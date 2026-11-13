package dev.bookah.photos.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** The op file the app writes (checked against the golden file the indexer's tests apply), the pending queue, the overlay and the upload. */
class OpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    // a made-up face: every photo has one face with the same box (x 0.1, y 0.1, width 0.2, height 0.3)
    private fun face(asset: Long, q: Float = 0.9f) = FaceBox(asset * 10, asset, 0.1, 0.1, 0.2, 0.3, q)
    private fun person(id: Long, name: String?, vararg assets: Long, hidden: Boolean = false) =
        PeopleReader.make(id, name, -1, hidden, assets.map { face(it) })
    private fun pending(vararg ops: kotlinx.serialization.json.JsonObject) = ops.map { PendingOp(it["op_id"].toString().trim('"'), OpsFormat.line(it), false) }

    // ---- the file format ----
    @Test fun `the writer produces exactly the golden file`() {
        fun id(n: Int) = "00000000-0000-4000-8000-%012d".format(n)
        fun t(n: Int) = "2026-11-13T09:30:%02dZ".format(n)
        val p1 = person(1, null, 1, 2, 3)
        val p2 = person(2, null, 9, 10, 11)
        val lines = listOf(
            OpsFormat.namePerson(id(1), t(1), p1, "Ελένη \"Έλα\""),
            OpsFormat.hidePerson(id(2), t(2), p2),
            OpsFormat.unhidePerson(id(3), t(3), p2),
            OpsFormat.setCover(id(4), t(4), p1, face(3)),
            OpsFormat.moveFace(id(5), t(5), face(12), null),
            OpsFormat.notAPerson(id(6), t(6), face(11)),
            OpsFormat.mergePeople(id(7), t(7), person(2, null, 9, 10), person(1, null, 1, 2)),
            OpsFormat.hidePhoto(id(8), t(8), 20),
            OpsFormat.unwantedMemory(id(9), t(9), "month:2024-03"),
            OpsFormat.unwantedMemory(id(10), t(10), "trip:2024-08-02:12345"),
            OpsFormat.wantedMemory(id(11), t(11), "month:2024-03"),
            OpsFormat.hidePhoto(id(12), t(12), 21),
            OpsFormat.hidePhoto(id(13), t(13), 22),
            OpsFormat.unhidePhoto(id(14), t(14), 21),
        ).joinToString("\n") { OpsFormat.line(it) } + "\n"
        val golden = javaClass.classLoader!!.getResourceAsStream("ops-golden.jsonl")!!.readBytes().toString(Charsets.UTF_8)
        assertEquals(golden, lines)
    }

    @Test fun `a person is named by their clearest faces from different photos, at most eight`() {
        val faces = (1L..12L).map { face(it, q = it / 20f) } + FaceBox(500, 12, 0.5, 0.5, 0.1, 0.1, 0.99f)   // a second face in photo 12
        val ref = OpsFormat.personRef(PeopleReader.make(7, "x", -1, false, faces))
        val anchors = ref["anchors"] as kotlinx.serialization.json.JsonArray
        assertEquals(8, anchors.size)
        val assets = anchors.map { (it as kotlinx.serialization.json.JsonObject)["file_id"].toString().toLong() }
        assertEquals(assets.size, assets.toSet().size)               // one per photo
        assertEquals(12L, assets.first())                            // the clearest first
    }

    // ---- the pending queue ----
    @Test fun `pending ops survive a restart, are marked uploaded, and are dropped when the snapshot has them`() = runBlocking {
        val f = tmp.newFile("pending.txt")
        val store = PendingOpsStore(f)
        store.add(OpsFormat.hidePhoto("a", "2026-11-13T10:00:00Z", 1)); store.add(OpsFormat.hidePhoto("b", "2026-11-13T10:00:01Z", 2))
        assertEquals(listOf("a", "b"), PendingOpsStore(f).all().map { it.opId })
        store.markUploaded(setOf("a"))
        assertEquals(listOf(true, false), PendingOpsStore(f).all().map { it.uploaded })
        f.appendText("garbage line\n")                                            // a damaged line is skipped, the rest stays
        assertEquals(listOf("a", "b"), PendingOpsStore(f).all().map { it.opId })
        assertEquals(1, store.dropApplied(setOf("a", "zzz")))
        assertEquals(listOf("b"), store.all().map { it.opId })
        store.dropApplied(setOf("b")); assertFalse(f.exists())
    }

    // ---- the overlay ----
    private val base = PeopleIndex(listOf(person(1, "Ann", 1, 2, 3, 4, 5), person(2, null, 6, 7, 8), person(3, null, 20, 21)))

    @Test fun `a name shows at once, and a typed name that exists is found for the same-person question`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.namePerson("n1", "2026-11-13T10:00:00Z", base.byId(2)!!, "Βασίλης")))
        assertEquals("Βασίλης", shown.byId(2)!!.name)
        assertEquals(listOf(1L), base.namedLike(" ann ").map { it.id })
        assertEquals(listOf(1L), base.namedLike("ANN", except = 2).map { it.id })
        assertTrue(base.namedLike("Ann", except = 1).isEmpty())
        assertEquals(listOf(1L), PeopleIndex(listOf(person(9, "Έλενα", 1))).namedLike("ελενα").map { it.id }.map { 1L })   // accents and case do not matter
    }

    @Test fun `hide and unhide move a person between the lists`() {
        val hidden = PeopleOverlay.apply(base, pending(OpsFormat.hidePerson("h1", "2026-11-13T10:00:00Z", base.byId(3)!!)))
        assertEquals(listOf(1L, 2L), hidden.people.map { it.id }); assertEquals(listOf(3L), hidden.hiddenPeople.map { it.id })
        val back = PeopleOverlay.apply(hidden, pending(OpsFormat.unhidePerson("h2", "2026-11-13T10:00:01Z", hidden.byId(3)!!)))
        assertEquals(3, back.people.size)
    }

    @Test fun `merging gives the faces and photos to the other person, which keeps its name`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.mergePeople("m1", "2026-11-13T10:00:00Z", base.byId(2)!!, base.byId(1)!!)))
        assertEquals(listOf(1L, 3L), shown.people.map { it.id })
        assertEquals(8, shown.byId(1)!!.photoCount); assertEquals("Ann", shown.byId(1)!!.name)
    }

    @Test fun `an op written for one person lands on the same people when the ids were swapped by a later regroup`() {
        // the op was written when the faces of photos 6, 7, 8 were person 2; in the new snapshot those faces are person 5 (and person 2 is somebody else)
        val op = pending(OpsFormat.namePerson("n2", "2026-11-13T10:00:00Z", base.byId(2)!!, "Μαρία"))
        val regrouped = PeopleIndex(listOf(person(5, null, 6, 7, 8), person(2, null, 20, 21), person(1, "Ann", 1, 2, 3, 4, 5)))
        val shown = PeopleOverlay.apply(regrouped, op)
        assertEquals("Μαρία", shown.byId(5)!!.name)                  // the person who holds those faces now
        assertEquals(null, shown.byId(2)!!.name)                     // not the one who now has the old id
    }

    @Test fun `an op whose person is not in this snapshot is left out`() {
        val ghost = person(9, null, 900, 901)
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.namePerson("n3", "2026-11-13T10:00:00Z", ghost, "Nobody")))
        assertTrue(shown.people.none { it.name == "Nobody" })
    }

    @Test fun `moving a face and not-a-person change who has the photo`() {
        val moveTo1 = pending(OpsFormat.moveFace("f1", "2026-11-13T10:00:00Z", face(6), base.byId(1)!!), OpsFormat.notAPerson("f2", "2026-11-13T10:00:01Z", face(21)))
        val shown = PeopleOverlay.apply(base, moveTo1)
        assertEquals(6, shown.byId(1)!!.photoCount); assertEquals(2, shown.byId(2)!!.photoCount); assertEquals(1, shown.byId(3)!!.photoCount)
    }

    @Test fun `not a person can be undone by giving the face back`() {
        val ops = pending(OpsFormat.notAPerson("u1", "2026-11-13T10:00:00.000Z", face(2)), OpsFormat.moveFace("u2", "2026-11-13T10:00:01.000Z", face(2), base.byId(1)!!))
        assertEquals(5, PeopleOverlay.apply(base, ops).byId(1)!!.photoCount)
        assertEquals(4, PeopleOverlay.apply(base, ops.take(1)).byId(1)!!.photoCount)
    }

    @Test fun `a cover chosen by the reader is used`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.setCover("c1", "2026-11-13T10:00:00Z", base.byId(1)!!, face(4))))
        assertEquals(40L, shown.byId(1)!!.coverFaceId)
    }

    // ---- the upload ----
    private fun uploader(device: String = "Xiaomi 15T") = OpsUploader(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"), device,
        now = { Instant.parse("2026-11-13T09:30:00Z") }, random = { "abc123" })

    @Test fun `ops are uploaded as one new file under the device folder, never overwriting`() = runBlocking {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(if (it < 2) 201 else 201)) }
        val ops = pending(OpsFormat.hidePhoto("a", "2026-11-13T09:00:00Z", 1), OpsFormat.hidePhoto("b", "2026-11-13T09:00:01Z", 2))
        val out = uploader().upload(ops)
        assertEquals(OpsUploader.Outcome.Sent(setOf("a", "b"), "BookahPhotos/ops/xiaomi-15t/20261113T093000Z-abc123.jsonl"), out)
        val mk1 = server.takeRequest(); val mk2 = server.takeRequest(); val put = server.takeRequest()
        assertEquals("MKCOL", mk1.method); assertEquals("/remote.php/dav/files/u/BookahPhotos/ops", mk1.path)
        assertEquals("/remote.php/dav/files/u/BookahPhotos/ops/xiaomi-15t", mk2.path)
        assertEquals("PUT", put.method); assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("/remote.php/dav/files/u/BookahPhotos/ops/xiaomi-15t/20261113T093000Z-abc123.jsonl", put.path)
        assertEquals(ops.joinToString("\n") { it.line } + "\n", put.body.readUtf8())
    }

    @Test fun `folders that already exist are fine, a taken name is retried, nothing pending sends nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405)); server.enqueue(MockResponse().setResponseCode(405)); server.enqueue(MockResponse().setResponseCode(412)); server.enqueue(MockResponse().setResponseCode(201))
        var n = 0
        val up = OpsUploader(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"), "p", random = { "r${n++}" })
        val out = up.upload(pending(OpsFormat.hidePhoto("a", "2026-11-13T09:00:00Z", 1)))
        assertTrue(out is OpsUploader.Outcome.Sent)
        assertEquals(4, server.requestCount)
        assertEquals(OpsUploader.Outcome.Nothing, up.upload(listOf(PendingOp("x", "{}", true))))
    }

    @Test fun `a failing server leaves the ops waiting, and a wrong password is reported`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(507))
        assertTrue(uploader().upload(pending(OpsFormat.hidePhoto("a", "2026-11-13T09:00:00Z", 1))) is OpsUploader.Outcome.Failed)
        server.enqueue(MockResponse().setResponseCode(401))
        val r = runCatching { uploader().upload(pending(OpsFormat.hidePhoto("a", "2026-11-13T09:00:00Z", 1))) }
        assertTrue(r.exceptionOrNull() is SnapshotSync.UnauthorizedException)
    }

    @Test fun `the app writes only below its own device folder`() {
        val u = uploader()
        assertTrue(u.isAllowed("BookahPhotos/ops/xiaomi-15t/20261113T093000Z-abc123.jsonl"))
        assertFalse(u.isAllowed("BookahPhotos/ops/applied/xiaomi-15t/x.jsonl"))
        assertFalse(u.isAllowed("BookahPhotos/ops/other-phone/x.jsonl"))
        assertFalse(u.isAllowed("BookahPhotos/ops/xiaomi-15t/../x.jsonl"))
        assertFalse(u.isAllowed("Photos/x.jsonl"))
        assertEquals("phone", OpsUploader.sanitizeDevice("applied")); assertEquals("phone", OpsUploader.sanitizeDevice("  "))
        assertNotEquals("applied", uploader("applied").device)
    }

    // ---- memories ----
    private fun mem(key: String, type: String, end: String, vararg assets: Long) = Memory(key, type, key, key, end, end, assets.first(), assets)

    @Test fun `unwanted memories come back with Undo, in time order`() {
        val ops = pending(
            OpsFormat.unwantedMemory("w1", "2026-11-13T10:00:00.000Z", "month:2024-03"), OpsFormat.unwantedMemory("w2", "2026-11-13T10:00:01.000Z", "trip:x"),
            OpsFormat.wantedMemory("w3", "2026-11-13T10:00:02.000Z", "month:2024-03"),
        )
        assertEquals(setOf("trip:x"), MemoryOverlay.unwantedKeys(ops))
        assertEquals(setOf("month:2024-03", "trip:x"), MemoryOverlay.unwantedKeys(ops.take(2)))
    }

    @Test fun `the memory row shows today's on-this-day only, then the newest trips and months, then people, and leaves out what the reader refused`() {
        val all = MemoriesIndex(listOf(
            mem("otd:11-13", Memory.TYPE_ON_THIS_DAY, "--11-13", 1), mem("otd:05-02", Memory.TYPE_ON_THIS_DAY, "--05-02", 2),
            mem("trip:a", Memory.TYPE_TRIP, "2023-08-01", 3), mem("month:2025-01", Memory.TYPE_MONTH, "2025-01-31", 4), mem("trip:old", Memory.TYPE_TRIP, "2016-03-13", 5),
            mem("person:1", Memory.TYPE_PERSON, "2026-12-31", 6), mem("person:3", Memory.TYPE_PERSON, "2026-12-31", 7),      // person 3 is hidden or merged away
            mem("month:gone", Memory.TYPE_MONTH, "2025-02-28", 999),                                                           // none of its photos is in the library
        ))
        val people = PeopleIndex(listOf(person(1, "Ann", 1, 2), person(3, "Bob", 5, 6, hidden = true)))
        val row = all.forRow(java.time.LocalDate.of(2026, 11, 13), setOf("trip:a"), people, { it < 100 }).map { it.key }
        assertEquals(listOf("otd:11-13", "month:2025-01", "trip:old", "person:1"), row)
        assertEquals(listOf("month:2025-01", "trip:a", "trip:old", "person:1"), all.forAll(java.time.LocalDate.of(2026, 5, 3), emptySet(), people, { it < 100 }).map { it.key })   // no on-this-day match today
    }
}

