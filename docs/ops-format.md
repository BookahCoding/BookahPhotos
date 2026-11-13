# Ops format (version 1)

Ops are Chris's corrections: naming a person, merging two people, moving a face, hiding a photo. The app (Phase 5) writes them; the indexer on BookahPC reads and applies them. Code: `indexer/src/bookahphotos_indexer/ops.py`. Tests: `indexer/tests/test_faces.py`.

## Where

- The app writes one file per batch: `BookahPhotos/ops/<device>/<UTC time>-<n>.jsonl` (for example `ops/xiaomi-15t/20261113T093000Z-1.jsonl`). A file is never edited after it is written.
- The indexer applies them in order and moves each fully applied file to `BookahPhotos/ops/applied/<device>/` (a move inside `BookahPhotos/`, which the indexer may write to). The `applied/` folder is the permanent record.
- If `BookahPhotos/ops/` does not exist there are no ops. The indexer never creates it (the app does).
- The indexer moves files only when started with `--move-ops`. **Chris said yes to the app writing into `ops/<device>/` and to `--move-ops` on 13 November 2026.** The first real batch is moved by hand and checked (only op files, only into `applied/<device>/`); after that the daily job gets `--move-ops`.
- **The app's side (5c):** each batch is one new file, uploaded with `If-None-Match: *` (a name clash gives 412 and a new name is tried; checked against the real server: folder 201/405, new file 201, same name again 412). The app writes only below `ops/<device>/` (the device name comes from the phone model, for example `xiaomi-15t`). A person is named by up to 8 of their clearest faces from different photos; the box is `[x1, y1, x2, y2]` (the snapshot stores x, y, width, height: the app converts). `t` has a fixed form (`2026-11-13T09:30:00.123Z`) so that sorting by `t` is sorting by time. The golden file `indexer/tests/fixtures/ops-golden.jsonl` is produced byte for byte by the Kotlin writer (its test) and applied by the indexer (its test).
- **How the phone learns an op arrived:** the snapshot (schema 1.2) has the table `applied_ops` with the id of every op the indexer applied. The phone shows its own corrections on top of the snapshot until that table lists them, then drops them. An op the indexer cannot place stays unapplied and stays pending on the phone (Settings shows the count).

## One op per line (JSON)

Every op has: `op_id` (a new UUID, unique), `t` (UTC time, ISO 8601), `type`, and the fields below. Unknown types or lines that are not valid JSON are counted and ignored; the file stays where it is so nothing is lost.

A **face reference** is `{"file_id": 12345, "box": [x1, y1, x2, y2]}`: the Nextcloud file id and the box as fractions (0 to 1) of the upright picture, exactly as in the snapshot's `faces` table. A face is found by overlap (IoU of at least 0.5 in the same file), never by an exact box, so small changes in the detector do not break it.

A **person reference** is `{"person": 17, "anchors": [<face reference>, ...]}`: the person id from the snapshot plus 6 to 10 faces of that person from the same snapshot. Person ids come from clustering and can change when the indexer's private database is rebuilt, so the anchors are what makes an op survive that. The indexer first trusts the id when the anchors agree with it; otherwise it finds the person that holds most of the anchors, and that person takes the op's id back (ids are swapped if needed). Merged people stay resolvable.

| `type` | fields | effect |
| --- | --- | --- |
| `name_person` | `person`, `anchors`, `name` | sets the name |
| `hide_person`, `unhide_person` | `person`, `anchors` | hides or shows the person everywhere |
| `merge_people` | `from` and `into`, each a person reference | all faces of `from` go to `into` (marked manual); `from` stays as a merged-away id; `into` keeps its name, or takes the name of `from` when it has none |
| `move_face` | `face`, `to` (a person reference, or `null` for "nobody") | the face belongs to that person (marked manual) |
| `not_a_person` | `face` | the face is not a person (statue, drawing, poster): removed from all people and never grouped again |
| `set_cover` | `person`, `anchors`, `face` | the cover picture of the person |
| `hide_photo`, `unhide_photo` | `file_id` | hides the photo from Library and memories |
| `unwanted_memory` | `key` | this memory (its key from the snapshot) is never made again |
| `wanted_memory` | `key` | the way back: the memory may be made again (the app's Undo) |

Example (one line, broken here for reading):

```
{"op_id":"7d2e...","t":"2026-11-13T09:30:00Z","type":"name_person","person":17,
 "anchors":[{"file_id":4411,"box":[0.31,0.12,0.52,0.40]}, ...],"name":"..."}
```

## Rules

- **Applying twice changes nothing** (`op_id` is remembered; a rebuild replays from `applied/`).
- **Order:** by `t`, then `op_id`.
- **Manual wins:** a face that an op moved, merged or rejected is marked manual and automatic grouping never moves it again.
- **Not applicable yet:** if the target is not found (for example the photo is not indexed yet) the op is skipped, reported, and its file stays in `ops/` so it is tried again next run.
- **A rebuild** (delete the private database, index and group again, then `group --ops --replay-ops`) gives back: names and ids of the people touched by ops, merges, moved and rejected faces. It does **not** promise that the automatic groups of people nobody touched are identical to before (the live database grows step by step, a rebuild clusters everything at once).
- Limits: a file with a changed content (new etag) has its faces read again, and ops on its old faces may not match.
