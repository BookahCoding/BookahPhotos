# Phase 2: the app compared with the design spec

Date: 12 November 2026, state of build 0.7.0 plus the 2e changes. Sources: `docs/design-spec.md`, emulator screenshots with generated data, automated size checks (`SpecSizesTest`), and what Chris reported from his Xiaomi 15T.

Legend: **same** = matches the spec; **different (on purpose)** = a decision recorded in the spec or by Chris; **gap** = the spec asks for it and it is not there yet.

## Shared pieces

| Spec | Status | Evidence |
| --- | --- | --- |
| Two-tab pill (60 dp) plus a separate round Search button (60 dp) | same | `SpecSizesTest.tabBarMatchesTheSpec` |
| Library bar: round Library button 48 dp, Years / Months / All pill 48 dp high, round Search 48 dp | same | `SpecSizesTest.libraryBottomBarMatchesTheSpec` |
| Round header buttons 44 dp, text pill "Select" 44 dp high | same | `SpecSizesTest` |
| Large title with a subtitle line, no sync line | same | screenshots; Chris decided: no sync line |
| Search button | different (on purpose) | inactive until the search phase |

## Screens

| Screen | Spec item | Status |
| --- | --- | --- |
| Library, All | 5 columns, 1 dp gaps, edge to edge; pinch 3 / 5 / 9 / 16; newest at the bottom; floating title with the visible date range, the item count at the end; fast scroll with a month bubble | same (Chris: scrolling "really smooth", pinch works) |
| | Unknown date group at the top | same |
| | video glyph and length, favourite heart, RAW tile | same (the heart sits bottom right, the spec said bottom left: different, harmless) |
| | filter menu (sort: added / taken; show: favourites, videos, screenshots) | **gap**: only the source filter (All / Nextcloud / This phone) exists; sorting and the other filters are not built (Collections already offers Favourites, Videos, Screenshots as pages) |
| Months | large cover plus three small tiles with the day number; one or two days use equal tiles; a tap opens All at that day | same |
| Years | one large card per year, year label top left | same |
| Select mode | round close button, check marks, bottom bar with Share and "Selected (n)", hold and slide to select | same; the trash button is **different (on purpose)**: the spec said "not before Phase 6", Chris asked for delete on 12 November |
| Viewer | swipe through the whole library, zoom, filmstrip, date pill, share, info, black background | same |
| | grows out of the cell, swipes down into it | **different (on purpose)**: scale-and-fade from the cell with one spring, not an exact shared-bounds transition (Chris: "feels and looks better") |
| | "more" button and the format badge row ("JPEG + RAW") | **gap** |
| | favourite heart | state only; the toggle comes with Phase 6 |
| | video in place with a scrubber | same (Chris: "video was playing normally") |
| Details sheet | place, caption (read only), date card, camera card with the five values, where the photo is | same; the map is a place name until the map phase; people row waits for faces |
| Collections | sections with title, chevron and collapse button; cards 105 dp, radius 20; menu with Show all / Collapse all / Reorder | same (cards were 112 dp until 2e, now 105) |
| | reorder with drag handles | **different (on purpose)**: up and down buttons plus a switch (exact, works with TalkBack) |
| | cards or list layout; Utilities section | **gap**; "Not backed up" lives in Pinned instead of Utilities |
| | account button opens Settings | same |
| Sign-in, offline banner | one screen; a slim banner "Nextcloud is not reachable. Is Tailscale on?" with Retry | same (Chris: the notice works) |
| Settings | account, index, cache, updates, About with the GeoNames credit | same; "share log" is **a gap**; added: phone photos counts, Speed |

## Motion (the spec says proposals, tuned on the phone)

| Spec | Status |
| --- | --- |
| viewer grow 300 ms, swipe-down return about 250 ms | different (on purpose): a spring that continues from the finger |
| pinch density change: 150 ms cross-fade | gap: the number of columns changes at once |
| bar fade 200 ms | same (180 ms) |

## Performance targets (plan 10.3)

| Target | What was measured | Status |
| --- | --- | --- |
| Library scroll, 50,000 items, under 1% slow frames | **Measured on Chris's Xiaomi 15T (release build 0.8.0, his real library of 24,732 items):** six-second scrolling test at 120 Hz: 719 frames, **0.0% slow**, typical frame 8.3 ms, slowest 8 ms. The in-memory side for 50,000 items on the build PC: library build 7 ms, merge with 8,000 phone photos 63 ms | **met** for 24,700 items; 50,000 not run on the phone (the memory work is linear and tiny) |
| Cold start to a usable Library under 1.5 s | **190 ms** from process start to the Library being built (snapshot already on the phone); building the library took 61 ms for 24,732 items | **met** |
| Viewer first frame under 150 ms | not measured | gap; Chris finds it fast |
| Disk cache capped at 2 GB, adjustable, with a clear button | capped at 2 GB, clear button works; the limit is not adjustable | partly |