class HiddenPhotosTest {
    private fun op(id: String, t: String, type: String, fileId: Long) = PendingOp(id, OpsFormat.line(if (type == "hide_photo") OpsFormat.hidePhoto(id, t, fileId) else OpsFormat.unhidePhoto(id, t, fileId)), false)
    private fun row(id: Long, name: String, size: Long) = AssetRow(id, 20240101, 1_700_000_000L, 0, null, 0, false, name = name, size = size)
    private fun phone(mediaId: Long, name: String, size: Long) = LocalRow(mediaId, "content://media/external/images/media/$mediaId", name, size, 0, "image/jpeg", 4000, 3000, 0, "Camera", false)

    @Test fun `the latest correction wins, in time order`() {
        val d = HiddenPhotos.delta(listOf(op("a", "2026-11-13T10:00:02.000Z", "unhide_photo", 5), op("b", "2026-11-13T10:00:01.000Z", "hide_photo", 5), op("c", "2026-11-13T10:00:03.000Z", "hide_photo", 6)))
        assertEquals(mapOf(5L to false, 6L to true), d)
        assertEquals(setOf(1L, 6L), HiddenPhotos.effective(setOf(1L, 5L), d))                      // 5 is shown again, 6 is hidden, 1 stays hidden
    }

    @Test fun `a hidden photo with a copy on the phone is in neither the library nor phone-only, and comes back when shown again`() {
        val index = listOf(row(10, "IMG_1.jpg", 5000), row(11, "IMG_2.jpg", 6000))
        val locals = listOf(phone(7, "img_1.jpg", 5000), phone(8, "IMG_9.jpg", 900))              // the phone has the copy of 10 and a photo of its own
        val hidden = LocalMerge.merge(index, locals, java.time.ZoneId.of("Europe/Athens"), hidden = setOf(10))
        assertEquals(listOf(11L), hidden.rows.filter { it.id > 0 }.map { it.id })                   // 10 is not in the library
        assertEquals(listOf(10L), hidden.hiddenRows.map { it.id })
        assertEquals(1, hidden.rows.count { it.id < 0 })                                           // only the phone's own photo is phone-only: the copy of 10 did not turn up as one
        assertEquals(1, hidden.stats.phoneOnly)
        assertTrue(hidden.hiddenRows.single().localUri != null)                                    // the hidden row knows the phone copy
        val shown = LocalMerge.merge(index, locals, java.time.ZoneId.of("Europe/Athens"), hidden = emptySet())
        assertEquals(setOf(10L, 11L), shown.rows.filter { it.id > 0 }.map { it.id }.toSet())
    }

