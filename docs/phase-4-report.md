# BookahPhotos Phase 4 report: the indexer learns what is in the photos

Date: 13 November 2026. Status: **built, run on the whole library, published as snapshot schema 1.1, checked by Chris on the phone and on the search questions. The daily job is installed. Waiting for Chris's acceptance and his yes to Phase 5.**

Detail reports: `phase-4a-report.md` (vectors and tags), `phase-4b-report.md` (faces, people, ops), `phase-4c-report.md` (memories and trips), `phase-4d-report.md` (snapshot 1.1, verification, daily job, Nextcloud upgrade). Contracts: `snapshot-schema.md` (1.1), `ops-format.md`, `daily-job.md`.

## Implemented

- **Search vectors** (XLM-RoBERTa ViT-B/32, 512 numbers) for 24,557 of the 24,639 files in the library at the time (82 have no usable picture), made on the RX 7600 by name with good-neighbour checks, from one cached 1024 px preview per photo (about 1.8 GB, deletable).
- **Tags:** 49 labels in Greek and English, thresholds agreed with Chris in two review rounds (13 labels were dropped because they stayed wrong).
- **Faces and people:** YuNet + SFace; 30,543 usable faces; 194 people with at least 5 faces; stable ids; automatic grouping that never moves a face Chris fixed by hand; 37 people named by Chris through ops; faces that only joined a person are decided again each run against fixed centres (a drift flaw was found and fixed).
- **Ops** (Chris's corrections): format written down, applied with overlap matching and anchor faces, replay after a rebuild tested.
- **Memories and trips:** 18 trips, 85 months, 29 people over the years, 361 "on this day", home place confirmed by Chris.
- **Snapshot 1.1** (45 MB) with a verification script; `run` refuses to publish a snapshot that fails its checks.
- **Daily job** (`daily`) installed in Task Scheduler (14:00, up to 60 minutes, never wakes the PC, skips when Nextcloud is unhealthy).

## Verified (how)

- **Phase 4 "done when":**
  - *Full library indexed:* 24,557 vectors, 30,543 faces, memories and tags in the published snapshot; 82 files have no vector (no usable picture: mostly videos without a poster frame and a few broken images).
  - *Re-running changes nothing:* the daily chain run three times by hand ended with "nothing changed since the last snapshot; not publishing"; tests cover the fingerprint.
  - *A rebuild plus ops replay gives the same people:* a test deletes the database, groups again with other numbering, replays ops: named person (id, name, faces), merges, moved and rejected faces come back exactly. Untouched automatic groups are **not** promised to be identical.
  - *Quality of Phase 0 holds on the full library:* Chris's 38 questions, top 10 each, accepted 9.95 of 10 (Greek 9.87, English 10.0, extras 10.0) against 8.84 on the sample.
- 131 indexer tests; real snapshot verified (no problems); the app (0.8.x) reads the 1.1 snapshot (Chris, on the phone: "library still works").
- Nextcloud upgrade (after the Memories app update asked for it) done with a backup first; healthy afterwards.

## Not verified

- Whether the people groups outside the 40 biggest are right; 16,432 usable faces are deliberately unassigned (most belong to people with fewer than 5 clear photos).
- Tags were judged on a sample of 1,494 photos, not the whole library.
- The first scheduled run of the daily job (today 14:00) and the behaviour of the PC with it over several days.
- A rebuild of the real database from scratch (tested with generated data only; the real rebuild would take about 2 hours of Nextcloud work plus a faces run).
- `oc_federated_invites` (a Nextcloud federated-sharing table) was reported missing after the upgrade; not touched, nothing here depends on it.
- The snapshot is 45 MB (was 8.6 MB); the app downloads all of it per generation.

## Open decisions and loose ends

- The Home Lab note "Local models on BookahPC" (a register in the Home Lab vault) should list the photo models (XLM-R ViT-B/32 text and image halves, YuNet, SFace): not edited, it is outside this project's vault. Chris decides.
- Moving applied op files into `BookahPhotos/ops/applied/` has never been done and needs his yes before the app writes ops (Phase 5).
- buffalo_l remains a benchmark only (licence); the repo stays private.
- Back up the signing key folder `C:\BookahPC\bookahphotos\signing\` outside BookahPC (his job).

## Next

Phase 5 proposal: `phase-5-proposal.md`. It starts only with Chris's yes.
