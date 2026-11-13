package dev.bookah.photos.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Hidden photos: the reader hides Nextcloud photos with the op `hide_photo` and shows them again with `unhide_photo`. A hidden photo is left out of the
 * snapshot's other tables and listed in `hidden_assets`. Until the indexer has applied an op, the phone applies it itself ([delta]).
 */
object HiddenPhotos {
    /** For every photo the reader's pending corrections touch: true = hide, false = show again. The latest correction (by time) wins. */
    fun delta(ops: List<PendingOp>): Map<Long, Boolean> {
        val out = HashMap<Long, Boolean>()
        for (op in ops.sortedBy { it.json["t"]?.toString().orEmpty() }) {
            if (op.type != "hide_photo" && op.type != "unhide_photo") continue
            val id = (op.json["file_id"] as? JsonPrimitive)?.longOrNull ?: continue
            out[id] = op.type == "hide_photo"
        }
        return out
    }

    /** The hidden photos as they should be now: the snapshot's hidden photos, with the pending corrections on top. */
    fun effective(snapshotHidden: Set<Long>, delta: Map<Long, Boolean>): Set<Long> {
        val out = HashSet(snapshotHidden)
        for ((id, hide) in delta) if (hide) out.add(id) else out.remove(id)
        return out
    }
}
