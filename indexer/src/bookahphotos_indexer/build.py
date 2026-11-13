"""Building a snapshot file from the indexer's private state (schema 1.3: metadata plus content vectors, tags, faces, people, memories, the list of applied corrections and the hidden photos)."""
from __future__ import annotations

import hashlib
import sqlite3
from datetime import date, datetime, timezone
from pathlib import Path

from . import schema
from .config import Config
from .places import PlaceIndex
from .snapshot import finalize_snapshot
from .state import State

GEONAMES_NOTE = "Place names from GeoNames (https://www.geonames.org/), Creative Commons Attribution 4.0"
SEARCH_MODEL = "xlmr-vit-b32-laion5b"      # same strings as content.MODEL_NAME and faces.FACE_MODEL (not imported: they need numpy)
FACE_MODEL = "yunet+sface"
MIN_FACES_FOR_PERSON = 5                   # design D6: a person appears in the app after this many faces
BUILD_VERSION = 1                          # bump when a rule that shapes the snapshot changes (so the fingerprint changes)
_ASSET_COLUMNS = ["id", "path", "etag", "size", "mime_type", "taken_utc", "taken_offset_min", "taken_zone_assumed", "taken_source", "day_key", "width", "height",
                  "duration_ms", "latitude", "longitude", "place_id", "camera", "lens", "exposure", "flags", "content_hash", "thumbhash", "duplicate_group", "indexed_level"]


def snapshot_rows(state: State) -> list:
    """Every live file at level 1 or above, except photos Chris hid with an op (they stay in Nextcloud; they are just not shown)."""
    hidden = {r[0] for r in state.db.execute("SELECT file_id FROM hidden_files")}
    return [r for r in state.live_rows() if r["file_id"] not in hidden]


def hidden_rows(state: State) -> list:
    """The photos hidden with an op, with the same filters as the visible ones (live files with metadata)."""
    hidden = {r[0] for r in state.db.execute("SELECT file_id FROM hidden_files")}
    return [r for r in state.live_rows() if r["file_id"] in hidden]


UNCOMPRESSED_MIME = {"image/tiff", "image/bmp", "image/x-ms-bmp", "image/x-tga"}


def derived(rows) -> dict[int, tuple[int, int | None]]:
    """What is worked out from the rows themselves, for the snapshot: file_id -> (extra flag bits, duplicate group).
    - Selfie (flag 4): the lens name says front camera (or selfie). Only what the file itself says; phones that do not write it are not guessed.
    - Motion photo (flag 16): a photo with a short video (5 seconds or less) of the same name in the same folder (iPhone Live Photos, Android motion photos), or an
      Android motion photo by name (MVIMG_..., ...MP.jpg).
    - Duplicate group: photos with exactly the same size, capture time, dimensions and type (the same picture saved twice, for example in two folders), compressed formats only: scanned TIFFs of the same dimensions always have the same size. The group
      number is the smallest file id of the group; a photo without a capture time or size is never grouped."""
    import re
    out: dict[int, tuple[int, int | None]] = {}
    by_stem: dict[tuple[str, str], list] = {}
    for r in rows:
        folder, _, name = r["path"].rpartition("/")
        by_stem.setdefault((folder, name.rsplit(".", 1)[0].lower()), []).append(r)
    short_video_stems = {k for k, v in by_stem.items() if any((x["mime"] or "").startswith("video/") and 0 < (x["duration_ms"] or 0) <= 5000 for x in v)}
    groups: dict[tuple, list[int]] = {}
    for r in rows:
        mime = r["mime"] or ""
        flags = 0
        if mime.startswith("image/"):
            if re.search(r"front|selfie", r["lens"] or "", re.I):
                flags |= schema.FLAG_SELFIE
            folder, _, name = r["path"].rpartition("/")
            if (folder, name.rsplit(".", 1)[0].lower()) in short_video_stems or re.match(r"MVIMG_", name, re.I) or re.search(r"\.MP\.jpe?g$|_MP\.jpe?g$", name, re.I):
                flags |= schema.FLAG_MOTION_PHOTO
            # not TIFF or BMP: an uncompressed picture has a size that depends only on its dimensions, so two different scans taken in the same second have the same
            # size, time and dimensions without being the same picture (scanned film frames); only compressed formats count as evidence
            if r["taken_utc"] is not None and r["width"] and r["size"] and r["size"] > 0 and mime not in UNCOMPRESSED_MIME:
                groups.setdefault((r["size"], r["taken_utc"], r["width"], r["height"], mime), []).append(r["file_id"])
        out[r["file_id"]] = (flags, None)
    for ids in groups.values():
        if len(ids) > 1:
            g = min(ids)
            for i in ids:
                out[i] = (out[i][0], g)
    return out


