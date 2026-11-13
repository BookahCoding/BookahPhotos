package dev.bookah.photos.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** One correction of the reader that the snapshot does not contain yet. [line] is the op exactly as it is (or was) uploaded. */
data class PendingOp(val opId: String, val line: String, val uploaded: Boolean) {
    val json: JsonObject get() = Json.parseToJsonElement(line).jsonObject
    val type: String get() = json["type"]!!.jsonPrimitive.content
}

/**
 * The reader's corrections kept on the phone until the snapshot confirms them (its `applied_ops` list has their id). They are saved before anything is shown
 * as done, so a name typed without network is not lost. Stored as one text file, one line per op: `0|1 <op json>` (1 = already uploaded).
 */
class PendingOpsStore(private val file: File) {
    private val lock = Mutex()

    suspend fun all(): List<PendingOp> = lock.withLock { read() }

    suspend fun add(op: JsonObject) = lock.withLock { write(read() + PendingOp(op["op_id"]!!.jsonPrimitive.content, OpsFormat.line(op), false)) }

    suspend fun markUploaded(ids: Set<String>) = lock.withLock { write(read().map { if (it.opId in ids) it.copy(uploaded = true) else it }) }

    /** Drops the ops the snapshot has applied. */
    suspend fun dropApplied(applied: Set<String>): Int = lock.withLock {
        val ops = read(); val keep = ops.filter { it.opId !in applied }
        if (keep.size != ops.size) write(keep)
        ops.size - keep.size
    }

    suspend fun clear() = lock.withLock { file.delete(); Unit }

    private fun write(ops: List<PendingOp>) {
        if (ops.isEmpty()) { file.delete(); return }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(ops.joinToString("\n") { "${if (it.uploaded) 1 else 0} ${it.line}" } + "\n")
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun read(): List<PendingOp> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { l ->
            if (l.length < 3) return@mapNotNull null
            runCatching {
                val line = l.substring(2)
                PendingOp(Json.parseToJsonElement(line).jsonObject["op_id"]!!.jsonPrimitive.content, line, l[0] == '1')
            }.getOrNull()
        }
    }
}
