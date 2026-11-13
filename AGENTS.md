# AGENTS.md: BookahPhotos

Rules for any coding agent working in this repo. The owner is Chris, a product owner, not a programmer: explain in plain words, and for every command he must run say which machine, which app and which file.

## What this is
A native Android photo app (BookahPhotos) for a private Nextcloud library, plus an indexer that runs on BookahPC and publishes an index snapshot the phone downloads. The plan and decisions live in the Obsidian Projects vault (`01 Projects/BookahPhotos/BookahPhotos Plan.md`); the current state is in `HANDOFF.md`; Phase 0 results are in `docs/phase-0-report.md`; Phase 1 design is in `docs/phase-1-design.md`.

## Hard rules
- Photo folders in Nextcloud and `D:\Nextcloud\Photos` are **read only**. Never write, rename, move or delete there. The indexer only ever writes under `BookahPhotos/` in Chris's Nextcloud (`index/`, `test-root/`, later `ops/`).
- No secrets in files, notes, logs or commits. The Nextcloud app password lives in Windows Credential Manager (targets `BookahPhotos-nextcloud` for tests, `BookahPhotos-indexer` for the indexer).
- No real photos, faces, names, file names of people or coordinates in the repo, tests, logs or issues. Tests use generated images and hand-made metadata.
- Do not index the `Family` folder or another person's files.
- No changes to Nextcloud, Home Assistant, MariaDB, Samba or Tailscale beyond what Chris approved. Do not load the OptiPlex with more than 1 to 2 concurrent requests.
- No cloud AI, telemetry or analytics.
- Push, merge, tag and release each need Chris's yes.
- Do not wake or reconfigure the PC. Check the GPU is free (`ollama ps`, port 8089, Whisper) before using it and unload your own models after.

## How to work
- Verify first, read only; propose, then wait for a yes before changing anything outside this folder. One phase at a time; stop and report on a failed check.
- Use the Write and Edit tools for files, not shell heredocs. Windows PowerShell 5.1 for scripts (no `&&`, no `??`); Python scripts run as `python -I -X utf8`.
- Reports separate **implemented**, **verified (how)** and **not verified**. Do not claim a phone check you did not see.
- Keep `HANDOFF.md` and the Obsidian notes current.

## Layout
- `indexer/` the indexer (uv project, Python 3.12). `docs/` plans, schema, reports. `android/`, `design/` for later phases. `spike/` and `tools/` Phase 0 code and helper scripts (kept as reference).
