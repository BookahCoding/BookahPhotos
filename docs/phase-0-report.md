# BookahPhotos Phase 0 report

Date: 2026-11-11. Status: **complete, waiting for Chris's review.** Everything below was measured on BookahPC unless stated; the phone numbers are from the Xiaomi 15T (section 5).

## 1. Summary

- The plan holds. Nothing found so far breaks it; five things in it should change (section 8).
- **Search model: XLM-RoBERTa ViT-B/32 (laion5b).** It scored 8.8 of 10 on Chris's questions (Greek 8.7, English 8.6), against 7.5 (nllb-clip) and 7.2 (SigLIP 2 base). SigLIP 2 is weak in Greek single words (5.0).
- **Face model: Chris chose YuNet + SFace** (permissive licence, so the repo can go public). buffalo_l was clearly better in the test (no wrong faces in 252 checked, against 19 for YuNet + SFace), so Phase 4 must tune YuNet + SFace and the correction tools (move a face to another person, merge, "not this person") matter more.
- **The library is mostly not on the PC.** 363.6 of 382.2 GB (23,489 of 24,539 files) are online-only placeholders in `D:\Nextcloud\Photos`. The indexer must fetch previews and EXIF from Nextcloud, not read the sync folder.
- **The phone text model is 157 MB** (target was 150) after trimming the vocabulary to Greek and plain English letters. Same answers as the full model to 0.993 cosine. **On the Xiaomi 15T:** a question takes 113 ms, a search over 50,000 vectors 191 ms, so about 0.3 s in total (target 0.5 s); loading takes 0.6 s and about 180 MB of memory.
- **The GPU path works:** ONNX Runtime DirectML on the RX 7600, chosen by name. The first full index is limited by Nextcloud, not by the GPU.

## 2. Nextcloud and the library (read only)

| Fact | Value |
| --- | --- |
| Nextcloud | 35.0.2 (plan said 35.0.1) |
| Memories app | 10.2.0 |
| Recognize | not in the capabilities list (not installed, as far as the API shows) |
| User id | `<user>` |
| Favourites / albums | 2 favourites, 4 albums (all readable) |
| `Photos` folder | 24,539 files, 382.2 GB |
| `InstantUpload` | 143 files, about 1 GB. `Family`: 15 files, not touched |
| Types | JPG 16,363 (86.5 GB), HEIC 2,899 (6.2 GB), TIFF 2,737 (102.5 GB), MOV 1,240 (140.6 GB), MP4 741 (43.6 GB), JPEG 421, PNG 83, RAW ORF 35, other small |
| On this PC | 1,050 files, 18.6 GB present; 23,489 files, 363.6 GB online-only |
| Date range | not read from EXIF yet (file times are sync times). The sample is 2024 and 2025 only |

Working requests (user = `<user>`, app password in Windows Credential Manager target `BookahPhotos-nextcloud`; Windows PowerShell 5.1 needs HttpClient for WebDAV verbs, see `tools\nc.ps1`):

- List with ids: `PROPFIND /remote.php/dav/files/<user>/Photos` (`Depth: 1`), properties `oc:fileid`, `d:getetag`, `d:getcontentlength`, `d:getcontenttype`, `oc:favorite`, `nc:has-preview`.
- Preview: `GET /core/preview?fileId=ID&x=256&y=256&a=true&mode=cover` (also `x=1024`). The Memories one also works: `GET /index.php/apps/memories/api/image/preview/ID?x=256&y=256&a=true`.
- EXIF without the original: `GET /index.php/apps/memories/api/image/info/ID` returns `datetaken`, `exif`, `w`, `h`, `dayid`, `etag`, `mimetype` in about 0.04 s. **This is how the indexer should read dates and GPS.**
- Favourites: `REPORT /remote.php/dav/files/<user>` with `oc:filter-files` and `<oc:favorite>1</oc:favorite>`.
- Albums: `PROPFIND /remote.php/dav/photos/<user>/albums` (`Depth: 1`); members by `PROPFIND` on an album path.
- Original: `GET /remote.php/dav/files/<user>/Photos/<path>`.

## 3. Speed of getting photos from Nextcloud (small sample: 20 JPEG/HEIC, 5 TIFF, 5 video)

| Request | Result |
| --- | --- |
| Full original JPEG/HEIC (about 5 MB each) | 6.3 MB/s on average, median 0.59 s |
| Preview 256 px, cold | median 0.74 s, 90th percentile 1.5 s |
| Preview 1024 px | median 0.19 s (one outlier of 21 s) |
| Memories image info (date, EXIF) | median 0.04 s |
| Preview of a 38 MB TIFF, cold | median 5.5 s |
| Preview of a video, cold | median 0.2 s (4 of 5 produced one) |

These are single sequential requests. Parallel load on the OptiPlex (8 GB) is **not measured** and needs Chris's yes before it is tried.

## 4. Models on the RX 7600 (DirectML, adapter chosen by name; Python 3.12 + onnxruntime-directml 1.24.4)

Image encoders over 1,537 photos (decoding was the bottleneck: 7 photos/s on one thread, 25/s on 8 threads):

