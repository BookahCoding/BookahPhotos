# Phase 1 design: indexer v1 (metadata only) and the snapshot contract

Status: proposal, 2026-11-11. Chris said yes to Phase 1; the points marked **needs yes** are not started until he answers.

## What Phase 1 builds

An indexer on BookahPC that lists the photo roots in Nextcloud, finds new, changed and removed files, reads dates, places and basic data, and publishes an index snapshot. No vectors and no faces yet (Phase 4). Plus `docs/snapshot-schema.md` and a generator for a fake 50,000-item snapshot for app work.

## Findings from Phase 0 that shape the design

- **Nextcloud stores no checksums** for these files (`oc:checksums` is empty). A real content hash would mean downloading every original (364 GB). So Phase 1 does **not** compute a content hash. Phase 3's local-to-Nextcloud matching starts with file name, size and capture time, and a hash is computed lazily for a candidate pair only (the plan already says so).
- **Memories has a bulk call.** `GET /index.php/apps/memories/api/days` lists 1,580 days and 23,641 photos; `GET .../api/days/<dayid>` returns, for each file, `fileid`, `etag`, `w`, `h`, `size`, `mimetype`, `basename`, `epoch`, `indexed_at`. About 1,600 requests replace 24,000. It misses about 900 of the 24,539 files (not yet indexed by Memories or types it skips; to be listed in Phase 1).
- **What `datetaken` means:** the camera's local wall-clock time stored as if it were UTC. Checked on 10 files (iPhone HEIC and JPEG with offsets, PNG, Canon TIFF scans): `datetaken` equals the EXIF `DateTimeOriginal` read as UTC, and the offset (`OffsetTimeOriginal`, `+03:00`, `+02:00`) is ignored. So the true instant is `datetaken` minus the offset; if there is no offset (PNG, TIFF, old cameras) the indexer assumes the home zone (Europe/Athens) and marks the date as "assumed zone". Not yet checked: videos (MOV), because none of the sampled MOV files were present on disk.
- `GET /index.php/apps/memories/api/image/info/<id>` returns the EXIF subset the schema needs (camera, offsets, GPS) in about 0.04 s.
- Previews are generated on demand and slow when cold (0.7 s JPEG, 5.5 s TIFF); the whole library is about 24,000 cold previews.

## Architecture

1. **Roots from configuration.** A list of Nextcloud folders. The first config is `Photos` and `InstantUpload`; tests use a separate test root. `Family` is never listed.
2. **Listing.** WebDAV `PROPFIND` with `Depth: 1` folder by folder, using folder etags: if a folder's etag is unchanged since the last run, the whole subtree is skipped. If the root etag is unchanged the run ends at once. (To be confirmed on the test root: Nextcloud changes a folder's etag whenever anything beneath it changes.)
3. **A failed or timed-out listing aborts the run** with a report. It is never treated as "these files disappeared". The deletion safeguard (more than 5% of known photos gone) is a second line of defence, not the first.
4. **Metadata** from the Memories bulk day lists, and `image/info` for EXIF. For files Memories has not indexed: a ranged `GET` of the first 256 KB of the original (enough for the EXIF of most JPEG and HEIC files); video and large TIFF fallbacks are decided when we see how many there are.
5. **Place:** nearest named place from an offline GeoNames file (Creative Commons Attribution licence: the attribution goes in `docs/` and in the app's About screen). No network lookups.
6. **ThumbHash placeholder:** from the 256 px preview. This is the step that makes about 24,000 cold previews on the OptiPlex (see "needs yes").
7. **State:** private SQLite database in `C:\BookahPC\bookahphotos\` (not `spike\`), never in Nextcloud, never in Git. Logs hold counts and file ids only, no file names, no coordinates.
8. **Snapshot:** one SQLite file, written completely, then published. The later tables (`asset_vectors` with the model id and 512 dimensions, `faces`, `people`) exist from version 1.0 but are empty, so the app is built against the final shape. `meta` records the model names from Phase 0 (XLM-R ViT-B/32 laion5b, YuNet, SFace). Schema version `1.0`: a new major version means "update the app"; minor versions are accepted.
9. **Publishing (a plan change, needs yes):** the plan says to write the snapshot and the manifest into the PC's sync folder. A rename inside a synced folder can upload a half-written file, and sync progress is not something I may check on Chris's behalf. Proposal: upload with WebDAV `PUT` to a temporary name, then `MOVE` it to its final name (atomic on the server), verify with a `PROPFIND` (size), and write `manifest.json` last. The app then never sees a manifest that points to a missing or partial file.

## Layout of the repo

`indexer/` (a `uv` project pinned to Python 3.12), `docs/`, `android/` and `design/` (empty for now), `spike/` (Phase 0 code, kept), `tools/`, `AGENTS.md`, `CLAUDE.md`, `HANDOFF.md`.

## Tests

Mocked WebDAV and Memories responses for the extractors, change detection and the safeguard; generated images and hand-made metadata for odd cases (no capture date, wrong zone, midnight, videos, duplicates, Greek file names); a golden-snapshot test; the 50,000-item fake snapshot is fully synthetic. No real photos, names or coordinates anywhere in tests.

## Needs Chris's yes before it happens

1. **Push** the three local commits (9aba46c, 576f65c, fe49f82) to the private repo.
2. **A separate test root** `BookahPhotos/test-root/` in his Nextcloud, holding only generated images, for the "add a file, remove a file" checks. The real `Photos` folder is never written to. The folder is created by me through WebDAV only after his yes.
3. **The first real run on `Photos`** is throttled (1 to 2 requests at a time), resumable, TIFFs last, and **grows the Nextcloud preview cache** on the CLOUD disk. Cold previews cost about 0.7 s per JPEG and 5.5 s per TIFF on the OptiPlex (8 GB). Until he says yes, development and testing use the test root only.
4. **App password:** keep using the existing `BookahPhotos-test`, or create one named `BookahPhotos-indexer` as the plan prescribes. Recommendation: create the indexer one before the real run.
5. **First write of the snapshot** into `BookahPhotos/index/` (the folder he approved).
