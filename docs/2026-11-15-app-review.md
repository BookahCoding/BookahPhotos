# App review and video controls fix — 15 November 2026

Scope: source review of the viewer, favourites/albums controllers, search, and duplicate classification; targeted emulator checks. This is not a complete security audit or a phone acceptance test.

## Implemented

- Video playback now reports Media3 controller visibility to the viewer, so its top bar, filmstrip and bottom actions follow video taps and automatic hiding. Current callbacks are retained through recomposition and inactive pages cannot change the visible page's controls.
- Fully faded bars leave composition, removing invisible touch targets and accessibility actions for both photos and videos.
- Added a generated H.264 fixture and playback tests. Corrected the old poster test: its 30 rows reused calendar days, so sorting meant position 3 was a photo; the video tests now use 20 unique dates and explicitly confirm playback.

The integration uses Media3's [PlayerView controller visibility callback](https://developer.android.com/reference/androidx/media3/ui/PlayerView#setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener)).

## Meaningful improvements, in priority order

1. **Make unsuccessful favourite changes visible.** `android/app/src/main/kotlin/dev/bookah/photos/FavouritesController.kt`, `flushNow`, treats `Refused` like a completed operation and removes it from the queue without a user-facing error. Keep transient failures queued, distinguish rejected requests from successful saves, and show a concise failure/retry message. A filled heart should not quietly revert without explanation. Add controller tests for offline/reconnect and rapid changes.
2. **Finish video interruption and recovery behavior.** `android/feature-viewer/src/main/kotlin/dev/bookah/photos/viewer/Pages.kt` releases the player when its page leaves composition, but has no explicit app lifecycle pause/resume or audio-focus configuration. Add defined behavior for leaving the app, locking the phone, and interruptions; preserve playback position and offer retry after a network failure. Background behavior is a source-review concern, not reproduced here.
3. **Combine visual search with library filters.** `android/core-search/src/main/kotlin/dev/bookah/photos/search/TextEncoder.kt`, `SearchEngine.search`, ranks text embeddings against image vectors. It does not interpret dates, named people, or place metadata. Add person, date range, place, and photo/video filters so a search such as “beach” can be narrowed to a particular trip or person. Explicit filters are a predictable first step before natural-language date parsing.
4. **Make duplicate confidence clear.** `indexer/src/bookahphotos_indexer/build.py`, `derived`, groups compressed photos by size, timestamp, dimensions and MIME type, not content equality. Present these as possible duplicates, with side-by-side comparison and file details. Only offer verified-duplicate wording after content verification; any new original-file reads need separate approval under the project's library rules.
5. **Retain viewer bar state across hiding.** The fix removes invisible controls correctly, but recreating the bars also recreates DatePill and Filmstrip state. Hoisting loaded details and strip scroll state would avoid repeat lookups and preserve an independently scrolled strip. This is a minor follow-up, not a blocker for the tap fix.

## Verification

- Playing-video tap/hide/show regression passed on BookahPhotosTest, Android 14 emulator, using generated media only.
- Viewer suite: 23/24 passed in the full run; the remaining video-to-photo test passed after waiting for the photo double-tap recognizer to finish (test-only synchronization correction). Covers automatic hiding, playing/paused taps, swiping to a photo, share, favourites, albums, hidden-viewer controls, gestures and accessibility.
- The paused rapid-tap test caught overlapping native control animations; disabling the native animation fixed that transition while retaining the Compose bars' fade.
- Documented release script unit-test tasks succeeded (Gradle reused unchanged task outputs). Signed/minified 1.0.2 APK built successfully; release certificate matched the project's expected certificate. See HANDOFF for the artifact and hash.

## Not verified

- Xiaomi phone behavior, real Nextcloud video streaming, phone-specific codecs, or slow-network playback.
- The suggested improvements above are review findings, not implemented changes.
- Chris subsequently requested a PR and normal 1.0.2 GitHub release so the fix arrives through the app's updater. The PR remains open for review.