| Model | GPU speed | Vector size | Image file | Text file |
| --- | --- | --- | --- | --- |
| XLM-R ViT-B/32 | 302 photos/s | 512 | 352 MB | 1,113 MB |
| SigLIP 2 base (224) | 89 photos/s | 768 | 372 MB | 283 MB int8 |
| nllb-clip-base-siglip (384) | 39 photos/s | 768 | 373 MB | 1,662 MB |

Quality: Chris judged the top 10 for 29 of his questions (15 Greek, 14 English, single words) and 9 phrases of mine, across the three models, on his 1,537 photos (2024 and 2025 only). Average accepted of 10:

| Model | Greek | English | Phrases | All 38 |
| --- | --- | --- | --- | --- |
| **XLM-R ViT-B/32** | **8.67** | 8.64 | 9.44 | **8.84** |
| nllb-clip | 6.87 | 7.07 | 9.00 | 7.45 |
| SigLIP 2 (int8 text) | 5.00 | 8.79 | 8.22 | 7.16 |

Notes: the zeros were real (nothing matched). Screenshots and scans scored low; the sample probably has few of them. SigLIP 2's int8 text model may have lost some accuracy (the fp16 file would not load in ONNX Runtime on the CPU, the fp32 file was not downloaded). One person, one sample.

Faces (usable faces: score 0.6 or more, 40 px or wider):

| Setup | Faces / photos | Speed | Groups (3 or more) | Wrong faces in 252 checked |
| --- | --- | --- | --- | --- |
| **buffalo_l** (SCRFD + ArcFace R50, GPU) | 2,085 / 931 | 85 photos/s, 201 faces/s | 55 (1,217 of 1,479 grouped) | **0** |
| buffalo_s (GPU) | 1,793 / 862 | 140 photos/s | 42 | 2 |
| YuNet + SFace (CPU, permissive) | 1,935 / 903 | 176 photos/s | 45 (982 grouped) | 19 (one mixed group; one person split in two) |

Limits: top 14 groups only, 18 random faces each; the largest group has about 550 faces; same child at different ages not tested.

Phone text model (spike\shrink_xlmr_text.py): vocabulary cut from 250,002 to 90,402 rows (Greek and plain ASCII letters), embedding table as per-row int8, MatMul weights int8. 158 MB, 14 ms per question on the PC. Against the full model: vector cosine 0.993 (worst 0.982), 8.6 of the top 10 photos identical on average (worst 6). The app needs the token id mapping file (`shrunk\old2new.npy`) and its own tokenizer (XLM-R Unigram; not yet built).

## 5. Phone spike (Xiaomi 15T)

Built: `android-spike` (plain Activity, onnxruntime-android 1.31.1), APK 195 MB, arm64 only, debug-signed, published as the pre-release `v0.0.1-spike` in the private repo. It measures file size, load time, cold and median query time, equality with the PC's answers, a 50,000-vector search and memory.

**Result (Xiaomi 15T, model "25069PTEBG", Android 16, 8 cores, screenshot from Chris, run at 15:35; one run, release not used, debug build):**

| Measure | Result | Plan target |
| --- | --- | --- |
| Model file | 157 MB | under 150 MB (just over) |
| Copy from the app package (first run only) | 145 ms | - |
| Model load | 598 ms | - |
| Memory (PSS): start / after load / at the end | 100 MB / 279 MB / 410 MB | - |
| First (cold) query | 112 ms | under 500 ms |
| Median query over 38 questions (slowest) | 113 ms (134 ms) | under 500 ms |
| Same answer as the PC (trimmed model on the PC) | vector similarity mean 0.9993, worst 0.9935; top 10 photos identical 9.8 of 10 | - |
| Search over 50,000 vectors, plain Kotlin loop, float32 | 191 ms (median of 5) | - |
| Question plus search | about 0.3 s | under 0.5 s |

Reading: the plan's search-on-the-phone design works and meets the 0.5 s target with room to spare. The vector memory (102 MB as float32) can drop by half with 16-bit storage and the search can be sped up with a better loop; both are Phase 5 work. The model costs about 180 MB of memory while loaded, so it should be loaded when the Search tab opens and released afterwards. This was one debug-build run and was not repeated, and it does not include the on-device tokenizer, which does not exist yet.

## 6. Estimates for the real library (24,539 files)

- **Snapshot size: about 40 MB** (plan guessed 60 to 120 MB). 512 fp16 values per photo is 1 KB, so 25 MB of vectors, plus about 6 MB of rows, 2 to 3 MB of tags, and about 1 to 2 MB of faces (1.36 faces per photo in the sample means about 33,000 faces). Not built; to be checked in Phase 1 with the fake 50,000-item snapshot.
- **First full index.** Compute on BookahPC is small: GPU encoding and faces together are well under an hour. What dominates is Nextcloud.
  - Previews route (about 100 KB per photo): about 2.5 GB in total. Cold preview time of about 0.7 s for a JPEG and 5.5 s for a TIFF, sequentially, is about 4 hours for 21,800 non-TIFF files and about 4 hours for 2,737 TIFFs (8 hours serial). With 4 parallel requests, about 2 to 3 hours, **if the OptiPlex copes; not tried.**
  - Originals route: 363.6 GB at 6.3 MB/s is about 16 hours on one stream. Much worse, and the video files alone are 184 GB.
  - Recommendation: previews plus Memories info for photos, a key frame from the video preview, and low priority or exclusion for the 2,737 large TIFFs (102 GB, probably scans) until Chris decides.