    @Test fun `faces in photos hidden by a pending correction are not shown`() {
        fun face(asset: Long) = FaceBox(asset * 10, asset, 0.1, 0.1, 0.2, 0.2, 0.9f)
        val base = PeopleIndex(listOf(PeopleReader.make(1, "Ann", -1, false, listOf(face(1), face(2))), PeopleReader.make(2, null, -1, false, listOf(face(3)))))
        val shown = PeopleOverlay.apply(base, listOf(op("h", "2026-11-13T10:00:00.000Z", "hide_photo", 3), op("h2", "2026-11-13T10:00:01.000Z", "hide_photo", 1)))
        assertEquals(listOf(1L), shown.people.map { it.id })                                       // person 2 had only a hidden photo
        assertEquals(1, shown.byId(1)!!.photoCount)
        assertTrue(shown.byId(1)!!.assetIds.toList() == listOf(2L))
        assertEquals(20L, shown.byId(1)!!.coverFaceId)                                             // the cover was in a hidden photo: the next face is used
    }
}

class DuplicateGroupsTest {
    @Test fun `a group with only one photo left is not shown any more, the others stay in order`() {
        val groups = mapOf(1L to 1L, 2L to 1L, 3L to 1L, 10L to 10L, 11L to 10L, 20L to 20L, 21L to 20L)
        // photo 2 and 3 of group 1 were deleted; photo 21 of group 20 was deleted too
        val shown = DuplicateGroups.visible(groups) { it != 2L && it != 3L && it != 21L }
        assertEquals(listOf(10L, 11L), shown)                                       // groups 1 and 20 have a single photo left
        assertEquals(listOf(20L, 21L, 10L, 11L, 1L, 2L, 3L), DuplicateGroups.visible(groups) { true })      // newest group first
        assertEquals(emptyList<Long>(), DuplicateGroups.visible(emptyMap()) { true })
    }
}
