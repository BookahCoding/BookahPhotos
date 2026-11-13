# Phase 6 proposal: changes and polish

Status: **proposal only; nothing built. Each step starts only after Chris's yes to the questions below.** Written 13 November 2026 from plan section 12 and what Phases 2 to 5 already delivered.

## Already done (so not repeated here)
Delete to the Nextcloud trash with confirmation (Phase 3), hide photos with a way back (Phase 5), the Hidden page behind the phone's unlock (Phase 5).

## Proposed steps (each ends with a report and waits for a yes)

| Step | Content | How it is checked |
| --- | --- | --- |
| 6a | **Favourites:** tap the heart in the viewer (it is only a display today), and a Favourite button in the Select bar. Written to Nextcloud as the file's favourite flag, so Nextcloud's own apps and Memories see the same. Works offline (kept on the phone, sent later, shown at once). | flag set and cleared on the real server on a test file; offline then online; the Favourites row follows |
| 6b | **Albums:** create, rename, add photos (from the viewer and the Select bar), remove photos, delete an album. Done through Nextcloud's own Photos album interface, so they appear in Nextcloud Photos and Memories. | made, renamed, filled and emptied on the real server with test files; they show in Nextcloud's web Photos |
| 6c | **Greek review:** a private page with every English and Greek text side by side, where Chris corrects the Greek and saves a file; I apply it. Done early so he can do it in his own time while the rest is built. | every string reviewed; app in Greek checked on screens |
| 6d | **Accessibility:** TalkBack labels on every button and tile, text size up to 200% without cut-off text, touch targets of at least 48 dp, contrast in light and dark. | automated checks of labels and sizes in the screen tests; large-text screenshots; Chris tries TalkBack briefly |
| 6e | **Speed profile (baseline profile):** the list of classes the app needs at start-up, so it opens and scrolls faster. | cold start and slow-frame numbers measured before and after on the emulator; Chris reports the feel on the Xiaomi |
| 6f | **Tablet layout** (MovinkPad): wider grids, a side-by-side layout for People and Collections where it helps. Only if Chris still wants it in version 1. | screen tests at tablet sizes; Chris tries it on the tablet |
| 6g | **Small extras** (optional): unhide several photos at once; the indexer marking Selfies, Motion photos and Duplicates so those rows can appear in Collections. | tests; the rows are filled on the real library |
| 6h | **The indexer as a BookahPC Server job** (instead of Windows Task Scheduler) with a short result line Shisha can report. Changes the BookahPC Server, so only after a yes. | one run through the runner; the Task Scheduler entry stays until it works |
| end | Acceptance run, then Chris decides on **1.0.0** and on making the repository public (needed for in-app updates). | |

## Needs Chris's yes or answers

1. **The app may change data in Nextcloud:** favourite flags on his photos (6a) and Nextcloud Photos albums (6b). This is the first time the app changes anything but its own folder `BookahPhotos/ops`. Nothing is deleted or moved; albums only list photos. **Yes / no?**
2. **Tablet (6f):** still wanted in version 1? **Yes / later.**
3. **Indexer as a BookahPC Server job (6h):** **yes / no / later.** My suggestion: later (Task Scheduler works, the daily job has run well).
4. **Extras (6g):** unhide several at once, and Selfies, Motion photos and Duplicates (needs indexer work): **yes / skip.**
5. **Greek review (6c):** is a side-by-side page with save fine, or would he rather get a plain file? **Page (default) / file.**
6. **Order:** 6a Favourites first, then 6c in parallel, then 6b Albums, 6d, 6e, then the optional ones. OK?

## Risks
- Favourites and albums change his real Nextcloud data. Mitigation: test only with files made for the test (a test folder), never his photos; each action is one small, reversible request; the app only ever sets or clears the favourite flag and adds or removes album entries (never deletes a photo).
- Nextcloud's album interface is less documented than the rest. Mitigation: tried first on a test album on the real server before any app code.
- A baseline profile made on an emulator may differ from the phone. Mitigation: measure on the emulator, ask Chris for the felt result.
