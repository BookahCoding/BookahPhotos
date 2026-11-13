# Phase 6 report (running): 6a Favourites and 6c Greek review

Date: 13 November 2026. Chris's answers to the proposal: favourites and albums may change Nextcloud data (yes); tablet later; indexer as a BookahPC Server job later; extras (6g) yes; Greek review as a page; order as proposed.

## 6a Favourites (test build 0.17.0)

### Implemented
- **The heart in the viewer works:** tap it to set or clear the favourite; a photo that exists only on this phone (not in Nextcloud) shows the heart but it does nothing.
- **Select bar:** a heart button favourites all selected photos, or clears them when every selected photo already is a favourite (its label says "Remove from favourites").
- **How it is saved:** the change is kept on the phone first and shown at once (hearts on tiles, the Favourites row), then sent to Nextcloud as the file's favourite flag (the same flag Nextcloud's own apps and Memories use). **Offline:** it waits and is sent later (on start, after a refresh, or on the next change). If Nextcloud answers a server error it is kept; if the file is gone it is dropped; a wrong sign-in keeps it for the next sign-in; signing out forgets unsent changes. The latest wish wins per photo.
- **Safety:** the app only sets or clears the favourite flag of a file inside the photo folders. It never moves, renames or deletes anything.

### Verified
- **On the real Nextcloud, with a test file in a folder I made and deleted again:** the flag request is accepted (207), the file appears in the favourites list, setting again changes nothing, clearing removes it, a missing file gives 404. (Done with the same request the app sends, from the indexer's Python.)
- **Kotlin (JVM):** the request is one PROPPATCH on that file with the right body and login; clearing sends 0; 404, 500 and a wrong password are handled as above; paths outside the photo folders are refused without any request; the queue keeps the latest wish, does not erase a newer wish when an older one finishes, survives a restart; the overlay on Nextcloud's list; the Select bar heart sets unless all are set (phone-only photos are left out).
- **Screens (emulator):** the heart in the viewer sets and clears (and does nothing for a phone-only photo); the Select bar heart and its label; older suites pass (library 27, viewer 17, collections 36).

### Not verified
- On Chris's phone: the whole loop with a real photo (the heart, then Nextcloud's own web Photos or app showing it as a favourite).
- The Kotlin request itself against the real server (the Python request is the same bytes; no password is put into a test).

## 6c Greek review (page ready for Chris)
- **Page:** `C:\BookahPC\bookahphotos\review\greek\index.html` (private, on BookahPC; open it in a browser). 258 texts in 6 modules, English on the left, Greek on the right to edit. Placeholders like `%1$d` are marked and a warning shows if they differ from the English; search, "only changed", and your edits are kept in the browser while you work. **Save results** gives `greek-review-results.json` in Downloads; send it back (upload it as before).
- **Tool:** `tools/greek-review.py make` builds the page, `apply FILE` writes the corrected Greek into the app (refusing any text whose placeholders differ). Tested: every existing text survives decode and encode unchanged; a changed text, a text with an apostrophe, and a refused text were applied/refused correctly.

## Next
6b Albums (create, rename, add, remove, delete), after a test of Nextcloud's album interface on a test album on the real server.

---

# After Chris tried 0.17.0 (favourites) and the Greek review

## Greek review applied
Chris's file had 8 corrections; all applied (the placeholders were all fine): "Σε χρήση", "Εκκαθάριση κρυφής μνήμης", the update text now names Tailscale, "Εκκίνηση έως τη Βιβλιοθήκη", "Το πιο αργό καρέ", "Άτομα" (instead of "Άνθρωποι"), and the back-up button. One small thing I changed in his text: "αντίγραφου" became "αντιγράφου" (the correct genitive), please tell me if you disagree.