def _levels(state: State) -> dict[int, int]:
    """indexed_level per file: 1 metadata, 2 plus a content vector, 3 plus faces read."""
    out: dict[int, int] = {}
    for r in state.db.execute("SELECT f.file_id FROM files f JOIN content c ON c.file_id=f.file_id AND c.etag=f.etag AND c.status='ok' AND c.model=? WHERE f.deleted=0",
                              (SEARCH_MODEL,)):
        out[r[0]] = 2
    for r in state.db.execute("SELECT f.file_id FROM files f JOIN faces_done d ON d.file_id=f.file_id AND d.etag=f.etag AND d.model=? WHERE f.deleted=0", (FACE_MODEL,)):
        if r[0] in out:
            out[r[0]] = 3
    return out


def rows_digest(state: State) -> str:
    """A fingerprint of everything that would go into the snapshot, so an unchanged library does not produce a new generation."""
    h = hashlib.sha256()
    levels = _levels(state)
    rows = snapshot_rows(state)
    extra = derived(rows)
    for r in rows:
        # only the columns that end up in the snapshot; bookkeeping such as last_seen (the run number) changes every run
        h.update(repr(_snapshot_values(r, None, levels.get(r["file_id"], 1), extra.get(r["file_id"]))).encode("utf-8"))
    for r in state.db.execute("SELECT file_id, etag, model, vec FROM content WHERE status='ok' ORDER BY file_id"):
        h.update(repr((r[0], r[1], r[2])).encode("utf-8"))
        h.update(r[3])
    for r in state.db.execute("SELECT face_id, file_id, person_id, rejected, usable, ROUND(x1,4), ROUND(y1,4), ROUND(x2,4), ROUND(y2,4) FROM faces ORDER BY face_id"):
        h.update(repr(tuple(r)).encode("utf-8"))
    for r in state.db.execute("SELECT person_id, name, hidden, merged_into, cover_face_id FROM persons ORDER BY person_id"):
        h.update(repr(tuple(r)).encode("utf-8"))
    h.update(repr(sorted(r[0] for r in state.db.execute("SELECT op_id FROM ops_seen"))).encode("utf-8"))      # the app learns that its corrections arrived
    h.update(repr(sorted(r[0] for r in state.db.execute("SELECT file_id FROM hidden_files"))).encode("utf-8"))
    h.update(repr([_hidden_values(r) for r in hidden_rows(state)]).encode("utf-8"))
    h.update(repr(sorted(r[0] for r in state.db.execute("SELECT memory_key FROM unwanted_memories"))).encode("utf-8"))
    h.update(repr((state.kv_get("home"), date.today().strftime("%Y-%m"), BUILD_VERSION, schema.SCHEMA_MINOR)).encode("utf-8"))   # months close; rules change
    from . import labels                                                                                                 # (numpy is imported by labels)
    h.update(repr(labels.LABELS).encode("utf-8"))
    h.update(labels.thresholds_text().encode("utf-8"))
    return h.hexdigest()


def _hidden_values(r) -> tuple:
    return (r["file_id"], r["path"], r["etag"], r["size"], r["mime"], r["taken_utc"], r["day_key"], r["duration_ms"], r["flags"], r["thumbhash"])


