# Phase 5c report: corrections from the app (build 1 of 2)

Date: 13 November 2026. Status: **build 1 is built and tested on an emulator, on the PC and against the real Nextcloud server's upload behaviour; test build 0.11.0 published as a pre-release. Not yet used on the Xiaomi.** Chris said yes on 13 Nov to the app writing into `BookahPhotos/ops/` and to `--move-ops`, and asked to be able to name unnamed people.

## Implemented (build 1)

- **Name a person, including the unnamed ones:** the person page (tap a face) has a header with the face, name, number of photos and the buttons **Name** (or **Rename**) and **Hide**. While typing, names that exist already are offered.
- **"Same person?":** naming somebody with a name that already exists (ignoring case and accents) asks "Same person as X?". Yes joins the two (they keep X's name), no keeps both with the same name. Most of the 161 unnamed groups are probably splits of people already named, so this is the quick way to join them.
- **Hide a person / Hidden people:** Hide asks first; hidden people are listed under **People > Hidden people** with a Show button.
- **Corrections show at once and are kept safe:** each one is saved on the phone first, shown on top of the snapshot, uploaded as one new file `BookahPhotos/ops/<device>/<UTC time>-<random>.jsonl` (device `xiaomi-15t`), and dropped from the phone when a new snapshot lists it as applied. Offline: they wait and are sent later. Settings shows **People: corrections waiting for BookahPC / not sent yet**, and signing out warns when some were not sent.
- **Indexer:** snapshot schema **1.2** adds the table `applied_ops` (ids of applied corrections); a correction that changes nothing else still produces a new generation; `verify.py` knows the table. **Generation 16 (schema 1.2) is published.**
- Release script refuses `-Publish` with uncommitted changes (after the 0.10.0 slip).

## Verified (how)

- **The contract between the phone's op file and the indexer:** the box convention (snapshot x, y, w, h to op x1, y1, x2, y2) was checked on a copy of the real private database: for all 194 people the anchors built from snapshot 15 resolve to the right person (also with 1, 3, 6 and 8 anchors), and with a wrong id the anchors alone still find the person (60 of 60). One golden op file (`indexer/tests/fixtures/ops-golden.jsonl`, made-up names including Greek and quotes) is produced byte for byte by the Kotlin writer (its test) and parsed and applied by the indexer (its test); the two copies of the file are checked to be identical.
- **The upload against the real Nextcloud:** folder creation 201 then 405 when it exists; a new file with "only if it does not exist" 201; the same name again 412 (so nothing can be overwritten). The test folder was deleted again. Unit tests with a mock server cover the paths (only below `ops/<device>/`, never `applied/`), a name clash retry, a failing server and a wrong password.
- **Overlay:** a name, hide/unhide, a merge, a cover, moving a face and "not a person" show as the indexer would apply them; **an old correction lands on the same person when the ids were swapped by a later regroup** (it is found by its anchors, not by id); an op for a person who is not in the snapshot is ignored.
- **A rebuild (tested on a COPY of the private database, never on the live one):** I forgot every person, grouping and correction, grouped the faces from scratch (199 people), and replayed the stored op file: **all 33 named people got their names back** (found by their anchors from the published snapshot), and a second replay changed nothing. This is the same code path as `group --ops --replay-ops` from `ops/applied/`; the untouched groups are not promised to be identical.
- **Tests:** 14 JVM tests of ops, queue, overlay and upload; 8 UI tests of naming, same-person, hiding, showing again; 6 UI tests of People from 5b; 3 device tests of reading people on a real snapshot; 144 indexer tests; **the older suites (24 library, 11 viewer, 12 search, 8 collections, 4 app) were rerun and pass.**

## Not verified

- Everything on Chris's phone: typing a name, the keyboard, how fast the corrections upload, the Greek strings.
- The whole loop on the real system: a name typed on the phone reaching BookahPC, being applied, and coming back in a published snapshot (the next step, below, does this with him).
- `--move-ops` has not run yet (no op files exist). After the first real batch I run `group --ops --move-ops` by hand, check that only op files moved and only into `applied/<device>/`, and only then switch the daily job.
- "Two daily runs": the daily job applies ops at 14:00; not yet exercised with an app file.

## Build 2 (not built yet): faces page per person for **move a face**, **not a person** and **choose a cover**, and **hide a photo**. `unwanted_memory` waits for 5d (memories).

## What Chris needs to do

1. Install 0.11.0 and let it sync (Settings > Refresh) so it has snapshot 16.
2. Collections > People > open an unnamed person > **Name**. Try one name that exists already (the "Same person?" question), then one new name. Settings should show "Corrections waiting for BookahPC".
3. Tell me when you have done 2 or 3 of them. I then run the indexer step on BookahPC (the daily job does it at 14:00 anyway), check the result, move the op files for the first time, and you refresh the phone: the "waiting" count must go to 0 and the names stay.

---

# Build 2 (test build 0.12.0): faces page, and the button rename

Date: 13 November 2026. Built after Chris's first real batch (32 corrections: 24 joins and 8 names) was applied on BookahPC and archived (backup first; the 32 files moved only into `ops/applied/25069ptebg/`, checked over WebDAV; `--move-ops` is now in the daily job; snapshot generation 17 published with 73 applied ids).

## Implemented
- **Faces page** (person page > **Faces**): every face of the person cut out, clearest first (a switch shows the least clear first, where the mistakes are). Tap a face: **Use as the cover**, **Not this person** (the face goes to nobody), **Belongs to someone else** (a search list of people), **Not a person (statue, picture)**. Each change shows at once, is sent like the names, and an **Undo** bar stays for 8 seconds (undo sends the opposite correction: giving a face back also reverses "not a person", on both the phone and the indexer, both tested).
- The Settings button **Check again** is now **Refresh** (Greek: Ανανέωση).

## Verified
- 5 UI tests of the faces page (cover and its undo, not this person and undo, someone else, not a person and undo, sort order); overlay test that "not a person" followed by "give it back" restores the face; Python test that the indexer does the same; earlier suites unchanged (collections 27 UI tests in all).

## Not verified
- On the phone: how fast the faces load for a big person (each face is a cached preview; only the faces on screen are loaded), whether the sort order helps find mistakes.

## Moved: **hide a photo**
Not built, on purpose. A hidden photo is left out of the snapshot completely, so the app could not list it again to un-hide it: Chris would have no way back. It needs a small schema addition (1.3: a list of hidden photos with their small placeholders) and a "Hidden photos" page. Proposal: do it as the next small step, with the way back, if Chris wants it. `unwanted_memory` waits for 5d.
