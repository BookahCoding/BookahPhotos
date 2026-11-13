# Snapshot schema 1.3

The contract between the indexer (BookahPC) and the Android app. The code is `indexer/src/bookahphotos_indexer/schema.py`; this page explains it.

**1.3 (Phase 5d)** adds `hidden_assets (id, path, etag, size, mime_type, taken_utc, day_key, duration_ms, flags, thumbhash)`: the photos the reader hid with `hide_photo`. They are in no other table (not in `assets`, `asset_vectors`, `tags`, `faces`, `memory_assets`; `verify.py` checks it), so Library, search, people, memories, places and the map never show them; the app lists them only behind an unlock prompt so they can be un-hidden. They stay in Nextcloud. `PRAGMA user_version` is 1003.

**1.2 (Phase 5c)** adds one table, `applied_ops (op_id TEXT PRIMARY KEY)`: the ids of the corrections (ops, `docs/ops-format.md`) that the indexer has applied to its database, so the app can drop its own pending copies of them. A snapshot without this table (1.1) means: nothing is known to be applied. `PRAGMA user_version` is 1002.

**1.1 (Phase 4)** only adds to 1.0: the content tables are filled (`asset_vectors`, `tags`, `people`, `faces`, `memories`, `memory_assets`), there is a new table `labels`, and `memories` has a new column `key`. Apps that read 1.0 ignore all of it. `PRAGMA user_version` is 1001. Details are in the section "Content tables (1.1)" below.

## Files in `BookahPhotos/index/` (in Chris's Nextcloud)

| File | What |
| --- | --- |
| `manifest.json` | Written **last**. Schema version, generation, creation time, snapshot file name, size, SHA-256, counts, model names. |
| `bookahphotos-g<generation, 6 digits>.db` | The snapshot: one SQLite file, never edited after publishing. The newest two are kept. |
| `.nomedia` | So nothing in the folder shows up as a photo. |
| `upload-*` | Temporary names while uploading (Nextcloud refuses names ending in `.part`). Removed after each publish. |

The app reads `manifest.json` (a few hundred bytes), downloads a new snapshot only when `generation` changed, checks `size` and `sha256`, then switches to it.

Publishing order: upload under the temporary name, move to the final name (atomic on the server), compare the size, then write the manifest. If the size does not match, no manifest is written.

## Versions

`PRAGMA user_version` = major × 1000 + minor (1.0 → 1000), also in `meta` (`schema_major`, `schema_minor`) and the manifest. The app refuses a newer **major** ("update the app") and accepts any newer minor (it ignores columns and tables it does not know). Minor changes only add columns or tables.

## Tables

| Table | Columns | Filled in Phase 1? |
| --- | --- | --- |
| `meta` | key, value (schema version, generation, created, roots, home zone, models, GeoNames attribution) | yes |
| `places` | id (GeoNames id), name, name_el, region, country, latitude, longitude. Only places used by an asset. | yes |
| `assets` | see below | yes |
| `asset_vectors` | asset_id, model_id, dim, vec (float16 little endian) | Phase 4: yes (1.1) |
| `people`, `faces` | people: id, name, cover_face_id, hidden, face_count. faces: id, asset_id, x, y, w, h (fractions), person_id, quality | Phase 4: yes (1.1) |
| `memories`, `memory_assets`, `tags`, `labels` | see "Content tables (1.1)" | Phase 4: yes (1.1) |

