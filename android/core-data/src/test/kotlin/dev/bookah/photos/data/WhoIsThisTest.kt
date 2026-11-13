package dev.bookah.photos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Who is this?": faces of nobody are given to a person, made into a new person, or marked as not a person; the phone shows it at once. */
class WhoIsThisTest {
    private fun face(asset: Long, q: Float = 0.9f) = FaceBox(asset * 10, asset, 0.1, 0.1, 0.2, 0.3, q)
    private fun person(id: Long, name: String?, vararg assets: Long) = PeopleReader.make(id, name, -1, false, assets.map { face(it) })
    private fun pending(vararg ops: kotlinx.serialization.json.JsonObject) = ops.map { PendingOp(it["op_id"].toString().trim('"'), OpsFormat.line(it), false) }

    private val nobody = listOf(face(50, 0.5f), face(51, 0.9f), face(52, 0.7f), face(53, 0.8f))
    private val base = PeopleIndex(listOf(person(1, "Ann", 1, 2, 3), person(2, null, 6, 7)), nobody)
    private val t = "2026-11-13T10:00:00.000Z"

    @Test fun `the faces of nobody come clearest first`() {
        assertEquals(listOf(510L, 530L, 520L, 500L), base.unassigned.map { it.id })
    }

    @Test fun `assigning faces to a person moves them out of the list and into the person`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.moveFace("a1", t, nobody[1], base.byId(1)!!), OpsFormat.moveFace("a2", t, nobody[3], base.byId(1)!!)))
        assertEquals(setOf(1L, 2L, 3L, 51L, 53L), shown.byId(1)!!.assetIds.toSet())
        assertEquals(listOf(520L, 500L), shown.unassigned.map { it.id })
    }

    @Test fun `a new person is made from faces of nobody, named, and shown at once with a local id`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.newPerson("n1", t, "Νέα", listOf(nobody[1], nobody[2]))))
        val made = shown.people.first { it.id < 0 }
        assertEquals("Νέα", made.name); assertEquals(2, made.photoCount)
        assertEquals(listOf(530L, 500L), shown.unassigned.map { it.id })
        assertEquals(3, shown.people.size)
    }

    @Test fun `a person made on the phone can be given more faces before the indexer has seen it`() {
        val first = pending(OpsFormat.newPerson("n1", t, "Νέα", listOf(nobody[1])))
        val madeId = PeopleOverlay.apply(base, first).people.first { it.id < 0 }.id
        val made = PeopleOverlay.apply(base, first).byId(madeId)!!
        val ops = first + pending(OpsFormat.moveFace("a3", "2026-11-13T10:00:05.000Z", nobody[0], made))
        val shown = PeopleOverlay.apply(base, ops)
        assertEquals(2, shown.people.first { it.id < 0 }.photoCount)
        assertEquals(setOf(520L, 530L), shown.unassigned.map { it.id }.toSet())                  // 51 made the person, 50 was added to it
    }

    @Test fun `not a person removes the face from the list, and taking a face from somebody puts it in the list`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.notAPerson("x1", t, nobody[0]), OpsFormat.moveFace("x2", "2026-11-13T10:00:01.000Z", face(6), null)))
        assertTrue(shown.unassigned.none { it.id == 500L })
        assertTrue(shown.unassigned.any { it.id == 60L })                                       // a face taken from person 2 is nobody's now
        assertEquals(1, shown.byId(2)!!.photoCount)
    }

    @Test fun `faces in photos hidden by a pending correction leave the list`() {
        val shown = PeopleOverlay.apply(base, pending(OpsFormat.hidePhoto("h1", t, 51)))
        assertTrue(shown.unassigned.none { it.assetId == 51L })
    }
}
