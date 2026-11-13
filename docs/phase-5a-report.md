# Phase 5a report: search on the phone

Date: 13 November 2026. Status: **accepted by Chris on 13 November 2026 after trying it on his Xiaomi 15T ("works great"; icon redrawn and accepted in 0.9.1). The phone's shrunk model scored 9.97 of 10 on his judgement of the 68 new photos (Greek 9.93, English 10.0), the full model 9.95.** Below: the report as written before his tests.

## Implemented

- **Tokenizer on the phone** (`core-search/PhoneTokenizer.kt`), a line-by-line Kotlin port of a Python reference (`indexer/.../phone_tokenizer.py`) that reproduces the PC's Hugging Face tokenizer exactly: grapheme-cluster normalizer with the sentencepiece charsmap, white-space split, Unigram lattice with the same tie rules, fused unknown characters, `<s> … </s>` cut to 77 and padded. The file the phone downloads is built by the indexer (`text_tokenizer.bin`, 5 MB).
- **The search model reaches the phone:** `bookahphotos-indexer publish-models` uploaded the small text model (158 MB, int8) and the tokenizer file to `BookahPhotos/models/` (temporary names, size check, `manifest.json` last). The app downloads them **only when asked** (a card on the search page and Settings), checks size and SHA-256, keeps them in its own storage, and refuses to search when the snapshot's vectors come from another model.
- **Search page** (round Search button, in both bars): a field, 49 category chips (Greek or English names by phone language), recent searches, three example phrases, the one-time download card with progress, results as the usual photo grid; tapping opens the viewer through exactly those results. First Back clears the results, second leaves.
- **Engine:** vectors of the snapshot decoded once into memory (about 50 MB, in the background), a plain dot-product search, the best matches kept (a nonsense tail is cut at 0.08 below the best score, at least 24 shown).
- **Settings:** a "Search" section: model status and size, Download, Delete.
- **App icon:** an original adaptive icon (two stacked frames with a sun and hills on the accent blue, plus a themed single-colour layer): `docs/app-icon-preview.png`.
- English and Greek strings for everything new (Greek to be reviewed by Chris in Phase 6).

## Verified (how)

- **Tokenizer, exact:** the Kotlin tokenizer reproduces the PC tokenizer's ids on 3,036 committed test cases and 60,036 fuzzed strings (Greek with and without accents, decomposed accents, spaces of every kind, emoji, unknown characters, over-long text, empty). The expected ids come from the Hugging Face library, not from our own code. The Python reference matches on about 100,000 strings.
- **On-device (emulator), real model:** token ids identical; the question vector has a cosine of 1.0000 with the PC's for 14 of 16 phrases, 0.9951 and 0.9982 for two (int8 rounding on another CPU; Phase 0 measured a minimum of 0.9935 on the phone); about 24 ms per question; Greek and English questions find the matching made-up photo in 17 of 17 cases.
- **Tests:** 16 JVM tests in `core-search` (tokenizer, float16 like numpy, vector search against a brute-force sort, model manifest/download/damaged download never replacing a good model, engine refuses mixed models, sync against a mock Nextcloud) plus the 75 existing; 4 device tests (real SQLite snapshot read, old snapshot without vectors, real model, cross-language) and 12 UI tests of the search page (download offer and progress, reasons in words, submit, recents, examples, categories in Greek, results and opening through the results, back behaviour); the older suites still pass (24 library, 8 collections, 11 viewer). 142 indexer tests.
- **Release build:** shrunk APK builds (39 MB, mostly ONNX Runtime), ONNX Runtime classes are kept by R8 (checked in the mapping).

## Not verified

- **Everything on Chris's phone:** the download from his Nextcloud (158 MB), loading time of the tokenizer and model on the Xiaomi, search speed there, the page layout with real photos, the Greek keyboard flow, the icon in his launcher and in themed mode.
- **Quality of the phone's shrunk model on the real library:** its top 10 overlaps the full model's by 8.2 of 10 on average (lowest 5) on the 38 questions. The 68 photos that are new in its top 10 are waiting for Chris's judgement (`search-check --small`); until then the phone's answer quality is not known.
- **Combined ranking** (place and date words as boosts) and typing suggestions: not built; the examples and recents are the first version of suggestions.
- The release build was not run on a device (only built and its R8 mapping inspected).

## What Chris needs to do

1. Install 0.9.0 from the pre-release, open the round Search button, tap **Download** (158 MB, Wi-Fi advised), then try a few questions in Greek and English. Tell me: speed, anything odd, whether the icon looks right.
2. On BookahPC open `C:\BookahPC\bookahphotos\review\search-small\index.html` (private page, 68 new photos), click the ones that do not match, save and send the file.

## Next

5b People tab and person page (read only), then 5c writing corrections from the app.
