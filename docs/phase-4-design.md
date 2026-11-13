# Phase 4 design: the indexer learns what is in the photos

Status: **proposal, nothing built yet.** Written 12 Nov 2026 after Phase 2 and 3 were accepted. It follows plan sections 8.2, 8.3, 9 and 12 (Phase 4) and the Phase 0 measurements. It ends with the decisions Chris has to make. Phase 5 (the app features that use all this: People, Search, Places map, Memories playback) starts only after Phase 4 is accepted.

## 1. What Phase 4 delivers

The indexer on BookahPC adds four things to every photo and video of the library and publishes them in the snapshot (schema 1.1):

1. **Content vectors:** a 512-number description of what is in the picture, made by the search model chosen in Phase 0 (XLM-RoBERTa ViT-B/32). The phone compares them with a typed question in Greek or English.
2. **Faces and people:** faces are found, described, and grouped into people. People get stable ids. Names and corrections come from Chris through "ops" and are never lost.
3. **Tags:** a short list of everyday categories per photo (beach, food, document, pet, snow...) in Greek and English, from the same model, for the category chips in Search.
4. **Memories and trips:** automatic sets (on this day, trips away from home, a month in review, a person over time) with Greek and English titles.

The app does not change in Phase 4 except that the snapshot gets bigger. Phase 5 uses the data.

**Done when** (plan): the full library is indexed; a second run changes nothing; a full rebuild followed by an ops replay gives the same people as before; the quality numbers from Phase 0 hold on the full library sample.

## 2. What is known (so nothing here is a guess)

