# BookahPhotos Phase 1 report: indexer v1 and the snapshot contract

Date: 2026-11-11. Status: **indexer built, tested and run on the real library; generation 2 published; TIFF placeholders left for 12 Nov.** Phase 2 has not started.

## 1. Summary

- The indexer lists `Photos` and `InstantUpload` in Nextcloud, reads dates, places and camera data, makes tiny placeholders and publishes an index snapshot (schema 1.0) with a manifest. It reads photos only through Nextcloud (previews and the Memories EXIF call) and writes only under `BookahPhotos/`.
- **Real library, stage 1 (listing and dates, no previews):** 24,683 files in 663 folders listed and read in 30 minutes, one request at a time. No failures.
- **Dates:** 23,783 files have a capture date (Memories). 900 have none: 864 videos (581 MP4, 283 MOV), 35 RAW (`.orf`) and 1 JPEG. Nextcloud's file time is not a usable fallback (see section 4). GPS is present on 6,669 files, all matched to a place.
- A run with no changes lists one folder and publishes nothing (verified on the test folder).

## 2. What was built (Chris approved the five points on 11 Nov)

| Part | Where |
| --- | --- |
| Config, credentials (Windows Credential Manager), WebDAV client that refuses to write outside `BookahPhotos/` | `indexer/src/bookahphotos_indexer/` |
| Scan with folder etags, deletion detection, safeguard | `scan.py`, `state.py` |
| Metadata rules (dates, offsets, GPS, flags), file-head fallback, places (offline GeoNames) | `metadata.py`, `places.py`, `enrich.py` |
| ThumbHash placeholders from 256 px previews, 2 workers, TIFFs last, resumable (`--minutes`) | `enrich.py` |
| Snapshot writer, manifest, publishing by PUT + MOVE, manifest last | `build.py`, `snapshot.py`, `publish.py` |
| Schema 1.0 with the later tables empty (vectors, faces, people, memories, tags) | `schema.py`, `docs/snapshot-schema.md` |
| Fake 50,000-item snapshot (11 MB without vectors, 1 second to make) | `fake.py`, `cli fake-snapshot` |
| 38 tests with mocked Nextcloud and generated images | `indexer/tests/` |

## 3. Verified on the real server (test folder with generated images, then the real library read-only)

- First run, add one / change one / remove one file: reflected correctly. The published manifest's SHA-256 and size match the downloaded snapshot.
- Second run without changes: one folder listed, nothing published.
- Listing a folder that does not exist stops the run, deletes nothing, publishes nothing.
- Safeguard: 4 of 7 files removed, run blocked with the previous snapshot untouched; `--accept-deletions` then published generation 3.
- Uploads of 11 MB and 60 MB work (14.9 s and 21.6 s).
- File-head fallback (the first 256 KB of a JPEG) works against the real server (5 of 7 test files, after `.nomedia` stopped Memories indexing the folder). Not yet seen on a real iPhone HEIC.
- Memories answers HTTP 404 for files it has not indexed (898 on the real library), which the indexer treats as "unknown", not as an error.

## 4. Findings that changed the code

1. Nextcloud refuses upload names ending in `.part`: temporary names use the prefix `upload-`.
2. For a file with no EXIF capture date, Memories sets `datetaken` to the file's sync time. It is trusted only when the EXIF has a real date tag; otherwise the date in the file name, otherwise none.
3. Videos: `datetaken` is local clock time as for photos; the EXIF also holds `DateTimeEpoch` (true UTC). The indexer derives the real offset from it, so a video shot outside Greece keeps its own offset. Checked on 3 videos and on the Xiaomi 15T photos (offset +02:00 in the EXIF).
4. A unit test found that folder etags saved before the deletion decision would hide a blocked deletion on the next run. They are now saved only after the whole scan succeeds.
5. The change digest must ignore bookkeeping columns, or an unchanged library publishes a new generation every run.
6. Metadata carries a rules version, so a later fix can be applied to all files (`--reread-metadata`).
7. File time is not a date: for 1,129 dated videos only 13% have a file time within a day of the capture date (median difference 683 days), and the undated videos' file times are their upload times (2024 and 2026).
8. Test images were showing in Chris's Memories timeline; they were removed and `BookahPhotos/test-root/.nomedia` was added.