## Fixes after his feedback
- **"Moving to the trash takes a while, there should be something that tells you":** there was a progress screen, but it was drawn **under** the photo viewer, so deleting from the viewer showed nothing. It is now on top of everything and also says "This can take a few seconds. Please wait." (English and Greek).
- **Favourites "take a bit to happen":** the heart was immediate but the **Favourites row** waited for Nextcloud. It now follows the heart at once.
- **Selection in albums and other photo pages (his request):** every page of photos (an album, Favourites, a place, a day, a trip, a memory's photos) now has the Library's selection: a **Select** button, press and hold a photo (then slide to select more), and the bottom bar with the same buttons: share, add to album, favourite, hide, delete. (The hold-and-slide code is now shared with the Library, not copied.) Tested: the Select button, hold, tap to toggle, Done, the buttons pass the selected photos, the heart clears when all selected are favourites, no Select button without actions.
- Nextcloud's Memories app does not show the favourites made in the app: Chris does not need that, so left as is.

## 6b Albums (test build 0.18.0)
### Implemented
- **New album:** a "New album" tile first in the Albums row (also when you have none), and "New album" in the add-to-album choice. Names: not empty, no slash, at most 100 characters, and not one you already have (case and accents ignored).
- **Add photos:** a **+** button in the viewer, and in the selection bar, opens "Add to album" (your albums, or a new one). A photo that is already in the album counts as added; photos only on the phone cannot be added (said in the message).
- **An album's page:** opens like any photo page with selection, and has a menu: **Rename album** and **Delete album** (the dialog says only the album is deleted; the photos stay). Inside an album the viewer has an **x** button: **Remove from this album** (only the entry in the album).
- **While it works:** a screen with progress ("3 of 20"); then a message ("Added 3 to Summer.", or "Nextcloud could not be reached. Nothing was changed."). The albums shown follow at once and are read from Nextcloud again afterwards.

### Verified
- **On the real Nextcloud, with a test photo and test album that I deleted again (your 4 albums were not touched):** create 201; add by copying the photo in 201 (the entry is named `<file id>-<file name>` and has the original's file id); adding the same photo again 409 (treated as "already there"); rename by moving 201 and the photo stays in it; take the photo out 204 and the **original photo stays**; delete the album 204 and the original stays. (My first run of this check stopped half way because of a mistake in my script and left a test album and folder; I removed them straight away and re-ran it with clean-up.)
- **Kotlin (JVM, 5 tests):** each action sends exactly one request of the right kind to the right address (Greek names encoded), a name that exists or is not usable is reported or refused without a request, removing finds the entry by file id and deletes only that entry, errors are handled.
- **Screens (emulator):** 9 tests of the New album tile, the name dialog (empty, existing, accent-insensitive clash, slash, trimming, renaming keeps its own name), the picker, the album menu and the delete dialog; the viewer buttons (add, remove; none for a photo only on the phone); the Select bar button.

### Not verified
- On Chris's phone, with his real albums (and Nextcloud's Photos app showing the changes).
- Adding many photos at once is one request per photo (a few seconds for 20).

---

# 6d, 6e, 6g and the end of Phase 6 (test build 0.19.0)

Chris asked me to finish everything that was left and said he would report problems. Tablet layout (6f) and the BookahPC Server job (6h) stay **later**, as he answered in the proposal.

## 6g Extras
- **Unhide several at once:** the Hidden page now has Select (also press and hold and slide) and an **Unhide** button in the bar; no share or delete there (those photos are not in the library).
- **Selfies, Motion photos, Duplicates (indexer, snapshot generation 20):**
  - *Selfies* (537 photos): the photo's own lens name says "front camera" (or "selfie"). Phones that do not write it are not guessed.
  - *Motion photos* (7): a photo with a video of 5 seconds or less of the same name in the same folder (Live Photos, Android motion photos), or an Android motion photo by name (MVIMG_...). Few, because Chris's library has few. The video itself is not marked.
  - *Duplicates* (1,961 photos in 917 groups at first; see the fix below): exactly the same size, capture time, dimensions and type, so the same picture saved twice (for example in two folders); a photo without a capture time is never grouped.
  - **In the app:** Selfies and Motion photos are cards in Media types; **Duplicates** is a tile next to Hidden under Utilities (with the number) that opens the photos group after group; the same selection bar is there, so one copy of each pair can be deleted. Nothing is deleted by itself.
  - Tested in the indexer (selfie, Live Photo with a short video, a long video that is not motion, name rule, groups, photos without time) and in the snapshot (flags and groups present, part of the fingerprint); 150 indexer tests.

## 6d Accessibility
- **Android's own accessibility checker (the one Google's Play pre-launch report uses) now runs in the screen tests** of the Library (also in select mode), the viewer with all buttons, search, Collections, People, a person's page, the photo pages, memories and the faces page. It found real problems that are fixed:
  - **Photo and face tiles had no spoken name** (a screen reader said nothing): they now say "Photo, 12 Aug 2024", "Video, ..." (Greek too), and "selected" in select mode, "Face of Ann".
  - **The viewer's bottom buttons and filmstrip** had no spoken name or were smaller than 48 dp: now 48 dp with names ("Remove from favourites", "Add to album", "Hide", "Information"...).
  - **Search:** chips, the clear button, the field and two text buttons are at least 48 dp.
  - **Text at 200%:** on a person's page the buttons ran off the screen; they now wrap onto a second line (tested at 200%).
