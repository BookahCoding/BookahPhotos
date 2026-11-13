package dev.bookah.photos.library

/**
 * What can be done with the selected photos (the ids are Nextcloud file ids; negative ones are photos only on this phone). A null action has no button.
 * The same set of buttons is used in the Library and in every page of photos (an album, favourites, a place...).
 */
class SelectionActions(
    val onShare: ((LongArray) -> Unit)?,
    val onDelete: ((LongArray) -> Unit)?,
    val onHide: ((LongArray) -> Unit)? = null,
    /** Sets (true) or clears (false) the favourite flag. */
    val onFavourite: ((LongArray, Boolean) -> Unit)? = null,
    val onAddToAlbum: ((LongArray) -> Unit)? = null,
    /** In the Hidden photos page: shows the selected photos again. */
    val onUnhide: ((LongArray) -> Unit)? = null,
)
