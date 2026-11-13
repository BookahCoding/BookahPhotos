# The daily job (proposal: not installed)

Status: **built and tested, NOT installed.** Installing it changes Chris's PC (a Task Scheduler entry), so it needs his yes.

## What it does

`bookahphotos-indexer daily` does everything new photos need, in this order, with a time budget (default 60 minutes; each step gets a share):

1. **Check Nextcloud is healthy** (`status.php`: reachable, installed, not in maintenance, no pending database upgrade). If not, it does nothing and says why (not an error). This is what happened on 13 Nov 2026 when Nextcloud asked for a database upgrade.
2. **Scan, dates, placeholders** for new and changed files (`run --no-snapshot`).
3. **Search vectors** for the new photos (`content`): checks first that the graphics card is free (Ollama, Whisper, OCR, more than 35% busy) and gives way.
4. **Faces** for the new photos (`faces`, CPU).
5. **People and corrections** (`group --ops`): new faces join existing people when they are clearly closest to them; the op files in `BookahPhotos/ops/` are read and applied (first real batch moved and checked by hand on 13 Nov 2026: 32 op files, only into `ops/applied/25069ptebg/`; Chris said yes to `--move-ops` on 13 Nov 2026, the daily job then moves applied op files to `ops/applied/<device>/`).
6. **Snapshot:** builds it, **verifies it**, and publishes it only if something changed and the checks passed (`run --skip-previews`).

A quiet day takes a few minutes. It never wakes the PC (no wake-up, the PC sleeps about 23:00 to 06:00), never runs twice at once (lock file), and stops each step at its time limit, so nothing is lost when it is cut off.

## How it would be scheduled

Windows Task Scheduler, once a day at 14:00 (Chris can pick another time), only while he is logged on, only if the network is up, stopped after 2 hours at the latest. Install and remove with `tools\install-daily-task.ps1` (`-Remove` removes it). It does not touch anything else.

## What it does not do

- It does not move op files into `ops/applied/` (needs Chris's yes, `group --move-ops`), does not delete anything, never writes outside `BookahPhotos/`.
- New people only appear when there are at least 5 clear photos of them; unnamed people stay unnamed until the app (Phase 5) sends a name op.
- Memories are rebuilt from the rules every run (a finished month becomes a "month in review", trips appear after the second day away).

## Decisions for Chris

1. Install it? (yes / no / later)
2. Time of day (default 14:00) and budget (default 60 minutes). It uses the graphics card for a few minutes and 3 CPU threads for faces (a few minutes on a normal day); tell me if a game or another job needs the PC at a certain time.
