# BookahPhotos

A native Android photo app for your own **Nextcloud** photo library. It has the structure and feel of a modern phone photo app, in Greek and English, and everything smart about your photos is worked out **on your own PC and your own phone**: no cloud AI, no accounts, no tracking.

- **Library:** one fast grid of all your Nextcloud photos and videos (also the photos that only exist on the phone), years / months / all, hold-and-slide selection, share, delete to the Nextcloud trash.
- **Search by what is in the photo**, in Greek and English ("dog on the beach", "σκύλος στην παραλία"), on the phone, offline once the small search model is downloaded; category chips.
- **People:** faces grouped by an indexer on your PC; you name people, join groups, move or remove faces, hide people. Your corrections are sent back and survive a rebuild.
- **Places and a map** (OpenStreetMap), **Memories** (trips, months, on this day, a person over the years) with a simple full-screen playback, **Trips**.
- **Favourites** and **albums** (the same ones Nextcloud's own Photos app shows), **hidden photos** behind the phone's own unlock, **duplicates**, **selfies**, **motion photos**.
- Accessible (spoken names, large touch targets, large text) and private by design.

## How it works

```
Nextcloud (your photos)  <──  indexer (your PC, Python)  ──►  one SQLite "snapshot" in Nextcloud
                                                             │
                                          Android app  ◄─────┘   downloads the snapshot, shows previews from Nextcloud
```

- The **indexer** (`indexer/`) reads your library through WebDAV, reads dates and places, makes search vectors and face groups on your own hardware (a graphics card helps but is not required), and publishes a verified snapshot to a `BookahPhotos/` folder in your Nextcloud. It never changes your photos. Your corrections made in the app arrive as small op files and are applied by the indexer (`docs/ops-format.md`).
- The **app** (`android/`) reads the snapshot and the previews from your Nextcloud over HTTPS (made for Nextcloud behind Tailscale, but any HTTPS Nextcloud works). Search runs on the phone with an exact Kotlin port of the text tokenizer. The snapshot format is in `docs/snapshot-schema.md`.
- Nothing is sent anywhere except to **your** Nextcloud, to the OpenStreetMap tile server (map tiles, when you open the map; no login or photo data is sent) and, for updates, to GitHub's public releases page.

## Status

Version 1.0.0. Built and used by one person for one library (about 25,000 photos): it works there, but expect rough edges elsewhere. The indexer is Windows-first (a scheduled task), and every phase of the work has a report in `docs/`.

## Install the app

1. Download `BookahPhotos-<version>.apk` from the **Releases** page and install it (allow installs from your browser or file manager when Android asks). Check the signing certificate against the SHA-256 printed in the release notes if you like.
2. Open it, type your Nextcloud address, and approve the sign-in in the browser page that opens (Nextcloud "Login Flow": the app gets its own app password; your real password is never typed into the app).
3. The app finds the snapshot made by the indexer. Without an indexer there is no snapshot: set one up first (below).

Updates: from 1.0.0 on, *Settings > Updates* checks this repository's releases (only real releases, never test builds).

## Run the indexer (Windows)

See `indexer/` (Python 3.12 with [uv](https://docs.astral.sh/uv/)), `docs/daily-job.md` (what the daily job does and how to schedule it) and `docs/phase-1-design.md`. In short: create a Nextcloud app password, store it in Windows Credential Manager (`tools/store-nextcloud-password.ps1`), write `indexer-config.json` with your Nextcloud address and user, download the models (`docs/`), and run `uv run --extra content bookahphotos-indexer run`.

## Build

```
cd android
./gradlew :app:assembleDebug            # Android Studio's JDK 21; compileSdk 37, minSdk 31
./gradlew :core-data:testDebugUnitTest  # unit tests; screen tests need an emulator or a phone
cd ../indexer && uv run --extra content python -m pytest
```

Releases are built and signed with `tools/release-android.ps1` (the signing key is never in the repository).

## Credits and third-party data

- Map data © **OpenStreetMap contributors** (ODbL); map tiles from the OpenStreetMap Foundation's tile servers, used under their tile usage policy; rendered with **MapLibre Native**.
- Place names from **GeoNames** (Creative Commons Attribution 4.0).
- Search: a multilingual OpenCLIP model (XLM-RoBERTa ViT-B/32) run with **ONNX Runtime**; faces: **YuNet** and **SFace** through **OpenCV**. The models are **not** in this repository: they are downloaded separately and keep their own licences.
- Android libraries: Jetpack Compose and AndroidX, Coil, OkHttp, kotlinx.serialization and coroutines. Python: NumPy, Pillow, OpenCV, ONNX Runtime and others (see `indexer/pyproject.toml`).

## Privacy rules of this repository

No real photos, faces, names, locations or secrets are kept here; tests use generated data. The indexer's private database, models and your snapshot live outside the repository.

## Licence

MIT, see `LICENSE`. The models, map data and libraries named above keep their own licences.
