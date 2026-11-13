# Design spec (Phase 2 screens)

Status: **draft for Chris's approval.** Written 11 Nov 2026 from the 40 reference screenshots Chris saved in Nextcloud (`design-reference`, kept out of Git). This file describes screens in generic words only: no names, faces, places or photo descriptions from the screenshots. The look is our own: our icons, colours and strings; nothing copied from Apple.

## 0. How the sizes were measured

- The reference is a 390 pt wide phone (a screenshot of the info sheet is 1170 x 2532 px, at 3x). The uploaded copies are 946 px wide, so one point is about 2.43 px. Checked on a known element: the round back button measures 44 pt, as expected.
- Android values are in **dp** and are the same numbers (1 pt is treated as 1 dp). Measurements are good to about plus or minus 2 dp. The first build is compared with them and the numbers are corrected in this file.
- Motion timings cannot be measured from still images. They are proposals, tuned on the phone.
- Typeface: the system font (Roboto on the Xiaomi). Sizes below are in sp.

## 1. Tokens

| Token | Light | Dark |
| --- | --- | --- |
| Background | #FFFFFF | #0B0B0D |
| Surface (cards, sheet rows) | #F2F2F5 | #1C1C1F |
| Text primary / secondary | #111114 / #6B6B73 | #F5F5F7 / #A0A0A8 |
| Accent | #2F6FED | #5B8DF5 |
| Viewer background | #000000 in both themes (plan 6.1), controls on dark glass | |
| Floating bar fill | background at 85% opacity, 1 dp hairline, soft shadow | same, with a lighter hairline |

Corner radii: floating controls fully round; section cards 20 dp; large cards (Years, Months cover, Memories) 24 to 28 dp; sheet cards 24 dp; grid cells 0 dp.
Type scale: large title 34 sp bold; title 22 sp semibold; section title 22 sp bold; body 17 sp; secondary 15 sp; caption 13 sp; card label 17 sp semibold on a dark gradient.
Spacing unit 4 dp. Screen side margin 20 dp (sheets 16 dp). Hit targets at least 44 dp.

Dark theme: chosen by Claude, as agreed. The viewer is true black in both themes. Everything else follows the phone setting.

## 2. Shared pieces

