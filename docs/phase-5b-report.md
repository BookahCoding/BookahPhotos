# Phase 5b report: People (read only)

Date: 13 November 2026. Status: **built and tested on an emulator and on the PC; test build 0.10.0 published as a pre-release for Chris to try on his Xiaomi 15T.**

## Implemented

- **People row in Collections** (a new section, second in the default order): round faces with names under them, named people first (most photos first), then unnamed groups. The section header opens the **People page** (three faces per row). The section disappears when the snapshot has no people. Chris's saved Collections order is kept; People is added at its end (he can move it up with Reorder).
- **Person page:** tapping a person opens their photos, newest first, in the usual photo grid; the viewer opens through exactly those photos. An unnamed person shows "Not named" (Greek: "Χωρίς όνομα").
- **Faces on the phone:** the face circle is cut out of the same Nextcloud preview the viewer uses, around the face box from the snapshot (1.6 times the box, moved inside the picture), at a preview size chosen from the face size (256 to 1024). Nothing new is stored in the snapshot.
- **Data:** `PeopleReader` reads `people` and `faces` of the snapshot (hidden people left out, faces of photos that are not in the snapshot ignored, a photo with two faces of one person counts once, cover = the chosen cover face or else the clearest face). Photos are found in the library by file id, so a photo merged with its phone copy still shows.
- English and Greek strings.

## Verified (how)

- **Geometry, on the real library (PC):** for all 194 people I fetched the same Nextcloud preview the app uses and cut the cover face by the snapshot box: faces are centred, 0 pictures were turned the wrong way (preview proportions checked against the snapshot's own width and height), median face about 240 px wide in a 1024 px preview. Contact sheet (private, faces): `C:\BookahPC\bookahphotos\review\people-geometry\sheet.png`.
- **Numbers match the snapshot:** on the emulator, the real snapshot (generation 15) read by the real code gives 194 people, every person's name and number of photos equal to a SQL count of distinct photos (33 named, first in the list); reading all people takes 170 ms there.
- **Generated-data tests:** hidden people, photos missing from the snapshot, one photo with two faces, cover choice, blank names, a snapshot without people tables (device); sort order and merged-photo lookup (JVM); crop maths (JVM); the face really is cut out around its box and the picture's edge is at the rim (device, a made-up picture); People row, page, empty case, tapping (6 UI tests); an old saved Collections order gets People at the end and loses nothing.
- Older suites: Collections 8/8 still pass; the release script runs the JVM tests.

## Not verified

- Everything on Chris's phone: how the real faces look in the row, how fast they appear over mobile data or Wi-Fi (each face is one preview download the first time, then cached), page opening time for the biggest person (2,418 photos; the lookup is plain id lookups, not timed on the phone), the Greek strings.
- Faces whose cover photo is a video or has no preview: the circle stays empty (6 of the first 60 covers were videos); not tested on the phone. If this looks bad I will pick the person's next-best face.

## Moved to 5c, on purpose

**"Who is this?" for unassigned faces.** The snapshot has no face descriptions (by design), so the phone cannot suggest who a face is, and a bare list of unknown faces cannot be acted on without writing. It becomes useful together with naming in 5c. If you want real suggestions, they need indexer-side data: a separate proposal.

## What Chris needs to do

Install 0.10.0, open Collections, look at the People row and the People page, open a few people, and tell me: speed, wrong or odd faces, blank circles, anything else. Then say yes to 5c (writing names and corrections from the app: needs your approval for the app to write into `BookahPhotos/ops/` and for `--move-ops`).