- Passing: all those screens pass the checker (labels, touch size 48 dp, contrast). Not covered by the checker: Settings and the sign-in screen (in the app module), the map's own view, and dialogs (separate windows). **Not tested with TalkBack itself:** Chris could try it briefly (Settings > Accessibility > TalkBack).

## 6e Speed profile (baseline profile)
- A start-up profile for the app's own code and for the picture, network and JSON libraries is part of the app now (`app/src/main/baseline-prof.txt`); Compose and AndroidX bring their own. It is packaged in the app and Android accepts it (`speed-profile` compile works).
- **No gain could be shown on the emulator:** with the only screen that can run without a sign-in, cold start was 522 ms without and 568 ms with the profile (8 starts each; the difference is noise). The real effect is on the Library after sign-in with 25,000 photos, which I cannot run on an emulator without his data. So this is **not verified as faster**; Chris's feel on the Xiaomi is the test, and it can be taken out again if the app feels no better.

## Tests (all green)
Indexer 150; Android JVM 136; on the emulator 138 (Library 30, viewer 20, collections 60, search 13, map 6, core 9; includes the real snapshot of generation 20 compared with SQL). Not run: Settings and sign-in screens (no tests exist for them).

## End of Phase 6: what is open for Chris
1. Try 0.19.0: unhide several photos, the Duplicates tile, Selfies, TalkBack if he likes, and say whether the app feels quicker (the speed profile).
2. **Accept Phase 6**, or tell me what to fix.
3. Decisions that are his: **1.0.0** (not a pre-release), whether the repository becomes **public** (then in-app updates can be switched on; it also rules out the non-commercial face model if I had used it; I used the permissive one), tablet layout later, the BookahPC Server job later, the iPhone version later.

---

# 0.19.1: Duplicates fixed after Chris's first look

Chris said Duplicates helped a lot, but it also showed almost all the wedding photos, which look like bursts, and that the pairs stayed after he deleted the duplicates.

- **Why the wedding photos showed:** 840 of the 917 groups were **scanned TIFF film frames** ("a scanned-film folder", 1,725 photos). An uncompressed TIFF has a size that depends only on its dimensions, so two different scans made in the same second have exactly the same size, time and dimensions: not duplicates. **Fixed in the indexer:** TIFF and BMP are never grouped by size (compressed formats only). Duplicates are now only **the 77 JPEG groups** that really are the same picture saved twice. Tested (two scans of different frames are not grouped).
- **Pairs staying after deleting one copy:** the phone showed the photos the snapshot listed; after a delete the surviving copy stayed alone in the list. **Fixed in the app:** a group with fewer than two photos still in the library disappears at once (tested). And **yes, the indexer refreshes it**: the daily job (or my run) notices the deleted files, and the next snapshot has no such group (generation 21, made right after his deletions, already has 24,468 photos instead of 24,614).
- **Generation 21** (with the fix) is published; tap Refresh in Settings to get it.

---

# 1.0.1 (test build): the advisor's review of 1.0.0, fixed

Chris installed 1.0.0 and asked for an independent review. What was checked in the code and what was done (Chris said: all bugs, the mobile-data switch, the crash log, and Who is this).