def _snapshot_values(r, places: PlaceIndex | None, level: int | None = None, extra: tuple[int, int | None] | None = None) -> tuple:
    return (r["file_id"], r["path"], r["etag"], r["size"], r["mime"], r["taken_utc"], r["taken_offset_min"], r["taken_zone_assumed"], r["taken_source"], r["day_key"],
            r["width"], r["height"], r["duration_ms"], r["latitude"], r["longitude"],
            (r["place_id"] if (places is None or r["place_id"] in places.by_id) else None),
            r["camera"], r["lens"], r["exposure"], r["flags"] | (extra[0] if extra else 0), r["content_hash"], r["thumbhash"], (extra[1] if extra else r["duplicate_group"]), level if level is not None else r["level"])


def build_snapshot(state: State, cfg: Config, places: PlaceIndex | None, out: Path, generation: int, models: dict[str, str] | None = None,
                   label_cache: Path | None = None, text_encoder_factory=None) -> Path:
    out = Path(out)
    if out.exists():
        out.unlink()
    conn = sqlite3.connect(out)
    schema.create(conn)
    rows = snapshot_rows(state)
    in_snapshot = {r["file_id"] for r in rows}
    levels = _levels(state)
    extra = derived(rows)
    used = {r["place_id"] for r in rows if r["place_id"]}
    if places:
        conn.executemany("INSERT INTO places(id, name, name_el, region, country, latitude, longitude) VALUES (?,?,?,?,?,?,?)",
                         [(p.id, p.name, p.name_el, p.region, p.country, p.lat, p.lon) for p in (places.by_id[i] for i in sorted(used) if i in places.by_id)])
    conn.executemany(
        f"INSERT INTO assets({', '.join(_ASSET_COLUMNS)}) VALUES ({', '.join('?' * len(_ASSET_COLUMNS))})",
        [_snapshot_values(r, places, levels.get(r["file_id"], 1), extra.get(r["file_id"])) for r in rows])
    n_vec = _add_content(conn, state, in_snapshot, label_cache, text_encoder_factory)
    n_people, n_faces = _add_people(conn, state, in_snapshot)
    n_mem = _add_memories(conn, state, places, in_snapshot)
    conn.executemany("INSERT INTO hidden_assets(id, path, etag, size, mime_type, taken_utc, day_key, duration_ms, flags, thumbhash) VALUES (?,?,?,?,?,?,?,?,?,?)", [_hidden_values(r) for r in hidden_rows(state)])
    conn.executemany("INSERT INTO applied_ops(op_id) VALUES (?)", [(r[0],) for r in state.db.execute("SELECT op_id FROM ops_seen ORDER BY op_id")])
    schema.set_meta(conn, schema_major=schema.SCHEMA_MAJOR, schema_minor=schema.SCHEMA_MINOR, generation=generation,
                    created_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"), roots=",".join(cfg.roots), home_zone=cfg.home_zone,
                    models="; ".join(f"{k}={v}" for k, v in (models or {}).items()) or "none (metadata only)", attribution=GEONAMES_NOTE,
                    vectors=n_vec, people=n_people, faces=n_faces, memories=n_mem)
    conn.commit()
    finalize_snapshot(conn)
    conn.close()
    return out


