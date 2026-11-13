package dev.bookah.photos.search

/** Where the search model on the phone stands. The 158 MB download only ever starts when the reader asks for it. */
sealed interface ModelUi {
    data object Checking : ModelUi
    /** The indexer has not put a model in Nextcloud yet (or it cannot be reached): search by content is not available, the category chips still are. */
    data object NotPublished : ModelUi
    data class Available(val sizeBytes: Long) : ModelUi
    data class Downloading(val done: Long, val total: Long) : ModelUi
    /** On the phone, starting up (reading the tokenizer and the model takes a second or two the first time). */
    data object Loading : ModelUi
    data object Ready : ModelUi
    data class Failed(val reason: String) : ModelUi
}

/** What a search found: [ids] are Nextcloud file ids, best first. [title] is the question or the category name. */
class SearchResultsUi(val title: String, val ids: LongArray, val viaCategory: Boolean, val millis: Long)

data class SearchUi(
    val model: ModelUi = ModelUi.Checking,
    /** Set when the snapshot's vectors and the model cannot be compared (no vectors, or made by another model). */
    val blocked: SearchBlock? = null,
    val hasVectors: Boolean = false,
    val labels: List<TagLabel> = emptyList(),
    val results: SearchResultsUi? = null,
    val searching: Boolean = false,
    val recents: List<String> = emptyList(),
) {
    /** Content search can run right now. */
    val canSearch: Boolean get() = model is ModelUi.Ready && blocked == null && hasVectors
}

/** What the search page can ask for. */
interface SearchActions {
    fun submit(question: String)
    fun pickCategory(label: TagLabel, greek: Boolean)
    fun clearResults()
    fun downloadModel()
    fun retryModel()
    fun clearRecents()
}