## 5. Real library numbers (stage 1, 11 Nov, state in `C:\BookahPC\bookahphotos\indexer.db`)

| Measure | Value |
| --- | --- |
| Files listed | 24,683 (JPEG 16,922, HEIC 2,899, TIFF 2,748, MOV 1,240, MP4 751, PNG 84, RAW 35, AVI 2) in 663 folders |
| Time for listing and dates | 30 minutes (one request at a time, 0.05 s pause) |
| Dated / undated | 23,783 / 900 |
| With GPS and a place | 6,669 |
| Zone assumed (no recorded offset) | 0 (every dated file carried an offset or a UTC epoch) |
| Videos / screenshots / panoramas flagged | 1,993 / 8 / 18 |
| Unknown to Memories | 898 (videos, RAW) |
| Previews | first batch of 200: 165 made, the 35 RAW files have none; about 3 per second with 2 workers |
| Snapshot size so far | 8.0 MB for 24,683 assets without vectors (vectors will add about 25 MB) |

Photos per year (from the dates): 2006 to 2010 about 3,550; 2013 to 2016 about 5,300; **2018 5,255**; 2021 3,189; 2022 to 2026 about 4,300. Six files are dated 2000, possibly wrong clocks.

Session 1 (90 minutes, photos first, TIFFs last): see section 7.

## 6. Implemented, verified, not verified

**Implemented:** everything in section 2.

**Verified (how):** the real-server checks in section 3; 38 automated tests; the stage 1 run on the real library (counts from the private state database).

**Not verified:**
- The Android app reading the snapshot (Phase 2).
- Dates of the 864 undated videos and 35 RAW files; they stay undated in the snapshot and the app should show an "Unknown date" group. Reading them needs the originals (videos can have the date at the end of the file) and is a later decision.
- A real iPhone HEIC through the file-head fallback (no real file needed it: Memories knows all JPEG and HEIC files).
- Place quality: GeoNames `cities500` has places down to 500 people; a Corfu village photo may get a nearby larger place. Districts of cities (`PPLX`) are skipped.
- Duplicates (`duplicate_group` is empty) and the content hash (deferred, Nextcloud has no checksums) are not done; both were in the plan's list and move to Phase 3 and later.
- Effect of the preview load on the OptiPlex (no way to read its CPU from here); see section 7 for the response times seen.
- Nextcloud sync or phone upload (Chris checks those).

## 7. Session 1 and the rest of the first index

Session 1 ran 17:17 to 18:47 (90 minutes, 2 workers, photos first):

- Placeholders made: 13,926 (about 2.6 per second). 494 attempts failed and will be retried (up to 3 tries; none has given up yet).
- Published generation 1 to `BookahPhotos/index/`: 24,683 assets, 8.3 MB. The published files were read back and checked with `verify_published.py`.
- Totals now: 14,091 of 19,942 photos have a placeholder (the 35 RAW files cannot get one). Still to do: about 5,850 photos, then 1,993 videos and 2,748 TIFFs, roughly 70 more minutes in one more session.
- Session 2 (18:54 to 20:09, 75 minutes): 7,600 more placeholders, 588 failed attempts (to be retried), generation 2 published (24,683 assets, 8.5 MB; SHA-256 and size match; 21,691 placeholders in the file).
- Where it stands after session 2: photos 19,435 of 19,942 (472 to retry, 35 RAW cannot have one); videos 1,913 of 1,993; TIFFs 343 of 2,748. Chris decided the remaining TIFFs wait until 12 Nov (`run --minutes N`).
- Cleanup: the leftover test folder `BookahPhotos/index-test` was deleted. `BookahPhotos/test-root` (generated images) remains.
