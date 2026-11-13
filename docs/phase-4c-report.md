# Phase 4c report: memories and trips (indexer)

Date: 13 November 2026. Status: **built and tested; run on the real library; waiting for Chris's review of the home place and the memories found. Nothing is published.**

## Implemented

- `memories.py`, `memoryreview.py` and the command `bookahphotos-indexer memories [--home lat,lon] [--auto-home]`: rules in code, no model, titles in English and Greek, a stable `key` per memory (so the op `unwanted_memory` keeps one from ever coming back).
  - **Home:** the densest 0.1 degree cell of the days with photos in the last two years (at night if at least 20 nights are known). Chris can correct it with `--home` (kept in the private database, not in Git).
  - **Trips:** days with a location more than 50 km from home; gaps of up to 2 missing days are bridged; at least 2 days and 15 photos (photos without a location inside those days count). Title: the most populous place within 25 km of the trip's main place, plus month and year ("Athens, Jul 2016" / Greek name and short Greek month).
  - **Month in review:** a month with at least 60 photos (the month that is still going on is skipped).
  - **On this day:** a calendar day with at least 8 photos from at least 2 different years within one day either side. The app will show the one for today's date and hide it when all its photos are from the current year.
  - **A person over the years:** a named, visible person with at least 40 photos over at least 3 years. Unnamed people have no memory (no title to give).
  - **Pictures and cover:** up to 40 (trips 60) per memory, the best of each day first, then the second best of each day and so on, in time order; quality = sharpness, a clear face, size. Screenshots and photos hidden by an op are never used; a memory marked unwanted is dropped.
- Places now carry their population (GeoNames) for the trip titles.

## Verified (how)

- **Tests (102 pass, 13 new, generated photos only):** home from where most days are spent rather than where trips are; a trip with its two-language title and cover; one day or under 15 photos is not a trip; a 2-day gap is bridged and a 3-day gap splits trips; trip named after the big place nearby; months need 60 photos and the current month is skipped; on-this-day needs 2 years and covers the neighbouring days; person memory needs a name, 40 photos, 3 years; screenshots, hidden photos and unwanted memories left out; picking spreads over days and prefers quality; a corrected home place is used.
- **On the real library:** 18 trips (dates and titles read through; all plausible, including 10-day and 24-day stays), 85 months, 29 people over the years, 361 on-this-day days (photos per day between 8 and 4,531). 

## Not verified

- **Whether the memories are the right ones.** That is Chris's review of the page. The thresholds (50 km, 15 photos, 60 photos, 8 photos, 40 photos) are first proposals.
- Whether the home place is really home (shown to Chris; the coordinates are deliberately not written in this repository).
- Trip titles name the biggest town within 25 km, which is sometimes not the place a person would say (for example a town on the mainland for an island trip, or a neighbouring town for a ski resort).
- Long stays away (two to four weeks) are counted as trips; they may be work or family stays rather than trips.
- Memory covers were chosen by sharpness and faces, not by anyone looking at them.
- Not yet in the snapshot (4d); the schema needs a `key` column in `memories` (additive, schema 1.1).

## What Chris needs to do

On BookahPC open `C:\BookahPC\bookahphotos\review\memories\index.html` (private, real photos). Check the **home place** at the top, then the trips, months and people. Tell me: wrong home place, trips that are not trips (or missing), titles you dislike, rules too strict or too loose.

## Next

After the review: tune the rules, then 4d: snapshot 1.1 (vectors, tags, faces, people, memories), the verification script, search-quality check on the whole library, and the daily job proposal.