| Piece | Spec | Same as reference | Different on Android |
| --- | --- | --- | --- |
| Bottom bar | A floating pill 192 x 60 dp with two tabs (Library, Collections), bottom margin 22 dp plus the navigation inset, left margin 20 dp; the selected tab has a darker inner pill. A separate round **Search** button 62 dp on the right. | Two-tab pill plus a separate round search button (as in his screenshots) | The plan said three destinations in one pill; this follows the reference. Search stays inactive ("coming soon") until Phase 5. Fill is a semi-opaque surface, not heavy blur |
| Library bottom bar | When the grid is shown: a round Library button (48 dp) at the left, a pill with **Years / Months / All** (about 210 x 47 dp) in the middle, the round Search button at the right. Tapping the round button returns to the normal two-tab bar | Same behaviour | Same |
| Header | Large title at the top left, 34 sp bold, with a subtitle line (15 sp) under it. Round 44 dp buttons at the right ("filter" and a text pill "Select") | Same layout | No sync line (Chris's decision) |
| Back button | Round 44 dp at the top left on pushed screens | Same | Respects predictive back |
| Section header | Title 22 sp bold, a chevron when it opens a page, a round 32 dp collapse button at the right | Same | Reorder and hide are in v1 (see Collections) |

## 3. Screens

### 3.1 Library, All

- Grid of square cells, **5 columns** on the phone at the default density, 1 dp gaps, edge to edge (about 78 dp cells). Pinch steps: 3, 5, 9, 16 columns (plan).
- Large title "Library"; subtitle shows the date range on screen (for example "3 - 10 Nov 2026"); at the very end of the list it shows the item count.
- Top buttons: filter (sort and filters, a menu) and "Select". The header floats over the grid with a gradient scrim so the grid scrolls under it.
- Cells: ThumbHash first, thumbnail sharpens in place. Video: small video glyph and length at the bottom left (length when the snapshot has it). Favourite: small heart at the bottom left. RAW without preview: a flat tile with a "RAW" label.
- Newest at the bottom; the screen opens scrolled to the newest.
- **Unknown date** group at the top (oldest end) with a header row "Unknown date".
- Fast-scroll handle at the right edge with a bubble showing month and year (plan 6.3).
- Same as reference: single continuous grid, header with range, floating bars. Different: the filter menu has our own items (Sort: added / taken; Show: all items, favourites, videos, screenshots); "Edited", "Shared with you", "My recordings" are not offered.

### 3.2 Library, Months

- Heading per month ("Aug 2026", 22 sp bold) with a mosaic: one **large cover** (350 x 233 dp, radius 24) plus a row of small tiles (about 112 dp wide, 6 dp gaps), each with the day number at the top left in white. Months with few days use two equal tiles. Newest at the bottom; opens at the newest month.
- Tap a tile: goes to All, scrolled to that day. Cover chosen by a simple rule (the first photo of the day with the best resolution); smarter choice comes with the index in Phase 4.

### 3.3 Library, Years

- One large card per year (350 x 262 dp, radius 24, 20 dp apart) with the year in white, 24 sp bold, 20 dp from the top left over a dark gradient. Tap goes to Months at that year.

### 3.4 Select mode

- "Select" turns into a round close button; the subtitle line is replaced by nothing (no sync line). Selected cells show a round check at the bottom right.
- Bottom bar: round Share button (48 dp), a centre pill "Selected (n)", and a round button that is **disabled** until Phase 6 (delete is not offered; the bar shows only Share and the count in Phase 2).
- Long-press starts selection, dragging across cells selects a range (plan).

### 3.5 Viewer

- Opens by growing from the tapped cell (shared bounds), 300 ms; swipe down shrinks it back to the same cell, with the background fading. Left and right swipes move through the whole library order. Double-tap and pinch zoom; tap hides the controls (the system bars too).
- Top: round back button (44 dp), a centre pill (about 160 x 44 dp) with the date on one line and the time on the second line, a round "more" button.
- A small badge row under the top bar when relevant: a file type badge (for example "JPEG + RAW", "HEIC", "Video"). Our own wording.
- Filmstrip: thin strip of neighbour thumbnails above the bottom bar, the current one larger.
- Bottom bar: round Share (48 dp), a pill (about 163 x 48 dp) with **Favourite** (state only in Phase 2: filled or outline, not tappable until Phase 6) and **Info**, and no Edit and no Delete in Phase 2.
- Video plays in place with a scrubber; paused when swiped away.
- Black background in both themes; controls use dark glass.
- Same as reference: layout of bars and pill; Different: no Edit; no Delete (Phase 6); no Live-photo or "visual lookup" controls.

### 3.6 Details sheet (swipe up or the Info button)

Cards with 24 dp radius and 16 dp side margins, in this order:

1. **Map header** when the photo has a location (small map; before MapLibre is added in Phase 5 it shows a place name card instead), with the place name.
2. **Caption** row (read-only in Phase 2: "No caption").
3. **Date card:** full date and time, file name, a status icon for "in Nextcloud / on this phone / both" (phone states arrive in Phase 3), the type with a format badge.
4. **Camera card:** camera model, lens, megapixels, dimensions, size, then a row of five values (ISO, focal length, exposure compensation, aperture, shutter). Missing values show "-".
5. **People row** (face chips) appears when the snapshot has faces (Phase 4+); not in Phase 2.
6. **Location and keywords** actions are not offered in Phase 2 (nothing is written).

### 3.7 Collections (Phase 2 subset)

- Large title "Collections". A vertical list of sections, each with a title, chevron and collapse button, and a horizontal row of cards (105 dp square cards, radius 20, 12 dp gaps, 24 dp start margin), label over a dark gradient at the bottom left.
- Sections in Phase 2: **Pinned** (Favourites, Recently added, Videos, Screenshots), **Albums** (Nextcloud albums, read-only), **Recent days**, **Media types** (Videos, Screenshots, Panoramas; RAW as a separate row when present), **Utilities** (Favourites, Not backed up arrives in Phase 3).
- Menu at the top right: layout choice (cards / list), **Show all**, **Collapse all**, **Reorder**. Reorder mode: drag handles on section headers, a Done button; hide and show per section. Stored on the phone.
- Pushed pages: a 2-column grid of cards for "see all"; Recent days as day cards; an album opens as a grid like the Library.
- Memories, People, Places, Trips sections are not shown until Phases 4 and 5 (no empty placeholders).

### 3.8 Sign-in, offline and Settings

- **Sign-in:** one screen, app icon and name, a field for the Nextcloud address, a button "Sign in with Nextcloud". It opens the browser; the app resumes when approved. Errors in plain words.
- **Offline:** a slim banner under the header "Nextcloud is not reachable. Is Tailscale on?" with a Retry text button. The grid keeps showing the snapshot and cached thumbnails; not-cached cells stay on their ThumbHash.
- **Settings** (from the account button at the top right of Collections): account (server, user, sign out), snapshot (generation, date, assets), cache (size, limit 2 GB default, clear), updates (check, off while the repo is private), share log, About (version and the GeoNames credit, CC BY 4.0).

## 4. Motion (proposals, tuned on the phone)

| Motion | Timing |
| --- | --- |
| Grid to viewer growth | 300 ms, decelerate curve; background fades to black in the same time |
| Viewer swipe-down return | follows the finger; release springs to the cell in about 250 ms or back to full |
| Bar show or hide on scroll | 200 ms fade and 8 dp slide |
| Density (pinch) change | cross-fade 150 ms, columns snap to the steps |
| Library tab change | 150 ms cross-fade |

Prefer smooth scrolling over effects: no live blur behind the grid, a light 8 dp blur only on the bars if the numbers allow.

## 5. Strings (Greek and English, our own wording)

| Key | English | Greek |
| --- | --- | --- |
| library | Library | Βιβλιοθήκη |
| collections | Collections | Συλλογές |
| search | Search | Αναζήτηση |
| years / months / all | Years / Months / All | Έτη / Μήνες / Όλα |
| select | Select | Επιλογή |
| selected_n | Selected (n) | Επιλεγμένα (n) |
| share | Share | Κοινοποίηση |
| info | Info | Πληροφορίες |
| albums | Albums | Άλμπουμ |
| favourites | Favourites | Αγαπημένα |
| recent_days | Recent days | Πρόσφατες ημέρες |
| media_types | Media types | Τύποι πολυμέσων |
| videos / screenshots / panoramas | Videos / Screenshots / Panoramas | Βίντεο / Στιγμιότυπα / Πανοράματα |
| unknown_date | Unknown date | Άγνωστη ημερομηνία |
| no_caption | No caption | Χωρίς λεζάντα |
| offline | Nextcloud is not reachable. Is Tailscale on? | Το Nextcloud δεν είναι προσβάσιμο. Είναι ανοιχτό το Tailscale; |
| sign_in | Sign in with Nextcloud | Σύνδεση με το Nextcloud |
| reorder / show_all / collapse_all | Reorder / Show all / Collapse all | Αναδιάταξη / Εμφάνιση όλων / Σύμπτυξη όλων |
| items_n | n items | n στοιχεία |

Chris reviews the Greek in Phase 6 (plan).

## 6. Where this deliberately differs from the reference

- Bar fill is a semi-opaque surface, not heavy glass blur (cost on Android).
- No sync line, no Edit, no Delete, no favourite toggle in Phase 2.
- No "Create a Memory Movie", Live Photo or visual lookup features.
- Our own icons (Material Symbols, rounded), our own colours and wording.
- Reorder and hide of Collections sections is included in version 1.
