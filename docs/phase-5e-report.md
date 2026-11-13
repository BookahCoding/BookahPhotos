# Phase 5e report: Collections complete and the acceptance run

Date: 13 November 2026. Test build **0.16.0** (pre-release).

## The Collections page now (plan section 6.4)

| Plan section | Status |
| --- | --- |
| Memories | done (row, playback, "Don't show" with Undo) |
| People | done (named first; unnamed groups at the end, **Name** on their page) |
| Places | done (**Map** tile first, then places) |
| **Trips** | **new in 0.16.0**: the 18 trips as their own row and page (same cards and playback as Memories) |
| Recent days, Albums, On this phone | done earlier (Phase 2 and 3) |
| Media types | Videos, Screenshots, Panoramas, RAW. **Selfies and Motion photos are not shown**: the indexer never marks a photo as either (0 of 24,617), so those rows would always be empty. Needs a small indexer step first (proposal, not built). |
| Utilities | **Hidden** (behind the phone's unlock) is new; Favourites, Recently added and Not backed up are in the Pinned section. **Duplicates is not built**: the snapshot has no duplicate groups (the column is empty); needs an indexer step first. |
| Reorder, hide, collapse | done in Phase 2; the new sections (Memories, People, Places, Trips, Utilities) take part; a saved order from before keeps its order and gets the new ones at the end (tested) |

## The acceptance run against the plan's "done when" (Phase 5)

1. **"Chris names his family once and the names survive two indexer runs and a rebuild"**
   - **Two runs:** I ran the real daily job twice on 13 Nov (after his corrections): the first published generation 19, the second found nothing changed. **All 40 named people were identical** (same ids, same names, same face counts) before and after. Earlier the first scheduled run (14:00) and three more publishes had run too.
   - **A rebuild, tested on a COPY of the private database (never the live one):** every person, grouping and correction forgotten, faces grouped from scratch (199 people), his review file and the archived app corrections replayed: **all 40 named people got their names back**. **Not everything came back:** 3 of the 32 phone corrections could not be placed again (their groups no longer exist in the new grouping, so 173 people instead of 171 remain: three groups that Chris had joined stay separate). I improved how a person is recognised from its anchor faces (two agreeing anchors are enough when others belong to nobody; tested), which took it from 4 to 3. Untouched groups were never promised to be identical.
2. **"His 30 test questions give the Phase 0 quality on the phone":** on his 38 questions the phone's search model scored **9.97 of 10** in his own judgement of the 68 new photos (the full model 9.95; Phase 0 on the 1,537-photo sample was 8.84). He tried search on his Xiaomi and said it works great. A single question takes about 24 ms on the emulator; on the phone Chris did not report any slowness (not timed there).
3. **"Everything in this phase works with BookahPC asleep":** the app never contacts BookahPC. I checked every address in the app's code: Nextcloud (his own server), `tile.openstreetmap.org` for the map, and GitHub's release page (switched off while the repo is private). Names and other corrections are **applied** by BookahPC, so they show on the phone at once but are confirmed only after the next daily run; with BookahPC off they wait (Settings > People shows how many). **Not tested:** switching BookahPC off while using the phone.

## Phase 5 summary of what Chris has: search on the phone (5a), People with naming, merging and faces (5b, 5c), memories with playback, places, the map and hidden photos (5d), and the complete Collections page (5e).

## Not verified / open
- The whole Phase 5 on the phone is judged by Chris. He reported 5a to 5d as good; **hidden photos and 0.16.0 are not yet tried by him**: hide a photo and see it in Hidden after the next daily run, in particular.
- Speed numbers of Phase 5 screens on the phone were not measured with a stopwatch.
- Selfies, Motion photos and Duplicates (above).
- 3 corrections lost in a full rebuild (above).

## Needs Chris
Try 0.16.0 (Trips, Hidden). Then **accept Phase 5** or tell me what to fix, and decide on **Phase 6** (favourite, albums, translation review, tablet layout, baseline profile; plan section 12), which needs his yes to start.
