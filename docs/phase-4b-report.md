# Phase 4b report: faces, people and ops (indexer)

Date: 12 November 2026. Status: **built, tested, run on the whole library (read from the local cache); waiting for Chris's review of the people groups. Nothing was written to Nextcloud.**

## Implemented

- `faces.py` and `facesrun.py` (command `faces`): YuNet finds faces, SFace describes them (CPU, OpenCV, as in Phase 0). Pictures come from the local preview cache; Nextcloud is asked only for pictures missing from it (2 at a time). Screenshots (file-name flag) and TIFF scans are skipped, videos use their poster frame. Every photo is recorded, also those with no face, so a second run does nothing.
- `grouping.py` (command `group`): stable people. Clear faces (score 0.8 or more, 60 px or wider on a 1280 px side) form new people by density clustering (cosine eps 0.3, at least 5 faces); every usable face then joins the nearest person only when it is close (distance under 0.35) and clearly closer than to the second person (gap 0.05); otherwise it stays unassigned ("Who is this?" in Phase 5). Existing people are never renumbered or re-clustered; manual choices are never moved; input order is fixed so results repeat. `--regroup` forgets the automatic grouping (refused when manual choices exist).
- `ops.py` and `docs/ops-format.md`: the corrections format (name, hide, merge, move face, not a person, cover, hide photo, unwanted memory) with op ids, anchors, overlap matching and an id swap so that touched people keep their ids after a rebuild. Reading op files from `BookahPhotos/ops/` is built (`group --ops`, `--replay-ops`); moving applied files only with `--move-ops`.
- `facereview.py` (command `people-review`): the private review page.
- Fix found on the way: the cached 1024 px copies made from originals (500 files) were sideways and too small for faces; fixed and re-fetched.
- New private tables: `faces`, `faces_done`, `persons`, `ops_seen`, `hidden_files`, `unwanted_memories`. Face descriptions stay private; the snapshot will only get boxes and person ids (4d).

## Verified (how)

- **Same faces as Phase 0:** on the same input (80 sample photos, 1280 px) the new code gives cosine 1.0000 and the same 137 usable faces. On 1024 px previews (what the cache holds) the same 137 faces stay usable, but their descriptions differ a little (cosine mean 0.97, lowest 0.75).
- **Whole library:** 21,591 photos read in 594 s: 45,571 faces found, 30,543 usable; no network errors.
- **Tests (88 pass, 20 new, made-up descriptions only, never real faces):** people found without mixing, a second run changes nothing, new faces join the right person and strangers stay unassigned, a face between two people stays unassigned, unclear faces cannot form a person, every op type, applying twice changes nothing, merge keeps old ids working, skipped ops are reported, manual faces never move, **rebuild then replay** (delete the database, group again with other numbering, replay the ops: the named person has the same id, name and faces; the merge, the moved face and the rejected face come back exactly), op files on a fake server (applied, moved, a skipped file stays, no `ops/` folder is ever created, archived ops replayed).
- **Chaining check:** with the Phase 0 setting (eps 0.4) one group swallowed 75% of all faces. A sweep over eps, group size and face quality led to eps 0.3 with clear faces only; now 199 people, the biggest three about 2,300 faces each, the biggest holds 18% of grouped faces.

## Not verified

- **Whether the groups are right.** That is Chris's review. The numbers (eps 0.3, 0.35, gap 0.05, clear-face limits) are first guesses tuned only by group sizes, not by checking faces.
- Coverage is cautious on purpose: 13,488 of 30,543 usable faces are in a person, 17,055 are unassigned. It may be loosened after the review.
- The ops have never been written by the real app (Phase 5) or read from the real Nextcloud (the `ops/` folder does not exist yet); only a fake server was used.
- Children changing with age: the same child may be two people (the merge op fixes it).
- Nothing yet shows people in the app or snapshot (4d and Phase 5).

## Chris's review (13 Nov 2026) and what was done

- He looked at the 40 biggest groups (18 faces each): **no wrong face was marked**, 37 groups got a name, 3 he could not name. No names are recorded in this repository or in notes; they live only in the private database and in `C:\BookahPC\bookahphotos\ops-local\people-review-20261113.jsonl` (replayed with `group --ops-file` after a rebuild).
- Three people were split into 2 to 3 groups each (the same name was typed on several groups): merged with 4 `merge_people` ops. Result: 195 people; 41 ops applied (37 names, 4 merges), nothing skipped.
- Coverage check: 16,319 usable faces are unassigned; their median distance to the nearest person is 0.55, so most belong to people with fewer than 5 clear photos. Loosening the join distance to 0.40 would add about 1,000 to 1,500 faces; not done (a wrong person is worse than none). It is a setting Chris can ask for.
- Not reviewed: the other 159 smaller groups and the unassigned faces.

## What Chris needed to do (done)

Open on BookahPC: `C:\BookahPC\bookahphotos\review\people\index.html` (private, real faces). It shows the 40 biggest of 199 groups with 18 random faces each. Click every face that is a different person (it turns red), type a short name if you like, press "Save results" and send me the downloaded file (it holds names: it stays out of Git and my notes). Or tell me in words: "group 3 is mixed", "groups 5 and 9 are the same person".

## Next

After the review: tune the grouping, then 4c (memories and trips), then 4d (snapshot 1.1, verification, quality review).
