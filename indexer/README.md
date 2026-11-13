# BookahPhotos indexer (Phase 1: metadata only)

Lists the photo roots in Nextcloud, finds new, changed and removed files, reads dates and places, makes tiny placeholders, and publishes an index snapshot (`docs/snapshot-schema.md`). Reads photos only through Nextcloud (previews and the Memories EXIF call); never writes into the photo folders.

## Run (BookahPC, PowerShell as Bookah, in `D:\Projects\BookahPhotos\indexer`)

```
uv sync
.venv\Scripts\python.exe -I -X utf8 -m bookahphotos_indexer.cli run
```

Useful options: `--roots BookahPhotos/test-root` (the generated-images test folder), `--credential BookahPhotos-nextcloud` (the Phase 0 test password), `--publish-dir`, `--state-name`, `--skip-previews`, `--max-previews N`, `--minutes N` (stop making placeholders after N minutes; the next run continues), `--no-publish`, `--accept-deletions`.

`fake-snapshot --out file.db --count 50000` writes a fully synthetic snapshot for app development.

## Configuration

Optional JSON at `C:\BookahPC\bookahphotos\indexer-config.json` (outside the repo, no secrets). Keys and defaults are in `src/bookahphotos_indexer/config.py`. The app password is in Windows Credential Manager under `BookahPhotos-indexer` (store it with `tools\store-nextcloud-password.ps1 BookahPhotos-indexer`).

## Safety

- Writes only under `BookahPhotos/` (the client refuses any other path). `Family` is never listed.
- A failed or timed-out listing stops the run; nothing is treated as deleted. If more than 5% of known photos disappear the run is blocked and nothing is published until `--accept-deletions`.
- Placeholders: at most 2 requests at a time, resumable, large TIFFs last.
- Private state, logs (counts and ids only) and temporary files are in `C:\BookahPC\bookahphotos\`.

## Tests

`.venv\Scripts\python.exe -I -X utf8 -m pytest -q` (mocked Nextcloud; generated images only).
