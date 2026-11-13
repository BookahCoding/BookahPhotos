package dev.bookah.photos

import android.app.Application
import dev.bookah.photos.data.FaceBox
import dev.bookah.photos.data.OpsFormat
import dev.bookah.photos.data.OpsUploader
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.PeopleOverlay
import dev.bookah.photos.data.PeopleReader
import dev.bookah.photos.data.PendingOpsStore
import dev.bookah.photos.data.Person
import dev.bookah.photos.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What the People screens show: the people with the reader's own corrections on top, and how many corrections are not in the snapshot yet. */
data class PeopleState(
    val index: PeopleIndex = PeopleIndex.EMPTY,
    /** Corrections made on this phone that the snapshot does not contain yet (waiting for BookahPC's next daily run). */
    val waiting: Int = 0,
    /** Of those, the ones not sent to Nextcloud yet (no network, or the server said no). */
    val notSent: Int = 0,
    val error: String? = null,
    /** Memory keys the reader said "don't show" in corrections that the snapshot does not contain yet. */
    val unwantedMemories: Set<String> = emptySet(),
    /** Photos the reader hid (true) or showed again (false) in corrections the snapshot does not contain yet. */
    val hiddenPhotoDelta: Map<Long, Boolean> = emptyMap(),
)

/**
 * People (read from the snapshot) plus the reader's corrections: names, merges, hiding. A correction is saved on the phone first, shown at once, sent to
 * Nextcloud as an op file, and dropped from the phone when a snapshot arrives that lists its id as applied (schema 1.2 `applied_ops`).
 */
class PeopleController(
    app: Application,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val session: () -> Session?,
) {
    private val store = PendingOpsStore(File(app.filesDir, "people-ops.txt"))
    private val device = OpsUploader.sanitizeDevice(android.os.Build.MODEL ?: "phone")
    private val flushLock = Mutex()
    private val stateLock = Mutex()
    private var base = PeopleIndex.EMPTY

    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    /** A new snapshot is current: read its people, forget the corrections it already contains, show the rest on top, and send what is still unsent. */
    fun onSnapshot(file: File) {
        scope.launch(Dispatchers.IO) {
            val people = PeopleReader.load(file)
            val applied = PeopleReader.appliedOps(file)
            stateLock.withLock {
                base = people
                store.dropApplied(applied)
                republish()
            }
            flush()
        }
    }

    private suspend fun republish() {
        val ops = store.all()
        val index = PeopleOverlay.apply(base, ops)
        _state.value = _state.value.copy(index = index, waiting = ops.size, notSent = ops.count { !it.uploaded }, unwantedMemories = dev.bookah.photos.data.MemoryOverlay.unwantedKeys(ops), hiddenPhotoDelta = dev.bookah.photos.data.HiddenPhotos.delta(ops))
    }

    private fun now(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC).format(java.time.Instant.now())
    private fun newId(): String = UUID.randomUUID().toString()

    private fun submit(build: (id: String, t: String) -> JsonObject) {
        scope.launch(Dispatchers.IO) {
            stateLock.withLock { store.add(build(newId(), now())); republish() }
            flush()
        }
    }

    fun namePerson(p: Person, name: String) = submit { id, t -> OpsFormat.namePerson(id, t, p, name) }
    fun mergeInto(from: Person, into: Person) = submit { id, t -> OpsFormat.mergePeople(id, t, from, into) }
    fun hide(p: Person) = submit { id, t -> OpsFormat.hidePerson(id, t, p) }
    fun unhide(p: Person) = submit { id, t -> OpsFormat.unhidePerson(id, t, p) }
    fun setCover(p: Person, face: FaceBox) = submit { id, t -> OpsFormat.setCover(id, t, p, face) }
    fun moveFace(face: FaceBox, to: Person?) = submit { id, t -> OpsFormat.moveFace(id, t, face, to) }
    /** Hides photos of Nextcloud (not phone-only photos, which have a negative id and no file in Nextcloud). */
    fun hidePhotos(ids: LongArray) = submitMany(ids.filter { it > 0 }) { id, t, fileId -> OpsFormat.hidePhoto(id, t, fileId) }
    fun unhidePhotos(ids: LongArray) = submitMany(ids.filter { it > 0 }) { id, t, fileId -> OpsFormat.unhidePhoto(id, t, fileId) }

    private fun submitMany(fileIds: List<Long>, build: (id: String, t: String, fileId: Long) -> JsonObject) {
        if (fileIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            stateLock.withLock { for (f in fileIds) store.add(build(newId(), now(), f)); republish() }
            flush()
        }
    }

    fun unwantMemory(key: String) = submit { id, t -> OpsFormat.unwantedMemory(id, t, key) }
    /** The way back right after [unwantMemory]. */
    fun wantMemory(key: String) = submit { id, t -> OpsFormat.wantedMemory(id, t, key) }
    fun notAPerson(face: FaceBox) = submit { id, t -> OpsFormat.notAPerson(id, t, face) }

    /** Sends the corrections that are not sent yet (one new file). Safe to call at any time; does nothing without a sign-in or without network. */
    fun flush() {
        scope.launch(Dispatchers.IO) { flushNow() }
    }

    /** Like [flush] but the caller waits for it (before signing out). Returns the number of corrections still unsent. */
    suspend fun flushNow(): Int = flushLock.withLock {
        val s = session() ?: return@withLock _state.value.notSent
        val ops = store.all()
        try {
            when (val out = OpsUploader(http, s, device).upload(ops)) {
                is OpsUploader.Outcome.Sent -> { store.markUploaded(out.ids); stateLock.withLock { republish() }; _state.value = _state.value.copy(error = null) }
                is OpsUploader.Outcome.Failed -> _state.value = _state.value.copy(error = out.reason)
                OpsUploader.Outcome.Nothing -> Unit
            }
        } catch (e: dev.bookah.photos.data.SnapshotSync.UnauthorizedException) {
            _state.value = _state.value.copy(error = "sign-in expired")
        }
        _state.value.notSent
    }

    /** Sign-out: everything about people on this phone goes. */
    suspend fun clear() {
        stateLock.withLock { store.clear(); base = PeopleIndex.EMPTY; _state.value = PeopleState() }
    }

    /** The sign-in ended (expired): the screens forget the people, the unsent corrections stay in the file for the next sign-in. */
    suspend fun reset() {
        stateLock.withLock { base = PeopleIndex.EMPTY; _state.value = PeopleState() }
    }
}
