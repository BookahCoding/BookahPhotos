# Phase 2 design: app foundation, Library and viewer

Status: **proposal, nothing built yet.** Written 11 Nov 2026 after Phase 1. It follows plan section 12 (Phase 2) and needs Chris's yes on the list in section 8. The look of every screen is in `docs\design-spec.md`.

## 1. What Phase 2 delivers

A signed Android app (Xiaomi 15T first) that:

1. Signs in to Chris's Nextcloud with Login Flow v2 and keeps the app password in the Android Keystore.
2. Checks `BookahPhotos/index/manifest.json`, downloads a new snapshot when `generation` changed, checks size and SHA-256, switches to it.
3. Shows the **Library**: grid of every photo and video, newest at the bottom, pinch density, Years / Months / All, floating date title, fast scroll, select mode (share only).
4. Shows the **viewer**: transition from the cell, swipe, zoom, filmstrip, details sheet, video playback.
5. Shows a **Collections** subset, read-only: Favourites, Albums, Media types, Recent days, with section reorder / collapse / hide (Chris's decision).
6. Handles **offline** ("Nextcloud is not reachable; is Tailscale on?"), and has Settings: account, cache, about (GeoNames credit).
7. Has a release workflow and an in-app update check (off while the repo is private).

Not in Phase 2 (by plan and by safety rule): local phone photos (Phase 3), people, search, places map, memories (Phases 4 and 5), and **any change to a photo or to Nextcloud data**: no delete, no favourite toggle, no album edits (Phase 6). The heart in the viewer shows state only.

## 2. Findings that shape the design

| Topic | Finding | Decision |
| --- | --- | --- |
| Video length | `duration_ms` is empty in the snapshot, but Memories' `image/info` for videos has `exif.Duration` (seconds, checked on 4 real videos). | Small indexer addition: `METADATA_VERSION = 3` reads the duration for the 1,993 videos (one pass, a few minutes). No schema change (the column exists). The grid badge shows the length once the snapshot has it; until then only a video icon. |
| TIFF | 2,748 TIFFs; Android cannot decode TIFF. Nextcloud makes JPEG previews of them. | The viewer uses a large preview (2048 px) as the "full quality" image for TIFF; share still sends the original. |
| RAW | 35 `.orf` files: no preview at all, no placeholder. | A labelled tile ("RAW") in the grid and viewer; details work; share sends the original. |
| HEIC / JPEG / PNG | Decoded by Android (API 31+ has HEIC). | Original used when zooming. |
| Undated | 900 files have `taken_utc` NULL. | One **"Unknown date"** group at the **top** of the Library (the oldest end, so it never sits between the newest photos and the thumb). In Years and Months it is the first card. Never guessed from file time. |
| Favourites, albums | Not in the snapshot; live in Nextcloud (Phase 0 recorded the requests: `REPORT` on files for favourites, `/remote.php/dav/photos/<user>/albums`). | Fetched when Collections opens and when the app starts online; cached in the app's own database so they show offline. |
| Real snapshot | Contains paths and GPS. | Never copied into the repo, tests, assets or logs. Development and the 10.3 benchmarks use `cli fake-snapshot --count 50000`. |

## 3. Architecture

- **Kotlin, Jetpack Compose, single activity**, edge to edge, predictive back. minSdk 31, compileSdk/targetSdk 36, **arm64-v8a only** (as the spike). AGP 9.4.1, Gradle 9.8.0, `local.properties` with forward slashes, and `core-ktx` 1.19.1 stays out (needs compileSdk 37): all learned in Phase 0.
- **Modules, start small:** `app`, `core-data` (snapshot, Nextcloud client, caches), `design` (theme, components), `feature-library`, `feature-viewer`, `feature-collections`, `feature-settings`. `core-ml` and `feature-search` are created in Phases 4 and 5. Fewer modules now avoids empty scaffolding.
- **Snapshot access:** opened read-only with the platform SQLite. At open the app reads only the columns the grid needs (id, day_key, taken_utc, offset, flags, width, height, thumbhash) into compact in-memory arrays, already sorted (24,683 rows is about 2 MB; 50,000 stays small). The grid, the section headers, the Years and Months cards and the fast-scroll labels are computed from those arrays, so scrolling never waits for the database. Details come from a per-asset query when the sheet opens.
- **App database (Room):** favourites cache, albums cache, Collections section order and visibility, last-seen manifest. Separate from the snapshot, as the plan requires.
- **Pictures:** Coil 3 with OkHttp and an interceptor that adds Basic auth. Grid: `core/preview?fileId=<id>&x=256&y=256&a=1`; viewer first frame about 1024 px; full image on zoom. ThumbHash is decoded by a small Kotlin port and drawn at once. Memory cache plus a capped disk cache (default 2 GB, "clear cache" button).
- **Viewer is an overlay of the Library screen, not a navigation destination.** This makes the grow-from-cell and swipe-down-to-cell transitions (shared bounds) and predictive back simple and reliable. Paging uses the same in-memory order, so swiping goes through the whole library.
- **Video:** Media3 ExoPlayer, streaming the original with range requests and the auth header; paused when the page is left.
- **Sign-in:** Login Flow v2 (the app opens the Nextcloud page in the browser, Chris approves, the app receives a revocable app password). The server address is typed once (no default in the code). Stored with an Android Keystore AES-GCM key in DataStore. If the app password is revoked (HTTP 401) the app goes back to sign-in.
- **Sync:** WorkManager periodic check (and one on every open). Snapshot download goes to the cache directory, is verified, then moved into place atomically. The old snapshot stays usable until the switch. A schema with a newer **major** version is refused with "update the app".
- **No telemetry, no analytics, no crash-reporting service.** A "share log" button in Settings; logs contain counts and ids only.
- **Updates:** the Hikyaku pattern (GitHub releases API, no token). It cannot read a private repo, so the check ships **off** until the repo is public.
- **Tests:** unit tests for the snapshot reader, date grouping and the manifest logic using a generated fake snapshot; Compose screenshot tests with generated images; a macrobenchmark for scroll and cold start. No real photos anywhere.

## 4. Milestones and test builds

Each test build is a signed pre-release that Chris installs by sideloading, as with the spike. **Each pre-release needs his yes.**

| Step | Content | Test build |
| --- | --- | --- |
| 2a | Project, theme, sign-in, manifest check, snapshot download, "empty" Library showing counts | A: sign-in and sync work on the phone |
| 2b | Library grid (All), pinch density, floating title, fast scroll, Years and Months, Unknown date group, select mode with share | B: scroll feel on the real library |
| 2c | Viewer: transitions, zoom, swipe, filmstrip, details sheet, video | C: Chris uses it as his viewer |
| 2d | Collections subset with reorder, favourites, albums, Media types, Recent days; offline state; Settings; About | D |
| 2e | Release workflow, in-app update check (off), performance numbers, screenshots compared with the spec | the Phase 2 build |

**Done when** (plan): Chris uses it on the Xiaomi 15T for a few days as his photo viewer, the performance targets in 10.3 are measured and met or the gaps are listed, and screenshots of each screen are compared with the design spec.

## 5. Performance targets (plan 10.3) and how they are measured

| Target | How |
| --- | --- |
| Library scroll, 50,000 items: under 1% slow frames | Macrobenchmark on the Xiaomi 15T, release build, fake 50,000 snapshot with generated placeholders and a local test image server |
| Cold start to a usable Library under 1.5 s | Macrobenchmark startup, snapshot already on the phone |
| Viewer first frame under 150 ms | Trace from tap to first frame (placeholder or cached thumbnail) |
| Disk cache capped at 2 GB, adjustable | Instrumented test |

Search time (0.5 s) belongs to Phase 5.

## 6. Small indexer work that goes with Phase 2

- `METADATA_VERSION = 3`: read `Duration` for videos, then `--reread-metadata` for videos only. A test with hand-made data. Reading only; the snapshot minor version stays 1.0 because the column already exists.
- Finish the TIFF previews and retries (Chris decided: 12 Nov).
- Update `snapshot-schema.md` (videos' dates and duration are now verified).

## 7. Risks

| Risk | Plan |
| --- | --- |
| Preview endpoint slow for a cold folder | Measured in Phase 0 and in the first sessions (about 3 per second at 2 workers). The app requests only visible cells plus a small prefetch, with a concurrency cap, and shows ThumbHash meanwhile. |
| Shared-bounds transition janky on a 24k-cell grid | The viewer is an overlay with a fixed cell lookup; fall back to a simple fade if the numbers are bad, and say so in the report. |
| Heavy blur (the reference's glass look) is costly | Use semi-opaque surfaces and a light blur only on the bars; see the design spec. |
| A bad snapshot | The app verifies size and SHA-256 and keeps the previous one. |
| Phone not on Tailscale | Offline state; the snapshot and any cached thumbnails keep working. |

## 8. Needs Chris's yes

1. **The design** in `docs\design-spec.md` (including the iOS 26 style bar: a two-tab pill plus a separate round search button, as in his screenshots, instead of the plan's three-tab pill).
2. **Login Flow v2** creates a new app password in his Nextcloud (named for the app and the phone). That is a Nextcloud change; he approves it in the browser, and can revoke it later.
3. **A release signing keystore**, created by me, kept **outside Git** (`C:\BookahPC\bookahphotos\signing\`), and he backs it up. Losing it means the phone cannot update the app in place.
4. **Each test pre-release** (builds A to D and the Phase 2 build) on the private repo, one yes each time (or one yes that covers A to D, if he prefers).
5. **minSdk 31 and arm64 only.**
6. **The in-app updater ships but stays off** while the repo is private (as agreed before).
7. **The indexer duration change** in section 6 (read-only against Nextcloud).