def _add_content(conn: sqlite3.Connection, state: State, in_snapshot: set[int], label_cache: Path | None, text_encoder_factory) -> int:
    """asset_vectors (float16 as stored), the labels table and the tags (scores against the label vectors, thresholds agreed with Chris)."""
    from . import labels
    conn.executemany("INSERT INTO labels(key, name_en, name_el) VALUES (?,?,?)", [(l[0], l[1], l[2]) for l in labels.LABELS])
    rows = [r for r in state.db.execute(
        "SELECT c.file_id, c.vec FROM content c JOIN files f ON f.file_id=c.file_id AND f.etag=c.etag WHERE c.status='ok' AND c.model=? AND f.deleted=0 ORDER BY c.file_id",
        (SEARCH_MODEL,)) if r[0] in in_snapshot]
    if not rows:
        return 0
    conn.executemany("INSERT INTO asset_vectors(asset_id, model_id, dim, vec) VALUES (?,?,?,?)", [(r[0], SEARCH_MODEL, len(r[1]) // 2, r[1]) for r in rows])
    if label_cache is not None and (Path(label_cache).exists() or text_encoder_factory is not None):
        import numpy as np
        L = labels.cached_label_vectors(label_cache, text_encoder_factory, SEARCH_MODEL)
        V = np.stack([np.frombuffer(r[1], np.float16).astype(np.float32) for r in rows])
        tags = labels.assign_tags(V, L, labels.load_thresholds())
        conn.executemany("INSERT INTO tags(asset_id, label_key, score) VALUES (?,?,?)",
                         [(rows[i][0], labels.LABELS[j][0], s) for i, t in enumerate(tags) for j, s in t])
    return len(rows)


def _add_people(conn: sqlite3.Connection, state: State, in_snapshot: set[int]) -> tuple[int, int]:
    """people with at least MIN_FACES_FOR_PERSON faces; faces = every usable face (boxes only, never the face descriptions). Faces of people with too few faces,
    and unassigned faces, have person_id NULL (Phase 5 shows them as 'Who is this?')."""
    faces = [r for r in state.db.execute("SELECT face_id, file_id, x1, y1, x2, y2, score, width_1280, person_id FROM faces WHERE usable=1 AND rejected=0 ORDER BY face_id")
             if r["file_id"] in in_snapshot]
    count: dict[int, int] = {}
    best: dict[int, tuple[float, int]] = {}
    for f in faces:
        p = f["person_id"]
        if p is not None:
            count[p] = count.get(p, 0) + 1
            q = f["score"] * f["width_1280"]
            if p not in best or q > best[p][0]:
                best[p] = (q, f["face_id"])
    live = {r["person_id"]: r for r in state.db.execute("SELECT * FROM persons WHERE merged_into IS NULL")}
    keep = {p for p, n in count.items() if n >= MIN_FACES_FOR_PERSON and p in live}
    face_ids = {f["face_id"] for f in faces}
    people = []
    for p in sorted(keep):
        cover = live[p]["cover_face_id"] if live[p]["cover_face_id"] in face_ids else best[p][1]
        people.append((p, live[p]["name"], cover, live[p]["hidden"], count[p]))
    conn.executemany("INSERT INTO people(id, name, cover_face_id, hidden, face_count) VALUES (?,?,?,?,?)", people)
    conn.executemany("INSERT INTO faces(id, asset_id, x, y, w, h, person_id, quality) VALUES (?,?,?,?,?,?,?,?)",
                     [(f["face_id"], f["file_id"], f["x1"], f["y1"], f["x2"] - f["x1"], f["y2"] - f["y1"], f["person_id"] if f["person_id"] in keep else None, f["score"]) for f in faces])
    return len(people), len(faces)


def _add_memories(conn: sqlite3.Connection, state: State, places: PlaceIndex | None, in_snapshot: set[int]) -> int:
    from . import memories
    found, _home = memories.find_memories(state, places)
    for i, m in enumerate(found, 1):
        assets = [a for a in m.asset_ids if a in in_snapshot]
        if not assets:
            continue
        cover = m.cover_asset if m.cover_asset in in_snapshot else assets[0]
        conn.execute("INSERT INTO memories(id, key, type, title_en, title_el, start_day, end_day, cover_asset) VALUES (?,?,?,?,?,?,?,?)",
                     (i, m.key, m.type, m.title_en, m.title_el, m.start_day, m.end_day, cover))
        conn.executemany("INSERT INTO memory_assets(memory_id, asset_id, position) VALUES (?,?,?)", [(i, a, k) for k, a in enumerate(assets)])
    return len(found)