## Bugs fixed
- **The map did not pause or free memory:** its pause, resume and low-memory calls were empty or missing. All forwarded now.
- **The update check could miss a final release** after many test builds (it read only the 15 newest). It now asks GitHub for the **latest release** (never a pre-release or draft).
- **Face pictures** were kept by number (400); now limited to 32 MB by size.
- **"On this day"** could stay stale after midnight: refreshed every time the app comes to the front.
- **A cancelled album action** was reported as "Nextcloud could not be reached": cancellation is passed on now.
- **A first name in a note of the public repo** (`HANDOFF.md`): removed.
- **Joining people after a rebuild by anchor faces:** I had loosened the rule to two agreeing faces; two faces that moved could point at the wrong person. **Now three are needed** (tested with two misleading faces, and with the correct case of three). Checked again on a copy of the real database: all 40 names still come back after a rebuild; 4 of the 32 phone corrections cannot be placed again (their groups no longer exist after regrouping), the same as with the first rule.

## The mobile data switch (off by default)
**Settings > "Use mobile data for big downloads"** (off). While off, on mobile data (or any network Android calls metered, also while Tailscale is on) the small manifest is still checked, but the **index (about 45 MB, new almost every day) and the search model (158 MB) wait for Wi-Fi**: the app says "A new index (45 MB) is waiting for Wi-Fi" (a note in Library and Settings) with a **Download now on mobile data** button for one time. Turning the switch on lets it download.

## Crash log
`BookahApp` writes any uncaught error (time, version, phone, stack) to a file on the phone (the last 5, small). **Settings > Problems** appears only when there is one, with **Share the report** (you choose where it goes; nothing is ever sent by the app) and **Delete the report**.

## "Who is this?"
- **People page > "Who is this? (N faces)":** the faces nobody has named (about 16,000), clearest first. Tap faces (or press and hold and slide) to select the faces of one person, then **Who is this?**: pick someone who exists (searchable list), or **A new person...** (type a name; if the name exists you are asked "Same person as X?"), or **Not a person** (a statue, a picture on a wall).
- **The phone shows it at once** (the faces leave the list; a new person appears in People with a temporary id). New op `new_person` (indexer: makes the person from those faces, marked manual so more faces can join; a person shows in the snapshot once it has 5 faces, so give a new person several faces).
- Tested: the indexer op (applied, skipped and retried when the faces are not found); the golden file (15 ops) is identical on both sides; overlay logic (assign, new person, more faces before the indexer has seen it, not a person, taking a face from somebody puts it in the list, hidden photos); screens (select, assign, new person, same-person question, clear, not a person, empty page, the link).

## Not done (honest)
- **Tests of the app's controllers** (people, favourites, albums, search, and the hide/relock glue in `Home`) are still missing: the app module has no JVM test setup, and the logic behind them is tested in `core-data`. To add real controller tests the controllers need a small refactor (a store and a clock they are given).
- Speed: Chris's measurement on 1.0.0: **start to Library 8.8 s** (the target is 1.5 s), building the library 32 ms, scrolling 0 slow frames. See below.

## The speed result Chris sent (1.0.0, screenshot)
Start to Library **8,839 ms** (target 1.5 s). Building the library takes only 32 ms for 24,538 photos and scrolling is perfect (720 frames, 0.0% slow), so the 8.8 s is **before the library appears**.
- **What I measured** (the real snapshot on the PC emulator, cold, every start-up step timed): reading the 24,538 photos 0.26 to 0.7 s, merging and building the library 0.36 to 0.63 s, people 0.2 to 0.35 s, search vectors 0.45 to 0.5 s, memories and places 0.1 s together. So the reading and building the app has to wait for is about **1 to 1.3 s**; I cannot reproduce 8.8 s from the code.
- **Changed in 1.0.1:** duplicates, memories and places are no longer waited for before the library is built (they are read in the background): this takes about 0.15 s off the wait.
- **Likely reasons for 8.8 s (not proven):** the first start after installing a new version (Android is still compiling the app in the background and the 45 MB file is not in the phone's memory yet), or the start of the app waiting on something else (the sign-in store, the phone's own photo list).
- **To find out:** Settings > Speed now also shows **"Before the app starts working"** (from the process start to the app's own start) and **"Reading the index file"**. **Chris: please close the app completely, open it again, and send a screenshot of Settings > Speed a second and a third time.** If the start is still slow, those two lines tell where the time goes.