| Fact | Source |
| --- | --- |
| 24,661 files in the index; about 95% are online-only on the PC, so the indexer reads **previews through Nextcloud**, not the sync folder | Phase 0, Phase 1 |
| Preview speed: 1024 px median 0.19 s (warm), cold 256 px median 0.74 s; TIFF cold 5.5 s; about 3 previews per second with 2 requests at a time was the real pace in Phase 1 | Phase 0, Phase 1 |
| About 550 files have no Nextcloud preview (HEIC variants, JPEG named .heic, RAW named .jpg): the original must be read instead | Phase 2 findings |
| Search model: XLM-R ViT-B/32 laion5b, 512 dimensions, 302 images per second on the GPU; Chris's test: 8.8 of 10 | Phase 0 |
| Face model: YuNet + SFace (Chris's choice, permissive licence). It was clearly worse than buffalo_l in the test (19 wrong faces in 252 checked, against 0) | Phase 0 |
| The GPU is shared (llama.cpp router on port 8089, Ollama, OCR, Whisper): ONNX Runtime DirectML, adapter chosen by name; load a model only when idle and unload after | plan 9.2, Phase 0 |
| The PC sleeps about 23:00 and wakes about 06:00; the indexer never wakes it | plan 9.2 |
| Disk: C: has 393 GB free; the Phase 0 models are on disk (4.8 GB) | measured today |

## 3. How it works

### 3.1 One picture fetch feeds everything

For each photo the indexer fetches **one 1024 px preview** from Nextcloud and uses it three times: shrunk to 224 px for the content vector, as is for face detection, and for a quality score (sharpness, screenshot or document look) used to pick covers. This keeps the load on the OptiPlex at one request per photo.

- **Videos:** the preview Nextcloud makes of a video is a poster frame; it gets a vector and faces like a photo. (80 videos have no preview; they are skipped for content.)
- **Files without a preview (about 550):** the original is downloaded once, decoded on the PC, used, and discarded. Camera RAW files named `.jpg` cannot be decoded and stay without a vector.
- **TIFFs (2,748, probably scans):** done last and on the 256 px preview that already exists (cold 1024 px previews take 5.5 s each, about four hours for all). Vector and tags only, no faces. Chris decides (section 7, D2).
- **A local cache of the previews** (private state folder `C:\BookahPC\bookahphotos\cache\`, about 3 to 4 GB for 24,000 photos, outside Nextcloud and Git): re-runs for tuning faces or tags do not fetch again. It can be deleted at any time and is rebuilt on demand (D4).

### 3.2 Content vectors and tags

- The image half of the search model runs on the RX 7600 in batches of 64; vectors are normalised and stored as 16-bit numbers in `asset_vectors` (about 25 MB for 24,000 photos).
- **Tags:** a fixed list of about 60 everyday labels, each with a Greek and an English name (beach, sea, mountain, snow, food, cake, birthday, cat, dog, car, document, screenshot, receipt, drawing, map, sunset, night, indoors, party, playground...). Each label's text vector is computed once on the PC from the search model's text half. A photo gets a tag when its score passes a threshold tuned on Chris's sample. Chris sees the label list and a page of examples per label before it is used (D7).
- The text half of the model stays on the PC (1.1 GB). The phone needs only the trimmed copy (Phase 5).

### 3.3 Faces and people (YuNet + SFace)

- YuNet finds faces; SFace describes each as 128 numbers. Only usable faces count (score 0.6 or more, 40 px or wider at 1280 px), as in the Phase 0 test. Face vectors stay in the indexer's private database; the snapshot carries only boxes and person ids.
- **Grouping:** density clustering by cosine distance (eps 0.40, the value that worked in Phase 0 for these models), at least 3 faces to make a group at first. **Stable person ids:** a person keeps its id for good; later runs add new faces to the nearest existing person if close enough, otherwise to a pool that is clustered again. Groups are never renumbered by a re-run.
- **Being careful with the weaker model:** faces that are close to two people, or far from every person, stay unassigned instead of being forced into a group (a wrong "this is mum" is worse than a missing one). Phase 5 shows unassigned faces as "Who is this?" suggestions.
- **Who counts:** a person appears in the app only after a minimum number of faces (default 5, D6). Screenshots, documents and the game-art folders can be excluded from faces by their flags and folder names (D1).

### 3.4 Ops: Chris's corrections

- The app (Phase 5) writes small JSON lines to `BookahPhotos/ops/<device>/` in Nextcloud. Phase 4 builds the **indexer side** and writes the exact format into `docs/ops-format.md`: name a person, merge two people, move a face to another person, "this is not a person", hide or unhide a person, choose a cover, hide or unhide a photo, mark a memory unwanted.
- At the start of every run the indexer reads the ops in time order, applies them to its private database, records them for good, and moves the files to `BookahPhotos/ops/applied/` (a move inside `BookahPhotos/`, which the indexer is already allowed to write to). Applying the same op twice changes nothing.
- **Manual choices always win:** a face Chris moved to a person is marked as fixed and automatic grouping never moves it again. A full rebuild (delete the private database, index again) replays all recorded ops and must give the same people (this is one of the "done when" tests).
- Until the app can write ops (Phase 5), tests use hand-made op files; nothing in Chris's Nextcloud is touched except the `BookahPhotos` folder.

### 3.5 Memories and trips (rules in code, no model)

| Kind | Rule (first proposal, tuned on the real library) |
| --- | --- |
| On this day | the same month and day in earlier years, at least 8 photos in total, shown in the days around it |
| Trip | consecutive days with photos more than 50 km from home (home = the place where most nights' photos were taken in the last two years); gaps of up to two days are bridged; at least 2 days and 15 photos; title "Zakynthos, Aug 2024" (the biggest place name) |
| Month in review | a month with at least 60 photos |
| Person over time | a person with at least 40 photos over at least 3 different years |

Covers are picked by quality (a sharp photo with a face, not a screenshot). Titles are made in Greek and English. A memory marked unwanted by an op is never made again. Home is shown to Chris and can be corrected (D8).

### 3.6 GPU and load rules (from the plan, checked before every batch)

- Before loading a model: `ollama ps` empty, the llama.cpp router on port 8089 idle, no Whisper or OCR job running. Between batches: check again; if another job takes the card, the indexer unloads and waits (it does not fight).
- Models are loaded only while the indexer works and unloaded at the end. Batches are small and every result is saved at once, so a stop loses nothing.
- Nextcloud: 2 requests at a time as in Phase 1 (a third only with Chris's yes, D5). Never more than the OptiPlex can take: the run slows down if a request takes more than 5 seconds.
- Never wakes or reconfigures the PC; sessions are limited with `--minutes` so they end before it sleeps.

### 3.7 The snapshot (schema 1.1)

Schema minor 1 only **adds** to 1.0, so the app keeps working. Filled in this phase: `asset_vectors`, `faces`, `people`, `tags`, `memories`, `memory_assets`, `meta` (model names, home place). `docs/snapshot-schema.md` is updated. The snapshot grows from 8.6 MB to about 40 MB.

## 4. Milestones

| Step | Content | Check |
| --- | --- | --- |
| 4a | Models loaded by name on the GPU with the good-neighbour checks; the 1024 px preview cache; vectors and tags on Chris's 1,537-photo sample first, then the library | vectors reproduce the Phase 0 ranking on the sample; the label pages reviewed by Chris |
| 4b | Faces, grouping with stable ids, ops reading and replay | review page of the biggest groups (names kept private); a rebuild gives the same people; hand-made ops are applied once |
| 4c | Memories and trips | a page listing the memories found, reviewed by Chris |
| 4d | Snapshot 1.1 published; verification script; first full run (several sessions); quality review | second run changes nothing; Chris's 30 questions on the whole library; people review |

Each step ends with a short report (implemented, verified and how, not verified) and waits for his yes, as always. Test data: generated images and hand-made vectors; the face tests use openly licensed sample portraits and made-up embeddings, never Chris's photos. Review pages with real photos stay in the private state folder.

## 5. Estimates

- **First full run:** about 24,000 previews at about 3 per second is about 2.5 hours of Nextcloud work, plus about 1.5 hours for TIFFs and the 550 originals, over 3 to 4 sessions of about 90 minutes; the GPU part is small. Chris can use the phone and Nextcloud meanwhile (the load is the same as the first index sessions).
- **Later runs:** only new and changed files; minutes.
- **Disk:** about 4 GB cache (deletable), about 100 MB private database.

## 6. Risks

| Risk | What we do |
| --- | --- |
| YuNet + SFace mixes or splits people (19 wrong of 252 in the test) | conservative grouping, unassigned instead of forced, a quality threshold, and the correction ops; Chris reviews the biggest groups before the app shows people. buffalo_l stays available as a benchmark (not for shipping, licence) |
| Children change with age | the same child may become two groups; the merge op fixes it and is remembered |
| Tags are wrong for odd photos (scans, game art) | thresholds tuned on his sample; tags are only a way to browse, search by content does not depend on them |
| The OptiPlex slows down | 2 requests at a time, slows down by itself, resumable, Chris can stop it any time |
| The GPU is needed for something else | the indexer checks before each batch and gives way |

## 7. Decisions for Chris

| # | Question | My proposal |
| --- | --- | --- |
| D1 | Which photos get faces? | all photos and videos' poster frames except screenshots, and except folders you name (for example the game art folders such as Races, Monsters, Maps). Tell me the folder names |
| D2 | TIFFs (2,748 probably scans): | vector and tags from the existing 256 px preview, no faces, done last |
| D3 | Videos: | poster frame gets a vector and faces; no frame-by-frame work |
| D4 | A local cache of previews (about 4 GB on C:, deletable)? | yes |
| D5 | Nextcloud requests at a time: | 2 (as now); 3 only if you say the OptiPlex copes |
| D6 | A person appears in the app after how many faces? | 5 |
| D7 | The tag label list | I write about 60 labels in Greek and English and show you a page of examples for each before use |
| D8 | Memories: the rules and thresholds in section 3.5, and "home" | as proposed; I show you the home place and the memories found before they are published |
| D9 | When to run? | daytime sessions of about 90 minutes while the PC is on; tell me if a game or another job needs the GPU |

## 8. Needs Chris's yes

1. This design (and the answers to D1 to D9, or "as proposed").
2. A local preview cache of about 4 GB on C:.
3. Writing into `BookahPhotos/ops/` (moving applied op files to `applied/`): inside the folder the indexer already writes to, but new.
4. The first full run, once 4a to 4c have been reviewed.
