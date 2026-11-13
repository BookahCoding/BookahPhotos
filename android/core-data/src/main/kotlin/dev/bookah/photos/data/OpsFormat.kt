package dev.bookah.photos.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.round

/**
 * The corrections (ops) the app writes for the indexer on BookahPC: one JSON object per line, as described in docs/ops-format.md. The JSON is built with
 * kotlinx.serialization (never by gluing strings), so Greek names and quotes are safe. A face is found by the indexer by overlap with a box
 * [x1, y1, x2, y2] in fractions of the upright picture: the snapshot stores x, y, width, height, so the box is converted here.
 */
object OpsFormat {
    const val ANCHORS = 8

    private fun r5(v: Double) = round(v * 100_000) / 100_000

    fun faceRef(f: FaceBox): JsonObject = JsonObject(mapOf(
        "file_id" to JsonPrimitive(f.assetId),
        "box" to JsonArray(listOf(f.x, f.y, f.x + f.w, f.y + f.h).map { JsonPrimitive(r5(it)) }),
    ))

    /** The person as the indexer will look for them: the id of this snapshot plus up to [ANCHORS] of their clearest faces from different photos. */
    fun personRef(p: Person): JsonObject {
        val seen = HashSet<Long>()
        val anchors = p.faces.sortedByDescending { it.quality }.filter { seen.add(it.assetId) }.take(ANCHORS)
        return JsonObject(mapOf("person" to JsonPrimitive(p.id), "anchors" to JsonArray(anchors.map { faceRef(it) })))
    }

    private fun op(opId: String, t: String, type: String, body: Map<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf<String, JsonElement>("op_id" to JsonPrimitive(opId), "t" to JsonPrimitive(t), "type" to JsonPrimitive(type)).also { it.putAll(body) })

    private fun withPerson(p: Person, extra: Map<String, JsonElement> = emptyMap()): Map<String, JsonElement> = personRef(p) + extra

    fun namePerson(opId: String, t: String, p: Person, name: String) = op(opId, t, "name_person", withPerson(p, mapOf("name" to JsonPrimitive(name.trim()))))
    fun hidePerson(opId: String, t: String, p: Person) = op(opId, t, "hide_person", withPerson(p))
    fun unhidePerson(opId: String, t: String, p: Person) = op(opId, t, "unhide_person", withPerson(p))
    fun setCover(opId: String, t: String, p: Person, face: FaceBox) = op(opId, t, "set_cover", withPerson(p, mapOf("face" to faceRef(face))))
    fun mergePeople(opId: String, t: String, from: Person, into: Person) = op(opId, t, "merge_people", mapOf("from" to personRef(from), "into" to personRef(into)))
    /** [to] null means "nobody". */
    fun moveFace(opId: String, t: String, face: FaceBox, to: Person?) = op(opId, t, "move_face", mapOf("face" to faceRef(face), "to" to (to?.let { personRef(it) } ?: JsonNull)))
    fun notAPerson(opId: String, t: String, face: FaceBox) = op(opId, t, "not_a_person", mapOf("face" to faceRef(face)))
    fun hidePhoto(opId: String, t: String, fileId: Long) = op(opId, t, "hide_photo", mapOf("file_id" to JsonPrimitive(fileId)))
    fun unhidePhoto(opId: String, t: String, fileId: Long) = op(opId, t, "unhide_photo", mapOf("file_id" to JsonPrimitive(fileId)))

    fun unwantedMemory(opId: String, t: String, key: String) = op(opId, t, "unwanted_memory", mapOf("key" to JsonPrimitive(key)))
    /** The way back for [unwantedMemory]. */
    fun wantedMemory(opId: String, t: String, key: String) = op(opId, t, "wanted_memory", mapOf("key" to JsonPrimitive(key)))

    /** The line written to the op file: compact JSON, no line break inside. */
    fun line(op: JsonObject): String = op.toString()
}
