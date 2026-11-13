# Phase 4a report: search vectors and tags (indexer)

Date: 12 November 2026. Status: **built and tested; run on 1,494 real photos; waiting for Chris's review of the label pages. The full library has not been run.**

## Implemented

- `gpu.py`: finds the RX 7600 **by name** (never by number) and checks before every batch that nobody else uses the card (Ollama has a model loaded, the card is more than 35% busy, Whisper or OCR runs). The llama.cpp router is always running and idle, so it is judged by how busy the card is. If the card is taken the indexer waits up to 10 minutes and then stops; it never takes the card by force.
- `content.py`: the search model's picture half and text half (XLM-R ViT-B/32), vectors of 512 numbers stored as 16-bit numbers; RAW files are read through the JPEG inside them.
- `contentrun.py` and the new command `bookahphotos-indexer content [--limit N] [--spread] [--minutes M]`: one 1024 px preview per photo (cached in `C:\BookahPC\bookahphotos\cache\`, deletable), 2 Nextcloud requests at a time (slows down when the server takes more than 5 s), the original is read only when Nextcloud has no preview, TIFFs (scans) and videos come last, every result is saved at once, a stop or an error loses nothing.
- `labels.py`: 62 tag labels with Greek and English names (beach, sea, mountain, snow, food, cat, dog, document, screenshot, game art ...), up to 5 tags per photo.
- `labelreview.py` and `bookahphotos-indexer labels-review`: a private review page with real photos (pictures that pass, and a few just below the threshold, per label).
- A new `content` table in the indexer's private database (vector, source, sharpness; a changed file is read again).

## Verified (how)

- **Same vectors as Phase 0:** 300 sample photos through the new code against the Phase 0 vectors: cosine 1.0000 (lowest 0.9999), 300 of 300. The shorter-side-then-centre-crop preparation is therefore the right one (squashing the picture gave 0.94).
- **Speed:** 284 pictures per second on the card; whole pipeline about 4 photos per second from Nextcloud (1,194 photos in 288 s).
- **Greek and English:** a quick check that "παραλία" and "beach" (0.89), "σκύλος" and "dog" (0.94), "έγγραφο" and "document" (0.79) give close vectors.
- **Real library run (read only):** 1,494 vectors made (1,194 spread evenly over the library, 300 from the first files), 35 Olympus RAW files only worked after the JPEG-inside fix, 6 photos have no usable picture, no network errors.
- **Tests:** 65 indexer tests pass (19 new: card by name and status, preparation, tags, cache, no-preview fallback, RAW, network trouble, resume, card in use, time limit, order, review page).

## Not verified

- **Tag quality.** The thresholds are a first guess (most sit at 0.19). Chris's review decides them. Hard labels (wedding, document, map, painting) tag almost nothing on this sample; it may be the sample or the threshold.
- The Phase 0 "8.8 of 10" search quality on the whole library (comes with 4d).
- Behaviour when Ollama or llama.cpp is really busy (the checks are tested with fake signals; the card was free during all runs).
- Videos and TIFFs have not been run yet (they come last in the full run).

## What Chris needs to do

On BookahPC open this file in a browser (private, real photos, nothing is uploaded):
`C:\BookahPC\bookahphotos\review\labels\index.html`
For each label say: good / too many wrong / too strict / drop it (or "all fine, tune yourself"). Also tell me the game-art folder names for D1 (faces are not made in those).

## Next

After the review: tune thresholds, run the whole library (about 1.5 to 2 hours in sessions, with `--minutes`), then 4b (faces, people, ops). Each needs his yes.
