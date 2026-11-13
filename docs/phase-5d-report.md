# Phase 5d report: memories and places (build 1 of 2), then the map (build 2)

Date: 13 November 2026. Status of **build 1: built and tested on an emulator and on the PC; test build 0.13.0 published as a pre-release. Not yet seen on the Xiaomi.** Chris said yes to 5d, chose **OpenStreetMap** for the map (build 2) and said **no data counter** (a "Wi-Fi only" switch was not asked for and is not built).

## Implemented (build 1)

- **Memories** (a new Collections section, second in the default order): a row of tall cards (cover and title, in Greek or English by the phone's language): today's "on this day" first (only today's, never the other 360), then the newest trips and months, then the people memories. The heading opens a page with all of them.
- **Playback:** tap a card for a full-screen playback: the pictures one after the other (4 seconds each), progress bars at the top, tap the right or left side to go forward or back, hold to pause; the last picture stays. The menu has **See all photos** and **Don't show this memory**.
- **Don't show this memory:** it asks first and says plainly that it can be undone only right after (a bar with **Undo** stays for 8 seconds); later there is no way back. The way back is a new op, `wanted_memory`, which the indexer applies (tested on both sides, in the golden file).
- **Memories of hidden or merged people are not shown** (the indexer already leaves them out; the phone also hides them at once when the reader hides someone). Memories are identified by their key, never by a number (the numbers change at every build).
- **Places** (a new Collections section): the places with photos, most photos first, names in Greek when the phone is Greek (GeoNames is credited in Settings > About); a place opens its photos, newest first.
- Phone state: memories and places are read from the snapshot with the library (both under 2 seconds).

## Verified (how)

- **On the real snapshot (generation 17), on the emulator:** memory counts per type equal SQL (85 months, 361 on-this-day, 30 people, 18 trips = 494); every memory has photos, the photos per memory equal SQL, every cover is one of its photos, keys are unique; photos per place equal SQL for all 118 places; 6,622 geotagged photos; reading all of it is fast (device test `ContentOnDeviceTest`).
- **Logic (JVM):** the row shows today's on-this-day only, then trips and months newest first, then people; leaves out refused memories, memories of hidden people and memories none of whose photos is in the library; Undo (`wanted_memory`) cancels a refusal in time order; place cards: photos newest first, Greek or English, most photos first, missing photos dropped; saved Collections orders from before still work (new sections go to the end).
- **Screens (emulator):** 5 tests of the memory row and the playback (opens, Greek titles, no section when empty, advances by itself and by tap and stops at the last picture, menu, asks before hiding); the older Collections, People and Faces tests still pass (32 UI tests in the module).
- **Indexer:** `wanted_memory` applied and read in the shared golden file (11 ops); 146 tests.

## Not verified

- Everything on Chris's phone (look, speed of playback with real photos, Greek texts).
- Playback of a memory of each of the four types: the real snapshot data of all four types is checked, but the playback screen was tested with made-up memories only.
- "Works offline": with BookahPC asleep everything works (the snapshot and previews come from Nextcloud). With **no internet at all** only cached pictures and the grey-blue placeholders show; not tested.
- Videos in a memory show their poster picture, not the video.

## Build 2 (next): the map
OpenStreetMap raster tiles with MapLibre: photos as clusters on a map, a tap opens the photos there. Rules I will follow (OSM tile policy): "© OpenStreetMap contributors" visible, an own User-Agent (no email), no pre-downloading, cache kept at about 50 MB, and **no Nextcloud login ever sent to the tile server** (tested). A minified build is run on the emulator before publishing, and the APK size increase is reported.

## What Chris needs to do
Install 0.13.0 and look at Collections: the Memories row (play one, try the menu, try "Don't show this memory" and Undo on a trip), the Places section and its pages. Tell me what looks or feels wrong.

---

# Build 2 (test build 0.14.0): the map

## Implemented
- **Map** (Collections > Places > the **Map** tile, first in the row): OpenStreetMap tiles drawn by MapLibre; every photo with a position is in a round marker (a small picture of the newest photo there, and a count when there are several). Tap a marker with several photos to zoom in on them; tap one that cannot be separated (or a single photo) to open its photos, newest first. Pan, pinch-zoom; no rotation or tilt. 6,622 of your photos have a position.
- **Markers are drawn by the app, not by the map library** (the advisor's suggestion was the library's own clustering): the library would need a map font server for the counts; the app's own grouping needs none and shows real pictures. Grouping is pure code with tests.
- **OSM rules kept:** "© OpenStreetMap contributors" is always shown on the map (tap it for OSM's copyright page); the map has **its own HTTP client** with User-Agent `BookahPhotos/<version> (private family photo app; Android)` (no email) that strips any Authorization or Cookie header, so **your Nextcloud login can never reach the tile server** (tested against a mock server); tiles are fetched only for what is on screen (no pre-download, no offline regions); the tile cache is capped at **50 MB**.
- The map screen forwards every life cycle step to the map view and destroys it when you leave.
- Chris: no data counter and no Wi-Fi switch (not built).

## Verified
- **Logic (JVM, 5 tests):** photos close together become one marker and far apart ones stay separate, every photo is in exactly one marker at every zoom, the newest photo is the cover, one-spot clusters never zoom; the User-Agent has no `@`, Authorization and Cookie are stripped even when a caller adds them; the style is one raster layer with the attribution and no fonts.
- **Screens (emulator, 5 + 2 tests):** attribution always visible; two photos at one spot are one marker with the count 2, the other photo is its own marker; tapping the count-2 marker opens exactly those two photos; the Places row starts with the Map tile and it opens the map; no section without places. A screenshot shows real OSM tiles with Greek names and the markers (emulator has internet).
- **The minified (R8) release build, run on the emulator:** built with a developer-only check (`-PmapSmoke=1`, compiled out of every published release) that opens the map with made-up photos without a sign-in; the minified build shows the map, tiles, markers and attribution, and the process did not crash (no `UnsatisfiedLinkError` or missing class from MapLibre; the only one in the log is an ONNX Runtime telemetry class that has always been there and is not used).
- All older module test suites pass again (collections 34 UI tests, map 5, core-data JVM, indexer 146).

## Not verified
- The map on Chris's phone: speed on mobile data, how the markers look with real thumbnails and 6,622 photos, how often the cache is hit.
- Data use is an estimate (about 0.2 to 0.5 MB for a fresh map screen, cached afterwards); it is not measured and there is no counter.
- Rotation is switched off on purpose; markers are redrawn on every camera move (fine for the 118 places, not tested with the whole library on a slow phone).

## APK size (arm64, signed, published)
**0.14.0 is 52,898,979 bytes (50.4 MB); 0.13.0 was 39,476,451 bytes (37.6 MB): the map adds 13.4 MB** (MapLibre's native map engine). Installed size is larger than the download.

---

# Build 3 (test build 0.15.0): the map remembers where it was, and Hidden photos behind the phone's unlock

## Implemented
- **Map camera:** when you open photos from a map marker (or a photo from them) and go back, the map comes back where it was (centre and zoom), instead of fitting all photos again. Opening the map afresh from Collections still starts with everything in view.
- **Hide photos:** the lock button in the photo viewer, and a lock button in the Library's Select bar (several at once). A confirmation says plainly what it does. Phone-only photos (not in Nextcloud) cannot be hidden and the dialog says how many of the selection are those.
- **Hidden:** Collections > **Utilities** > **Hidden** (a lock tile with no count and no picture). Opening it asks for **the phone's own unlock** (fingerprint or face if the phone counts it as good enough, else PIN, pattern or password; the app never sees them). If the phone has no screen lock, the app says so and does not open it. Hidden photos show newest first; the viewer there has **Unhide** and no share or delete (those photos are not in the library).
- **Locks again** when you leave the app, and the window is **secure** while hidden photos are on screen (black in screenshots, recordings and the recent-apps list).
- **Hidden photos disappear everywhere else at once:** Library, search, people (faces in hidden photos too), memories, places and the map; and they do not come back as "phone-only" or "Not backed up" copies (their phone copies are hidden with them) or as "new" files.
- **Indexer, snapshot schema 1.3:** the new table `hidden_assets` (the hidden photos; in no other table, checked by `verify.py`); `hide_photo`/`unhide_photo` ops (already existed) now have the way back in the app. **Generation 18 (schema 1.3) is published.**

## What the lock does and does not do (plain words)
- It guards **viewing in this app** only. The photos stay in **Nextcloud**, visible in Nextcloud's own apps and website. Their blurred placeholders and ids are in the app's private snapshot file on the phone.

## Verified
- **Indexer:** hidden photo in `hidden_assets` and nowhere else (assets, vectors, tags, faces, memories), showing it again makes a new generation; verify checks; golden file now 14 ops (hide, hide, unhide); 147 tests.
- **Phone logic (JVM):** latest correction wins in time order; **a hidden photo with a copy on the phone is in neither the library nor phone-only and comes back when shown**; faces in photos hidden by a pending correction are not shown (and the cover moves to another face).
- **Screens (emulator):** lock button hides the current photo; the hidden viewer offers Unhide and no Share or Delete; Utilities section with the Hidden tile; older suites pass (library 24, viewer 14, collections 35, map 6).
- **Unlock prompt and secure window, end to end on the emulator, minified build:** with no screen lock the app says so (no lock result); after setting a PIN the phone's prompt appears and the PIN unlocks; while the unlocked screen is shown, `screencap` returns nothing (secure window), while the same screen before unlocking is capturable. (This used a developer-only check compiled out of every published release; the PIN was removed again.)
- **Map camera:** an initial camera is applied (zoom 9.0 instead of fit-all) and reported back (test).

## Not verified
- On Chris's phone: the real fingerprint/face prompt, the whole loop (hide a photo, wait for BookahPC's next daily run, see it in Hidden), locking when the app is left (the lifecycle code is in place; the emulator check covered the prompt and the secure window, not leaving the app inside the real Home screen).
- Unhiding several photos at once is not built (one at a time from the Hidden viewer).
