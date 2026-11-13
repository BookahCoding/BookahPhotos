package dev.bookah.photos.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The reader's own corrections shown at once, on top of the snapshot, until the snapshot contains them (then they are dropped, see [PendingOpsStore]).
 *
 * Every op is read the way the indexer reads it: a person is found by their ANCHORS (faces: same photo, boxes overlapping by at least half), and the id
 * is only trusted when the anchors agree. Ids can change when the indexer regroups, so an old op must never land on the wrong person. An op whose person or
 * face cannot be found in this snapshot is left out here (it stays pending).
 */
object PeopleOverlay {
    private const val IOU_MIN = 0.5

    private class W(val id: Long, var name: String?, var hidden: Boolean, var cover: Long, var faces: MutableList<FaceBox>)

    fun apply(base: PeopleIndex, ops: List<PendingOp>): PeopleIndex {
        if (ops.isEmpty()) return base
        val all = (base.people + base.hiddenPeople).associate { it.id to W(it.id, it.name, it.hidden, it.coverFaceId, it.faces.toMutableList()) }.toMutableMap()
        val mergedInto = HashMap<Long, Long>()
        // the faces of the snapshot by photo (an op's anchors are looked up here) and who holds them in this snapshot
        val byAsset = HashMap<Long, MutableList<Pair<FaceBox, Long>>>()
        for (p in base.people + base.hiddenPeople) for (f in p.faces) byAsset.getOrPut(f.assetId) { ArrayList() }.add(f to p.id)
        val moved = HashMap<Long, Long?>()          // faces moved by ops: face id -> person id (null = nobody)

        fun chain(id: Long): Long {
            var c = id
            repeat(50) { c = mergedInto[c] ?: return c }
            return c
        }
        fun box(e: JsonElement): DoubleArray? = runCatching { e.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }.toDoubleArray().takeIf { it.size == 4 } }.getOrNull()
        fun iou(a: DoubleArray, f: FaceBox): Double {
            val bx2 = f.x + f.w
            val by2 = f.y + f.h
            val ix = maxOf(0.0, minOf(a[2], bx2) - maxOf(a[0], f.x))
            val iy = maxOf(0.0, minOf(a[3], by2) - maxOf(a[1], f.y))
            val inter = ix * iy
            val union = (a[2] - a[0]) * (a[3] - a[1]) + f.w * f.h - inter
            return if (union > 0) inter / union else 0.0
        }
        fun findFace(ref: JsonObject?): FaceBox? {
            ref ?: return null
            val asset = ref["file_id"]?.jsonPrimitive?.longOrNull ?: return null
            val b = ref["box"]?.let { box(it) } ?: return null
            var best: FaceBox? = null
            var bestIou = IOU_MIN
            for ((f, _) in byAsset[asset].orEmpty()) {
                val v = iou(b, f)
                if (v >= bestIou) { best = f; bestIou = v }
            }
            return best
        }
        fun holder(f: FaceBox): Long? = if (f.id in moved) moved[f.id]?.let { chain(it) } else byAsset[f.assetId]?.firstOrNull { it.first.id == f.id }?.second?.let { chain(it) }
        fun resolve(ref: JsonObject?): W? {
            ref ?: return null
            val pid = ref["person"]?.jsonPrimitive?.longOrNull
            val votes = HashMap<Long, Int>()
            var resolvable = 0
            for (a in ref["anchors"]?.jsonArray.orEmpty()) {
                val f = findFace(a as? JsonObject) ?: continue
                resolvable++
                holder(f)?.let { votes.merge(it, 1, Int::plus) }
            }
            if (pid != null && pid in all) {
                val c = chain(pid)
                if (resolvable == 0 || (votes[c] ?: 0) * 2 >= resolvable) return all[c]
            }
            val best = votes.maxByOrNull { it.value } ?: return null
            return if (best.value * 2 >= resolvable) all[best.key] else null
        }
        fun detach(f: FaceBox) { all.values.forEach { w -> w.faces.removeAll { it.id == f.id } } }

        for (op in ops.sortedBy { it.json["t"]?.jsonPrimitive?.content.orEmpty() }) {
            val j = op.json
            runCatching {
                when (op.type) {
                    "name_person" -> resolve(j)?.let { it.name = j["name"]!!.jsonPrimitive.content.trim().ifBlank { null } }
                    "hide_person" -> resolve(j)?.hidden = true
                    "unhide_person" -> resolve(j)?.hidden = false
                    "set_cover" -> {
                        val p = resolve(j)
                        val f = findFace(j["face"] as? JsonObject)
                        if (p != null && f != null && p.faces.any { it.id == f.id }) p.cover = f.id
                    }
                    "merge_people" -> {
                        val a = resolve(j["from"] as? JsonObject)
                        val into = resolve(j["into"] as? JsonObject)
                        if (a != null && into != null && a.id != into.id) {
                            into.faces.addAll(a.faces)
                            if (into.name == null) into.name = a.name
                            a.faces = mutableListOf()
                            mergedInto[a.id] = into.id
                            all.remove(a.id)
                        }
                    }
                    "move_face" -> {
                        val f = findFace(j["face"] as? JsonObject)
                        val toRef = j["to"]
                        val to = if (toRef == null || toRef is JsonNull) null else resolve(toRef as? JsonObject)
                        val wantsPerson = !(toRef == null || toRef is JsonNull)
                        if (f != null && (!wantsPerson || to != null)) {
                            detach(f)
                            to?.faces?.add(f)
                            moved[f.id] = to?.id
                        }
                    }
                    "not_a_person" -> findFace(j["face"] as? JsonObject)?.let { f -> detach(f); moved[f.id] = null }
                }
            }
        }
        // faces in photos the reader hid (and the snapshot does not know yet) are not shown either
        val hiddenNow = HiddenPhotos.delta(ops).filterValues { it }.keys
        return PeopleIndex(all.values.map { w -> w to w.faces.filter { it.assetId !in hiddenNow } }.filter { (_, f) -> f.isNotEmpty() }.map { (w, f) -> PeopleReader.make(w.id, w.name, if (f.any { it.id == w.cover }) w.cover else -1, w.hidden, f) })
    }
}
