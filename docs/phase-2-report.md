# BookahPhotos Phase 2 report: the Android app

Date: 12 November 2026. Status: **built, tested on an emulator with generated data, and used by Chris on his Xiaomi 15T through eight test builds (0.1.0 to 0.7.0, plus 0.8.0 ready).** Phase 3 (phone photos) was started and finished before this report, at Chris's request. Phase 4 has not started.

## 1. What exists

A signed Android app (arm64, Android 12 and later) that:

- signs in with Nextcloud Login Flow v2 and keeps the app password in the Android Keystore;
- downloads the index (snapshot) from `BookahPhotos/index/`, checks its size and checksum, keeps the previous one, refuses a newer major schema;
- shows the **Library** (All, Months, Years), with pinch columns, a floating date title, a fast scroller, **select by holding and sliding**, share, and delete to the trash (this phone, Nextcloud, or both, always after a confirmation that lists the counts);
- shows the **viewer** with swipe, zoom, original on zoom, filmstrip, details sheet, video, share, and the swipe-down close;
- shows **Collections** (Pinned, Albums, Recent days, Media types, On this phone) with reorder, hide and collapse; favourites and albums are read from Nextcloud and cached;
- merges the **photos of the phone** into the same library (one tile per photo, a phone mark on photos only on the phone, source filter All / Nextcloud / This phone, "Not backed up", device folders), and shows files uploaded to Nextcloud after the index was made;
- makes thumbnails from originals when Nextcloud has no preview;
- has Settings (account, index, phone photos, cache, updates, speed, About with the GeoNames credit), an offline notice, a release script and an update check that is off while the repository is private.

Design: `docs/phase-2-design.md`, `docs/design-spec.md`; comparison with the spec: `docs/phase-2-comparison.md`; release steps: `docs/release.md`.

## 2. Things found on the real library that changed the code

- Memories gives no preview for about 2% of files (HEIC variants, JPEG named .heic, camera RAW named .jpg): thumbnails are made from the originals once.
- Memories' time value is the clock time read as UTC for files without a timezone tag; 15,308 files were stored 2 or 3 hours late and sorted wrongly next to newer photos. Fixed in the indexer (metadata version 4) and re-read.
- 862 videos Memories does not know are now dated from their own header (version 5); only 38 files stay undated (35 RAW, 1 JPEG, 2 videos).
- A hold on a tile also fired the tile's own click when the finger was lifted (opened the photo or switched the selection off); the release is now consumed (an emulator test reproduces it).
- `nc:upload_time` finds files uploaded after the index was made, even when the photo's own file time is old.

## 3. Verified (how)

- **Unit tests:** 75 in the app's modules (index reading, merge with the phone, dates, matching, delete plan and the Nextcloud delete request against a mock server, trash safety, updates, sections) and 46 in the indexer.
- **Emulator tests with generated data only (40):** the Library screen with 50,000 items, the three views, select mode, hold, slide and diagonal slide selection, pinch keeps the place, phone photos read from MediaStore and shown, the viewer (date in the photo's clock, details, share, delete, swipe, zoom, close gestures), Collections and reorder, thumbnails from originals, and the size of the floating controls against the spec.
- **On Chris's phone (his reports):** sign-in, index download, thumbnails fast, very smooth scrolling, pinch, share, video, zoom, filmstrip, details, Collections, reorder, the offline notice, phone photos, the source filter, the corrected photo order, delete and its dialog, and (after fixes) the hold-to-select.
- **Speed on Chris's phone (Xiaomi 15T, 0.8.0, 24,732 items):** start to Library 190 ms (target 1.5 s), library build 61 ms, scrolling test at 120 Hz: 719 frames, 0.0% slow, slowest frame 8 ms (target under 1% slow).
- **Real server:** every Nextcloud request used by the app was checked read-only first, apart from deleting a real photo (Chris did that himself with his own photos).

## 4. Not verified

- A Macrobenchmark was not built; the speed targets were read from the in-app Speed box instead (see 3). The viewer's first-frame time was not measured.
- The real Android trash dialog was never run by me; Chris used it.
- Dragging with a real finger in select mode, on 0.6.1 and later (he tested 0.6.0, where it was broken).
- Drag handles for reordering, sort and filter menus, the format badge in the viewer and "share log" are not built (see the comparison).
- The in-app update check was tested only against a mock GitHub; it cannot be tried until the repository is public.
- Matches between phone photos and Nextcloud use file name and size (with a time check); no content hash yet.

## 5. Decisions and next steps

- The repository stays private until the app is used for a while; the update check then needs the repository public and `-PreleasesRepo`.
- **Phase 4** (the indexer reads the content of the photos: search vectors, faces, memories) needs Chris's yes.
- The release key folder `C:\BookahPC\bookahphotos\signing\` must be backed up outside BookahPC.