- **Re-runs** only touch changed etags, so they are small.

## 7. Build, signing and updates (Kurobane and Hikyaku for Android)

- Gradle 9.8, AGP 9.4.1, Kotlin built in, compileSdk 37 in Kurobane (the local SDK has 36; the spike uses 36; `core-ktx` 1.19.1 needs 37). Release workflow: tag `vX.Y.Z` on main, signed APK attached as a fixed asset name, `release-signing-cert.sha256` committed, keystore secrets in repo secrets, `-PappVersion` and `-PreleasesRepo` from the tag.
- The in-app updater calls the unauthenticated `api.github.com/repos/<repo>/releases/latest`. **That does not work for a private repo.** Chris agreed: private while developing, public before the updater is turned on.

## 8. What in the plan should change

1. Section 4: Nextcloud is 35.0.2; the library is mostly online-only, so the indexer reads previews and Memories EXIF over the network, not `D:\Nextcloud` (section 9.1, step 4 and the Phase 1 design).
2. Section 9.3: search model = XLM-R ViT-B/32 laion5b (512 dimensions), not the SigLIP 2 base first choice. Face model = YuNet + SFace (Chris's decision, permissive licence; buffalo_l stays the quality benchmark). Python 3.12, not 3.14.
3. Section 10.1: the phone text model is the trimmed 158 MB build, with an on-device tokenizer (Kotlin Unigram port or a library) and the id mapping file; add that to Phase 5.
4. Section 8.1: snapshot estimate 40 MB, not 60 to 120 MB.
5. Section 12: add a decision about the 2,737 TIFFs and the 1,981 videos (index or skip), and plan the first index around Nextcloud preview load. Phase 6 release work: the updater needs a public repo.

## 9. Open decisions (plan section 14) with what is known now

| #   | Decision                | What we now know                                                                                                                                                                |
| --- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1   | Photo roots             | `Photos` (24,539) and `InstantUpload` (143 files). `Family` not touched. TIFF scans: index with low priority. **Decided by Chris, 11 Nov.** Videos: still to decide (default: key frame, after photos)                                                                    |
| 2   | Face model              | buffalo_l best by far. Licence: non-commercial research. YuNet + SFace is permissive but clearly worse. Let's use YuNet + SFace (can I manually move photos to another person?) **Decided by Chris, 11 Nov: YuNet + SFace.** Answer: yes, moving a face to another person, merging and "not this person" are part of the plan (ops, section 8.3) and are never undone by re-indexing; they matter more with this model, which mixed one group and split one person in the test. Phase 4 will tune it (stricter quality filter, better alignment, grouping threshold) and re-check it on your sample. |
| 3   | Map tiles               | not tested; default stays online tiles with an off switch                                                                                                                       |
| 4   | Delete from Phase 6     | no change                                                                                                                                                                       |
| 5   | Repo                    | private now. Public later needs the face licence question settled (models are not in the repo)                                                                                  |
| 6   | Minimum Android         | Android 12 still fine; MovinkPad version not checked                                                                                                                            |
| 7   | iPhone Photos reference | screenshots not supplied yet. Put them in the Nextcloud folder `Obsidian/Projects/01 Projects/BookahPhotos/design-reference` (it has a README; on the PC it is `D:\Nextcloud\Obsidian\Projects\01 Projects\BookahPhotos\design-reference`). They stay out of the Git repo.                                                                                       |
| 8   | Snapshot location       | `BookahPhotos/index/` This location is approved. **Approved by Chris, 11 Nov.** The folder itself is not created yet; I will confirm before the first write into Nextcloud.                                                                                                                                |
| 9   | kanban id               | Claude picks one. **Chosen: `P-0041`** (highest in use was P-0040; P-0041 appears nowhere else), stage `in-progress`.                                                                                                                                                                |

## 10. Implemented, verified, not verified

**Implemented:** read-only Nextcloud probes, the sync-folder inventory, GPU adapter selection by name, three image encoders and their text sides, the faces pipeline with three setups, the phone-sized text model, the review pages, the phone spike APK and its release.

**Verified (how):** WebDAV, previews, favourites, albums and Memories info by real requests; model speed by timing on the RX 7600; search and face quality by Chris's clicks; trimmed model against the full model by cosine and top-10 overlap on his vectors; the phone numbers in section 5 from Chris's screenshot of the test app on the Xiaomi 15T (one run).

**Not verified:** the app beyond the search spike (no tokenizer, no library, no viewer on the phone); the phone result is a single run of a debug build; parallel load on the OptiPlex; cold previews for the whole library; real date range; the full-library index time (estimated only); the SigLIP 2 fp16 text model; faces for children at different ages; videos' frames; HEIC motion photos; Nextcloud sync or phone upload (not checked on Chris's behalf).
