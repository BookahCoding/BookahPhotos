# Phase 4d report: snapshot 1.1, verification, daily job (indexer)

Date: 13 November 2026. Status: **built, tested, snapshot 1.1 published (generation 14 at the time of writing), the daily chain run end to end three times by hand. Waiting for Chris: the whole-library search check, the daily-job yes, and a look at the app with the new snapshot.**

## Update, 13 Nov (later): Nextcloud upgrade, publishing, and a flaw found and fixed

- **Nextcloud:** when the indexer first tried to publish, Nextcloud answered every file request with 503 (`needsDbUpgrade: true`). The cause was the **Memories app update to 11.1.2**, which asked for the database upgrade. With Chris's go-ahead I did it through Chrome in Home Assistant's own terminal: `occ status` (read only) to confirm, a Home Assistant backup of the Nextcloud and MariaDB apps first (314.5 MB, "Before Nextcloud upgrade (Nextcloud + MariaDB)", kept), then `occ upgrade` ("Update successful", maintenance mode off). Afterwards `status.php` is healthy and every folder lists. **One loose end, not touched:** the upgrade printed "the database schema does not match: missing table `oc_federated_invites`" (a federated-sharing table; `occ db:schema:check -v` shows details). Nothing in the library depends on it.
- **Publishing:** after the upgrade `run` published generation 10; the second run said "nothing changed". The indexer found 18 new and 40 removed files at that time (changes made in Nextcloud, not by the indexer).
- **Flaw found by running the daily chain twice:** the grouping was not stable: faces that joined a person moved that person's "centre", which let more faces in on the next run (202, then 116, then more), so a second run kept publishing new generations and groups could creep towards each other. **Fixed:** a person's centre now comes only from the faces that formed the group (and faces fixed by hand); faces that merely joined are decided again from scratch on every run. A test reproduces the old behaviour (a face 0.46 from the person got in on the second run) and passes with the fix. On the real library the first run after the fix took back 471 crept-in joins and added 44; the next runs changed nothing (0, 0, 0). The 40 groups Chris reviewed were not changed by this (their founders are unchanged); a backup of the database from before is `indexer.db.before-founders.bak` (private folder).
- **Second run changes nothing, on the real library:** the daily chain run three times in a row ended with "nothing changed since the last snapshot; not publishing" (the earlier generations were real changes: 3 new photos arrived).
- 131 tests pass.

## Chris's checks (13 Nov, later)

- **The app on the new snapshot:** Chris updated it and confirmed "library still works" (the 45 MB 1.1 snapshot is read by the app).
- **Search quality on the whole library** (the 38 Phase 0 questions, top 10 each, Chris's clicks): average accepted of 10 = **Greek 9.87, English 10.0, extra phrases 10.0, all 9.95** (Phase 0 sample: 8.67, 8.64, 9.44, 8.84). Weakest question: the Greek word for "scanned" (8 of 10). Caveat: a bigger library has more good matches per question, so the numbers are not a like-for-like comparison with the sample.
- **Daily job:** Chris said yes. The install command was blocked by Claude Code's permission check (a lasting change to his PC), so Chris runs it himself: on BookahPC in PowerShell `powershell -ExecutionPolicy Bypass -File D:\Projects\BookahPhotos\tools\install-daily-task.ps1` (remove again with `-Remove`).
- **Inbox:** the note was blocked the first time because of its content (a recipe for remote access), not the folder; a shorter version was written.

## Original status text (before the Nextcloud upgrade)

Built, tested, a real snapshot built and verified locally. Not yet published because Nextcloud needed its database upgrade.

## Implemented

- **Snapshot schema 1.1** (`schema.py`, `docs/snapshot-schema.md`): only additions to 1.0. Filled: `asset_vectors` (512 float16 per asset), `tags` (at most 5 per asset, thresholds agreed with Chris), new table `labels` (49, English and Greek names), `people` (at least 5 faces), `faces` (boxes only, no face descriptions), `memories` (new column `key`) and `memory_assets`. `indexed_level` 2 or 3. Photos hidden by an op are left out. `user_version` 1001. The current app (0.8.x) reads only the columns it needs and accepts a newer minor, so it keeps working (checked in the code and with the app's own query on the new file).
- **Fingerprint** (`build.rows_digest`) now covers vectors, faces, people, hidden photos, unwanted memories, label thresholds and the month, so "nothing changed" still means nothing changed.
- **Verification** (`verify.py`, command `verify-snapshot`): integrity check, version, all references, vector size and length, people counts, boxes inside the picture, no screenshots in memories, no unexpected table, faces table has no description column. **`run` now verifies every snapshot and refuses to publish one that fails.**
- **Search check** (`searchcheck.py`, command `search-check`): the 38 Phase 0 questions on the whole library, using the vectors inside the snapshot; private page; `--score` scores Chris's clicks like Phase 0.
- **Daily job** (`daily.py`, command `daily`, `tools/install-daily-task.ps1`, `docs/daily-job.md`): the whole chain for new photos with a Nextcloud health check, lock file, time budget and verified publishing. **Not installed.**

## Verified (how)

- **Tests:** 128 pass (11 new snapshot tests: complete and passing verification, tags exactly where expected, people and face rules, no face description anywhere in the file bytes, hidden photos left out, hidden people marked not dropped, fingerprint stable and sensitive, two builds identical, six kinds of deliberately broken snapshot all caught, old version rejected; 8 daily-job tests; 1 search-check test).
- **Real library, built locally (not published):** 24,661 assets, 24,539 vectors, 11,925 tags on 49 labels, 194 people, 30,543 faces, 493 memories (18 trips, 85 months, 29 person, 361 on-this-day), 45.1 MB, built in about 11 s, **verification: no problems**.
- **The daily job** against the real server today: it skipped by itself with the reason "Nextcloud needs its database upgrade" and touched nothing.
- **The Phase 4 "done when" list:** full library indexed (vectors for 24,539 of 24,661; 122 files have no usable picture: 80 videos and 42 JPEGs), a second run changes nothing (the fingerprint test and every earlier incremental run; the real publish-then-rerun check is open, see below), a rebuild plus ops replay gives the same touched people (test, 4b).

## Not verified

- **Publishing the new snapshot and the phone reading it.** Not published (Nextcloud problem below). The app's reading of 1.1 files was checked by its query and its code, not by running the app on a 1.1 file.
- **A real second run after publishing** (should say "nothing changed"): open until Nextcloud works.
- **Search quality on the whole library:** the page is ready; needs Chris's clicks.
- **Install and first scheduled run of the daily job**; the PC's behaviour with the job at a fixed time.
- The snapshot grew from 8.6 MB to 45.1 MB (every sync of a new generation downloads it all; no delta download yet).
- Tags are only a browsing aid; their quality was judged by Chris on a sample (4a), not on the whole library.

## Found today (not caused by the indexer)

Nextcloud 35.0.2.2 answers every file request with 503: `status.php` says `needsDbUpgrade: true`. The indexer only reads from the server. This usually means the Nextcloud software was updated and its database upgrade has not been run. **Not touched** (no changes to Nextcloud without Chris's yes). The phone app will also fail to list files until it is fixed.

## What Chris needs to do

1. Check Nextcloud on the OptiPlex (the upgrade step is his server, his decision; I can explain it).
2. Open `C:\BookahPC\bookahphotos\review\search-whole\index.html`, click the pictures that do not match each question, press "Save results", send me the file (the same 38 questions as in the first test).
3. Decide on the daily job (`docs/daily-job.md`).