Face vectors never go into the snapshot (they stay in the indexer's private database). Every table is read-only for the app; the app keeps its own data in a separate database.

### Content tables (1.1)

- **`asset_vectors`**: one row per asset that has a vector: `model_id` = `xlmr-vit-b32-laion5b`, `dim` = 512, `vec` = 512 little-endian float16, length 1. A typed question is turned into a vector by the text half of the same model and compared by dot product. Assets without a vector (about 120: videos without a poster frame, broken files) have no row.
- **`labels`**: `key`, `name_en`, `name_el` of the tag labels (49 in Phase 4; the key never changes once published). **`tags`**: `asset_id`, `label_key`, `score` (cosine against the label vector); at most 5 tags per asset; only scores above the per-label threshold agreed with Chris are stored.
- **`people`**: only people with at least 5 faces (and not merged into another person). `name` is NULL until Chris names the person; `hidden` = 1 when he hid the person (the row stays so the app can show a "hidden people" list); `cover_face_id` is the chosen or best face; `face_count` is the number of rows in `faces` with that person.
- **`faces`**: every usable face (clear enough and big enough) as a box in fractions of the **upright** picture (the same picture the preview shows). `person_id` is NULL for faces of nobody yet ("Who is this?" suggestions in Phase 5) and for faces of people with fewer than 5 faces. Faces marked "not a person" are left out. `quality` is the detector score. **Never any face description.**
- **`memories`**: `key` (stable text such as `trip:2024-08-02:12345`, `month:2024-03`, `otd:08-10`, `person:7`; what the op `unwanted_memory` refers to), `type` (`trip`, `month`, `on_this_day`, `person`), `title_en`, `title_el`, `start_day`, `end_day` (`YYYY-MM-DD`; for `on_this_day` the form `--MM-DD`), `cover_asset`. **`memory_assets`**: up to 40 (trips 60) assets per memory, `position` in time order.
- **On this day**: the app shows the memory whose key matches today's month and day, and hides it when all its photos are from the current year (the memory lists photos from the day itself and one day either side).
- **Photos hidden by an op** (`hide_photo`) are left out of every table, `assets` included. The files stay in Nextcloud.
- `assets.indexed_level`: 2 when the asset has a vector, 3 when its faces were read as well.
- `meta` also holds `vectors`, `people`, `faces`, `memories` (counts) and `models`.

### `assets`

| Column | Meaning |
| --- | --- |
| `id` | The Nextcloud file id. Stable when a file is renamed or moved. Previews are requested by it. |
| `path`, `etag`, `size`, `mime_type` | From the WebDAV listing. |
| `taken_utc` | Capture time, seconds since 1970 UTC. NULL when no date is known. |
| `taken_offset_min` | Local UTC offset in minutes at capture (180 = +03:00). |
| `taken_zone_assumed` | 1 when no offset was recorded and the home zone (Europe/Athens) was assumed. |
| `taken_source` | `memories` (EXIF read by Memories), `exif-head` (EXIF from the first 256 KB of the file), `exif`, `filename` (date in the file name), `none`. |
| `day_key` | Local calendar day `YYYY-MM-DD` (what the Library groups by). NULL when no date. |
| `width`, `height`, `duration_ms` | Duration is empty in Phase 1. |
| `latitude`, `longitude`, `place_id` | GPS from EXIF; the nearest GeoNames place within 25 km. |
| `camera`, `lens`, `exposure` | Exposure is JSON (time, f-number, ISO, focal length). |
| `flags` | Bits: 1 video, 2 screenshot, 4 selfie, 8 panorama, 16 motion photo (selfie and motion photo are not set in Phase 1). |
| `content_hash` | **NULL in version 1**: Nextcloud stores no checksums for these files, and hashing means downloading the originals. Filled lazily later (Phase 3 matching). |
| `thumbhash` | About 25 bytes, a tiny placeholder drawn instantly in the grid. Built from the 256 px preview; NULL until that preview has been fetched. |
| `duplicate_group` | NULL in Phase 1. |
| `indexed_level` | 0 listed only, 1 metadata, 2 plus vector, 3 plus faces. The snapshot contains level 1 and above. |

## Dates: what was found on a real server

- Memories' `datetaken` is the camera's **local clock time stored as if it were UTC**; the EXIF offset is ignored. The real instant is `datetaken` minus the offset.
- For a file with **no EXIF capture date**, Memories fills `datetaken` with the file's sync time (the day it was uploaded). The indexer ignores it in that case and uses the date in the file name, or leaves the date empty. The app should show undated photos in an "Unknown date" place, not by guessing.
- Videos: `datetaken` is local clock time as for photos; the EXIF also holds `DateTimeEpoch` (true UTC), from which the real offset is derived (verified on real files). Memories also gives `exif.Duration` (seconds) for videos; `duration_ms` will be filled from it (indexer metadata version 3, planned with Phase 2).
- Files Memories has not indexed (about 900: videos and RAW) stay undated. Nextcloud's file time is **not** a usable date (checked: only 13% of dated videos have a file time within a day of the capture date).

## Attribution

Place names come from GeoNames (<https://www.geonames.org/>), licensed Creative Commons Attribution 4.0. The app's About screen must credit it. The credit is also in `meta.attribution`.
