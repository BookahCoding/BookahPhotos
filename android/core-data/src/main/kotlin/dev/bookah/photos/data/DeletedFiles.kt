package dev.bookah.photos.data

import java.io.File

/**
 * Nextcloud files this phone moved to the Nextcloud trash, with the time. The index file still lists them until BookahPC publishes a new
 * one, so every re-read of it (restart, new download) would bring them back; they are filtered out until the index drops them, or after
 * [keepMs] so a file restored from the trash bin shows again.
 */
class DeletedFiles(private val file: File, private val clock: () -> Long = System::currentTimeMillis, private val keepMs: Long = 14L * 24 * 3600 * 1000) {
    var at: Map<Long, Long> = emptyMap()
        private set

    val ids: Set<Long> get() = at.keys

    fun load() {
        val limit = clock() - keepMs
        at = try {
            if (!file.exists()) emptyMap()
            else file.readLines().mapNotNull { l ->
                val p = l.split(',')
                val id = p.getOrNull(0)?.toLongOrNull()
                val t = p.getOrNull(1)?.toLongOrNull()
                if (p.size == 2 && id != null && t != null && t > limit) id to t else null
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun add(deleted: Set<Long>) {
        val now = clock()
        at = at + deleted.associateWith { now }
        save()
    }

    /** A new index that no longer lists a file means BookahPC has caught up: that file does not need remembering any more. */
    fun prune(listed: Set<Long>) {
        val kept = at.filterKeys { it in listed }
        if (kept.size != at.size) { at = kept; save() }
    }

    fun clear() { at = emptyMap(); file.delete() }

    private fun save() {
        try { file.writeText(at.entries.joinToString("\n") { "${it.key},${it.value}" }) } catch (e: Exception) { /* the in-memory list still works for this run */ }
    }
}
