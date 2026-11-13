"""The indexer's private working database (never goes into Nextcloud or Git)."""
from __future__ import annotations

import sqlite3
import time
from pathlib import Path

ASSET_FIELDS = ["taken_utc", "taken_offset_min", "taken_zone_assumed", "taken_source", "day_key", "width", "height", "duration_ms",
                "latitude", "longitude", "place_id", "camera", "lens", "exposure", "flags", "content_hash", "duplicate_group"]

DDL = """
CREATE TABLE IF NOT EXISTS files (
    file_id INTEGER PRIMARY KEY, path TEXT NOT NULL, etag TEXT NOT NULL, size INTEGER NOT NULL, mime TEXT NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 0,
    meta_tries INTEGER NOT NULL DEFAULT 0, meta_version INTEGER NOT NULL DEFAULT 0, preview_tries INTEGER NOT NULL DEFAULT 0, thumbhash BLOB,
    taken_utc INTEGER, taken_offset_min INTEGER, taken_zone_assumed INTEGER NOT NULL DEFAULT 0, taken_source TEXT, day_key TEXT,
    width INTEGER, height INTEGER, duration_ms INTEGER, latitude REAL, longitude REAL, place_id INTEGER, camera TEXT, lens TEXT,
    exposure TEXT, flags INTEGER NOT NULL DEFAULT 0, content_hash TEXT, duplicate_group INTEGER
);
CREATE INDEX IF NOT EXISTS files_path ON files(path);
CREATE TABLE IF NOT EXISTS folders (path TEXT PRIMARY KEY, etag TEXT NOT NULL, committed_run INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS runs (id INTEGER PRIMARY KEY AUTOINCREMENT, started REAL NOT NULL, finished REAL, status TEXT NOT NULL, note TEXT);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
-- Phase 4: what the models read from each picture. etag = the file version it was made from (a changed file is read again).
-- status: ok | no_picture (no usable preview or original) ; source: preview1024 | preview256 | original
CREATE TABLE IF NOT EXISTS content (
    file_id INTEGER PRIMARY KEY, etag TEXT NOT NULL, status TEXT NOT NULL, source TEXT, model TEXT, vec BLOB, sharp REAL,
    tries INTEGER NOT NULL DEFAULT 0, made REAL NOT NULL
);
-- Phase 4b: faces. Embeddings stay here (private); the snapshot only gets boxes and person ids.
-- faces_done: every file that was looked at (also files with no face), for the file version and face model.
CREATE TABLE IF NOT EXISTS faces_done (file_id INTEGER PRIMARY KEY, etag TEXT NOT NULL, model TEXT NOT NULL, n_faces INTEGER NOT NULL, made REAL NOT NULL);
-- box normalised 0..1 on the upright picture. assigned_by: auto | manual (a manual choice is never moved by automatic grouping).
-- rejected = 'this is not a person'. dist = cosine distance to the person's centre when it was assigned.
CREATE TABLE IF NOT EXISTS faces (
    face_id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL, x1 REAL NOT NULL, y1 REAL NOT NULL, x2 REAL NOT NULL, y2 REAL NOT NULL,
    score REAL NOT NULL, width_1280 REAL NOT NULL, usable INTEGER NOT NULL, emb BLOB NOT NULL,
    person_id INTEGER, assigned_by TEXT NOT NULL DEFAULT 'auto', rejected INTEGER NOT NULL DEFAULT 0, dist REAL,
    founder INTEGER NOT NULL DEFAULT 0      -- 1 = this face formed its group (clustering); only founders and manual faces define a person's centre
);
CREATE INDEX IF NOT EXISTS faces_file ON faces(file_id);
CREATE INDEX IF NOT EXISTS faces_person ON faces(person_id);
-- merged_into: this person was merged into another (kept so that old ids in ops still resolve). cover_face_id: chosen cover.
CREATE TABLE IF NOT EXISTS persons (
    person_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, hidden INTEGER NOT NULL DEFAULT 0, merged_into INTEGER, cover_face_id INTEGER, created REAL NOT NULL
);
-- A cache of which ops were applied (the real record is the files in BookahPhotos/ops/applied/, so a rebuild replays from there).
CREATE TABLE IF NOT EXISTS ops_seen (op_id TEXT PRIMARY KEY, applied REAL NOT NULL, result TEXT NOT NULL);
-- Photos and memories hidden by ops.
CREATE TABLE IF NOT EXISTS hidden_files (file_id INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS unwanted_memories (memory_key TEXT PRIMARY KEY);
"""


