# Phase 5 proposal: smart features in the app

Status: **proposal only; nothing built. Starts only with Chris's yes.** Written 13 November 2026 from plan section 12, the Phase 0 phone spike and the Phase 4 results.

## What Phase 5 delivers (plan)

People (list, person page, naming, merge, move a face, "not a person", hide, cover, ops written and uploaded), Search (suggestions, chips, content search on the phone, combined ranking), Places (map and place pages), Memories (cards and a simple full-screen playback), the complete Collections page. **Done when** (plan): Chris names his family once and the names survive two indexer runs and a rebuild; his 30 test questions give the Phase 0 quality on the phone; everything works with BookahPC asleep.

## What is already there (facts)

- The 1.1 snapshot has everything the app needs offline: vectors (512 float16), tags and labels, people, faces (boxes), memories with covers and keys, places. The app already downloads and reads the snapshot.
- Phone spike (Xiaomi 15T): the shrunk text model (158 MB) loads in 0.6 s; a question takes 113 ms; a search over 50,000 vectors 191 ms (total about 0.3 s, target 0.5 s); same answers as the PC. **Still missing: an on-device tokenizer** (the spike used pre-tokenised questions).
- The indexer already reads, applies and replays ops (`ops-format.md`); the app side (writing them) does not exist.

## Proposed steps (each ends with a report and waits for a yes)

| Step | Content | Check |
| --- | --- | --- |
| 5a | **Search on the phone:** the XLM-R tokenizer in Kotlin (checked token for token against the PC tokenizer on all 38 questions and 1,000 random phrases), the text model delivered to the phone, the search box with suggestions and the 49 category chips, combined ranking (content score plus place and date words) | (a) pipeline exactness: the phone's token ids equal the PC's exactly and its question vector has cosine about 0.999 with the same model run on the PC; (b) quality: the phone's shrunk model on the whole library is judged by Chris on the photos that are new in its top 10 (it overlaps the full model by about 8 of 10, not 9: it is int8 and the library is large); a search in under 0.5 s |
| 5b | **People:** People tab and person page, from the snapshot; "Who is this?" for unassigned faces; no writing yet | numbers match the snapshot; the biggest people open in under 0.5 s |
| 5c | **Ops from the app:** naming, merge, move a face, not a person, hide, cover, hide photo, unwanted memory; written to `BookahPhotos/ops/<device>/` and applied by the daily job | a name typed on the phone survives two daily runs and a rebuild |
| 5d | **Places and memories:** place pages and a map, memory cards, full-screen playback with Greek and English titles | each kind of memory opens and plays; works offline |
| 5e | **Collections** complete (reorder, hide, utilities) and the end-to-end acceptance run | plan's done-when list |

## Needs Chris's yes or answers

1. Phase 5 itself, and starting with 5a (search), which is the feature he will use most.
2. **The text model on the phone is 158 MB.** Ship it inside the app (about 340 MB installed APK) or download it once from his Nextcloud (`BookahPhotos/models/`, new folder, written once by the indexer). I propose the download, so test builds stay small.
3. **Writing ops from the app** into `BookahPhotos/ops/` (the app writes only there; the indexer moves applied files to `ops/applied/` only after his explicit yes, `docs/ops-format.md`).
4. **Map tiles** (plan section 14): which provider, if any, for the Places map (no cloud service is used today). Options: OpenStreetMap tiles over the internet (leaks only the area looked at), or a place list without a map in the first version.
5. Whether to keep the repository private (the in-app update check needs it public; not needed for Phase 5).

## Risks

- The Kotlin tokenizer must match the Python one exactly (Unigram with a normaliser); a mismatch silently worsens Greek search. Mitigation: token-for-token tests before anything else.
- People quality depends on SFace (weaker than buffalo_l); mistakes are fixed through ops, and Chris reviews each step.
- The 45 MB snapshot downloads in full per generation; if it is slow on mobile data, a delta download is a later optimisation.

## Estimate

About the size of Phase 2: 5a and 5b first (2 to 3 days of building and test builds), 5c to 5e after that, with Chris testing each build on the Xiaomi as before. Test pre-releases follow his standing permission; the repo stays private.
