package dev.bookah.photos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The sections of the Collections page that exist in this version. A saved order that does not know a section gets it at the end. */
enum class SectionId { Pinned, Memories, People, Places, Trips, Albums, RecentDays, MediaTypes, OnThisPhone, Utilities }

/**
 * The reader's choices for Collections: the order of the sections, which are hidden and which are collapsed.
 * Stored on the phone only. Unknown or missing entries are repaired when reading, so an old save never breaks a newer app.
 */
data class SectionPrefs(
    val order: List<SectionId> = SectionId.entries,
    val hidden: Set<SectionId> = emptySet(),
    val collapsed: Set<SectionId> = emptySet(),
) {
    /** The sections to show, in the chosen order, without the hidden ones. */
    fun visible(): List<SectionId> = order.filter { it !in hidden }

    fun move(id: SectionId, delta: Int): SectionPrefs {
        val i = order.indexOf(id)
        val j = (i + delta).coerceIn(0, order.lastIndex)
        if (i < 0 || i == j) return this
        val list = order.toMutableList().also { it.removeAt(i); it.add(j, id) }
        return copy(order = list)
    }

    fun toggleHidden(id: SectionId) = copy(hidden = if (id in hidden) hidden - id else hidden + id)
    fun toggleCollapsed(id: SectionId) = copy(collapsed = if (id in collapsed) collapsed - id else collapsed + id)
    fun showAll() = copy(collapsed = emptySet(), hidden = emptySet())
    fun collapseAll() = copy(collapsed = order.toSet())

    fun serialize(): String = listOf(order.joinToString(","), hidden.joinToString(","), collapsed.joinToString(",")).joinToString("|")

    companion object {
        private fun ids(text: String) = text.split(',').mapNotNull { n -> SectionId.entries.firstOrNull { it.name == n.trim() } }

        fun parse(text: String?): SectionPrefs {
            if (text.isNullOrBlank()) return SectionPrefs()
            val parts = text.split('|')
            val saved = ids(parts.getOrElse(0) { "" }).distinct()
            // sections the save does not know about (added in a newer version) go to the end
            val order = saved + SectionId.entries.filter { it !in saved }
            return SectionPrefs(order, ids(parts.getOrElse(1) { "" }).toSet(), ids(parts.getOrElse(2) { "" }).toSet())
        }
    }
}

private val Context.sectionDataStore by preferencesDataStore("collections")

class SectionPrefsStore(private val context: Context) {
    private val key = stringPreferencesKey("sections")
    val flow: Flow<SectionPrefs> = context.sectionDataStore.data.map { SectionPrefs.parse(it[key]) }
    suspend fun save(prefs: SectionPrefs) { context.sectionDataStore.edit { it[key] = prefs.serialize() } }
}