class State:
    def __init__(self, path: Path | str):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.db.executescript(DDL)
        cols = {r[1] for r in self.db.execute("PRAGMA table_info(files)")}
        if "meta_version" not in cols:      # state files made before this column existed
            self.db.execute("ALTER TABLE files ADD COLUMN meta_version INTEGER NOT NULL DEFAULT 0")
            self.db.execute("UPDATE files SET meta_version=1 WHERE level>=1")
            self.db.commit()
        fcols = {r[1] for r in self.db.execute("PRAGMA table_info(faces)")}
        if fcols and "founder" not in fcols:        # state files made before founders existed (13 Nov 2026): the faces that formed a group, as far as it can be told
            self.db.execute("ALTER TABLE faces ADD COLUMN founder INTEGER NOT NULL DEFAULT 0")
            self.db.execute("UPDATE faces SET founder=1 WHERE person_id IS NOT NULL AND assigned_by='auto' AND score>=0.8 AND width_1280>=60")   # clear faces of people
            self.db.commit()

    def close(self) -> None:
        self.db.close()

    # ---- runs and kv ----
    def start_run(self) -> int:
        cur = self.db.execute("INSERT INTO runs(started, status) VALUES (?, 'running')", (time.time(),))
        self.db.commit()
        return int(cur.lastrowid)

    def finish_run(self, run_id: int, status: str, note: str = "") -> None:
        self.db.execute("UPDATE runs SET finished=?, status=?, note=? WHERE id=?", (time.time(), status, note, run_id))
        self.db.commit()

    def kv_get(self, key: str, default: str | None = None) -> str | None:
        r = self.db.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
        return r[0] if r else default

    def kv_set(self, key: str, value: object) -> None:
        self.db.execute("INSERT OR REPLACE INTO kv(key, value) VALUES (?, ?)", (key, str(value)))
        self.db.commit()

    # ---- folders ----
    def folder_etag(self, path: str) -> str | None:
        r = self.db.execute("SELECT etag FROM folders WHERE path=?", (path,)).fetchone()
        return r[0] if r else None

    def commit_folder(self, path: str, etag: str, run_id: int) -> None:
        self.db.execute("INSERT OR REPLACE INTO folders(path, etag, committed_run) VALUES (?,?,?)", (path, etag, run_id))
        self.db.commit()

    # ---- files ----
    def get_file(self, file_id: int) -> sqlite3.Row | None:
        return self.db.execute("SELECT * FROM files WHERE file_id=?", (file_id,)).fetchone()

    def upsert_listed(self, file_id: int, path: str, etag: str, size: int, mime: str, run_id: int) -> str:
        """Record a listed file. Returns 'new', 'changed' or 'same'."""
        row = self.get_file(file_id)
        if row is None:
            self.db.execute("INSERT INTO files(file_id, path, etag, size, mime, last_seen) VALUES (?,?,?,?,?,?)", (file_id, path, etag, size, mime, run_id))
            return "new"
        if row["etag"] != etag or row["deleted"]:
            # content changed (or the file came back): everything derived from it is read again
            self.db.execute("UPDATE files SET path=?, etag=?, size=?, mime=?, deleted=0, last_seen=?, level=0, meta_tries=0, meta_version=0, preview_tries=0, thumbhash=NULL WHERE file_id=?",
                            (path, etag, size, mime, run_id, file_id))
            return "changed"
        self.db.execute("UPDATE files SET path=?, last_seen=? WHERE file_id=?", (path, run_id, file_id))  # a rename or move keeps the id
        return "same"

    def mark_subtree_seen(self, folder: str, run_id: int) -> int:
        prefix = folder.rstrip("/") + "/"
        cur = self.db.execute("UPDATE files SET last_seen=? WHERE deleted=0 AND substr(path, 1, ?) = ?", (run_id, len(prefix), prefix))
        return cur.rowcount

    def known_under(self, roots: list[str]) -> int:
        return sum(self.db.execute("SELECT COUNT(*) FROM files WHERE deleted=0 AND substr(path,1,?)=?", (len(r.rstrip('/')) + 1, r.rstrip('/') + '/')).fetchone()[0] for r in roots)

    def missing_under(self, roots: list[str], run_id: int) -> list[int]:
        out: list[int] = []
        for r in roots:
            p = r.rstrip("/") + "/"
            out += [x[0] for x in self.db.execute("SELECT file_id FROM files WHERE deleted=0 AND last_seen<? AND substr(path,1,?)=?", (run_id, len(p), p))]
        return out

    def mark_deleted(self, ids: list[int]) -> None:
        self.db.executemany("UPDATE files SET deleted=1 WHERE file_id=?", [(i,) for i in ids])
        self.db.commit()

    def set_fields(self, file_id: int, fields: dict, level: int | None = None, meta_version: int | None = None) -> None:
        cols = {k: v for k, v in fields.items() if k in ASSET_FIELDS}
        if level is not None:
            cols["level"] = level
        if meta_version is not None:
            cols["meta_version"] = meta_version
        if cols:
            sets = ", ".join(f"{k}=?" for k in cols)
            self.db.execute(f"UPDATE files SET {sets} WHERE file_id=?", (*cols.values(), file_id))
        self.db.commit()

    def pending_metadata(self, current_version: int, max_tries: int = 3, non_video_version: int | None = None) -> list[sqlite3.Row]:
        """New files, and files whose metadata was made by an older version of the reading rules (so a later fix reaches old files too).
        `non_video_version`: when a rule change only concerns videos, files that are not videos need re-reading only below this version."""
        nv = current_version if non_video_version is None else non_video_version
        return self.db.execute(
            "SELECT * FROM files WHERE deleted=0 AND meta_tries<? AND (level=0 OR meta_version<? OR (meta_version<? AND mime LIKE 'video/%')) ORDER BY file_id",
            (max_tries, nv, current_version)).fetchall()

    def reset_metadata(self) -> int:
        """--reread-metadata: forget the derived metadata version of every file; placeholders are kept."""
        cur = self.db.execute("UPDATE files SET meta_version=0, meta_tries=0 WHERE deleted=0")
        self.db.commit()
        return cur.rowcount

    def reset_offset_zero(self) -> int:
        """Metadata version 4: files stored as UTC+0 with a derived (not recorded) offset are read again with the new rule. Placeholders are kept."""
        cur = self.db.execute("UPDATE files SET meta_version=0, meta_tries=0 WHERE deleted=0 AND taken_offset_min=0 AND taken_zone_assumed=0 AND taken_source='memories'")
        self.db.commit()
        return cur.rowcount

    def reset_undated_videos(self) -> int:
        """Metadata version 5: videos without a date are read again, now from their own header. Placeholders are kept."""
        cur = self.db.execute("UPDATE files SET meta_version=0, meta_tries=0 WHERE deleted=0 AND mime LIKE 'video/%' AND taken_utc IS NULL")
        self.db.commit()
        return cur.rowcount

    def count_stuck(self, max_tries: int = 3) -> tuple[int, int]:
        """(files waiting for metadata, files that gave up after max_tries)"""
        waiting = self.db.execute("SELECT COUNT(*) FROM files WHERE deleted=0 AND level=0 AND meta_tries<?", (max_tries,)).fetchone()[0]
        gave_up = self.db.execute("SELECT COUNT(*) FROM files WHERE deleted=0 AND level=0 AND meta_tries>=?", (max_tries,)).fetchone()[0]
        return waiting, gave_up

    def bump_meta_try(self, file_id: int) -> None:
        self.db.execute("UPDATE files SET meta_tries=meta_tries+1 WHERE file_id=?", (file_id,))
        self.db.commit()

    def pending_previews(self, tiff_last: bool, max_tries: int = 3) -> list[sqlite3.Row]:
        rows = self.db.execute("SELECT * FROM files WHERE deleted=0 AND level>=1 AND thumbhash IS NULL AND preview_tries<? ORDER BY file_id", (max_tries,)).fetchall()
        def key(r: sqlite3.Row) -> tuple:
            is_tiff = r["mime"] in ("image/tiff", "image/x-tiff") or r["path"].lower().endswith((".tif", ".tiff"))
            return (1 if (tiff_last and is_tiff) else 0, 1 if r["mime"].startswith("video/") else 0, r["file_id"])
        return sorted(rows, key=key)

    def set_thumbhash(self, file_id: int, blob: bytes) -> None:
        self.db.execute("UPDATE files SET thumbhash=? WHERE file_id=?", (blob, file_id))
        self.db.commit()

    def bump_preview_try(self, file_id: int) -> None:
        self.db.execute("UPDATE files SET preview_tries=preview_tries+1 WHERE file_id=?", (file_id,))
        self.db.commit()

    # ---- content (Phase 4) ----
    def pending_content(self, model: str) -> list[sqlite3.Row]:
        """Live files without a result for their current version and model ('no_picture' is final until the file changes; a network error saves
        nothing, so the file is simply tried again next run). TIFFs (scans) and videos come last."""
        rows = self.db.execute(
            "SELECT f.* FROM files f LEFT JOIN content c ON c.file_id=f.file_id WHERE f.deleted=0 AND f.level>=1 AND "
            "(c.file_id IS NULL OR c.etag<>f.etag OR (c.status='ok' AND c.model<>?)) ORDER BY f.file_id", (model,)).fetchall()
        def key(r: sqlite3.Row) -> tuple:
            is_tiff = r["mime"] in ("image/tiff", "image/x-tiff") or r["path"].lower().endswith((".tif", ".tiff"))
            return (1 if is_tiff else 0, 1 if r["mime"].startswith("video/") else 0, r["file_id"])
        return sorted(rows, key=key)

    def save_content(self, file_id: int, etag: str, status: str, source: str | None, model: str | None, vec: bytes | None, sharp: float | None) -> None:
        self.db.execute("INSERT OR REPLACE INTO content(file_id, etag, status, source, model, vec, sharp, tries, made) VALUES (?,?,?,?,?,?,?,0,?)",
                        (file_id, etag, status, source, model, vec, sharp, time.time()))
        self.db.commit()

    def content_counts(self) -> dict[str, int]:
        return {r[0]: r[1] for r in self.db.execute("SELECT status, COUNT(*) FROM content c JOIN files f ON f.file_id=c.file_id WHERE f.deleted=0 AND c.etag=f.etag GROUP BY status")}

    # ---- faces (Phase 4b) ----
    def pending_faces(self, model: str) -> list[sqlite3.Row]:
        """Live files with a picture (content ok, not a TIFF scan, not a screenshot) whose faces were not read for this version and model."""
        rows = self.db.execute(
            "SELECT f.*, c.source AS csource FROM files f JOIN content c ON c.file_id=f.file_id AND c.etag=f.etag AND c.status='ok' "
            "LEFT JOIN faces_done d ON d.file_id=f.file_id WHERE f.deleted=0 AND f.level>=1 AND c.source<>'preview256' AND (f.flags & 2)=0 "
            "AND (d.file_id IS NULL OR d.etag<>f.etag OR d.model<>?) ORDER BY f.file_id", (model,)).fetchall()
        return rows

    def save_faces(self, file_id: int, etag: str, model: str, faces: list[tuple]) -> None:
        """faces: (x1, y1, x2, y2, score, width_1280, usable, emb_bytes). Replaces what was stored for the file (a changed file is read again)."""
        self.db.execute("DELETE FROM faces WHERE file_id=?", (file_id,))
        self.db.executemany("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb) VALUES (?,?,?,?,?,?,?,?,?)",
                            [(file_id, *f) for f in faces])
        self.db.execute("INSERT OR REPLACE INTO faces_done(file_id, etag, model, n_faces, made) VALUES (?,?,?,?,?)", (file_id, etag, model, len(faces), time.time()))
        self.db.commit()

    def live_rows(self) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM files WHERE deleted=0 AND level>=1 ORDER BY file_id").fetchall()
