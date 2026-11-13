package dev.bookah.photos.data

/**
 * Duplicates as the phone should show them right now. The snapshot says which photos were the same picture when it was made; after the reader deletes one
 * copy, the other copy is no longer a duplicate of anything, so a group with fewer than two photos still in the library is dropped at once (the next snapshot
 * will not have it either). Returns the photos group after group (newest group first, inside a group by file id).
 */
object DuplicateGroups {
    fun visible(groupOf: Map<Long, Long>, present: (Long) -> Boolean): List<Long> {
        val byGroup = HashMap<Long, MutableList<Long>>()
        for ((id, g) in groupOf) if (present(id)) byGroup.getOrPut(g) { ArrayList() }.add(id)
        return byGroup.entries.filter { it.value.size >= 2 }.sortedByDescending { it.key }.flatMap { it.value.sorted() }
    }
}
