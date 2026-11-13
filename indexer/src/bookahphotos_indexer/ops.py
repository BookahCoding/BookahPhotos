"""Ops: Chris's corrections, written by the app (Phase 5) as JSON lines into BookahPhotos/ops/<device>/ and applied by the indexer. Format: docs/ops-format.md.

Key ideas (they make a rebuild of the private database safe):
* Every op has a unique op_id: applying it twice changes nothing.
* An op never relies on a person id alone. Person ids come from clustering and are different after a rebuild, so person-level ops carry ANCHORS: a few
  faces of that person as {file_id, box}. Faces are found by overlap (IoU >= 0.5 in the same file), never by an exact box.
* The record of what was applied is the set of files in BookahPhotos/ops/applied/ (not the private database), so a rebuild replays them in order.
* A manual choice wins: a face that was moved, rejected or merged is marked manual and automatic grouping never moves it again."""
from __future__ import annotations

import json
import logging
import time
from collections import Counter
from dataclasses import dataclass, field

from .dav import Dav, DavError, ListingError
from .state import State

log = logging.getLogger("bookahphotos.ops")
OPS_DIR = "BookahPhotos/ops"
APPLIED_DIR = OPS_DIR + "/applied"
IOU_MIN = 0.5
TYPES = {"name_person", "merge_people", "move_face", "not_a_person", "hide_person", "unhide_person", "set_cover", "hide_photo", "unhide_photo", "unwanted_memory", "wanted_memory", "new_person"}


# ---- faces and people by reference ----
def iou(a: list[float], b: tuple[float, float, float, float]) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def find_face(state: State, ref: dict) -> int | None:
    """face_id of the face in file_id whose box overlaps ref['box'] best (IoU >= 0.5), or None."""
    try:
        box = [float(v) for v in ref["box"]]
        rows = state.db.execute("SELECT face_id, x1, y1, x2, y2 FROM faces WHERE file_id=?", (int(ref["file_id"]),)).fetchall()
    except (KeyError, TypeError, ValueError):
        return None
    best, best_iou = None, IOU_MIN
    for r in rows:
        v = iou(box, (r["x1"], r["y1"], r["x2"], r["y2"]))
        if v >= best_iou:
            best, best_iou = r["face_id"], v
    return best


def _chain(state: State, pid: int) -> int:
    for _ in range(50):
        r = state.db.execute("SELECT merged_into FROM persons WHERE person_id=?", (pid,)).fetchone()
        if r is None or r[0] is None:
            return pid
        pid = r[0]
    return pid


def _exists(state: State, pid: int) -> bool:
    return state.db.execute("SELECT 1 FROM persons WHERE person_id=?", (pid,)).fetchone() is not None


def _renumber(state: State, old: int, new: int) -> None:
    state.db.execute("UPDATE persons SET person_id=? WHERE person_id=?", (new, old))
    state.db.execute("UPDATE faces SET person_id=? WHERE person_id=?", (new, old))
    state.db.execute("UPDATE persons SET merged_into=? WHERE merged_into=?", (new, old))
    state.db.commit()


def _swap(state: State, a: int, b: int) -> None:
    """Exchange the ids of two existing people (after a rebuild every id is taken, so a person cannot simply take its old id back)."""
    t = -1
    for src, dst in ((a, t), (b, a), (t, b)):
        state.db.execute("UPDATE persons SET person_id=? WHERE person_id=?", (dst, src))
        state.db.execute("UPDATE faces SET person_id=? WHERE person_id=?", (dst, src))
        state.db.execute("UPDATE persons SET merged_into=? WHERE merged_into=?", (dst, src))
    state.db.commit()


def resolve_person(state: State, ref: dict | None) -> int | None:
    """The person an op means: by id when the anchors agree with it, otherwise by the anchors (then the matched person takes the op's id back
    when that id is free, so people Chris touched keep their ids across a rebuild). None when it cannot be told."""
    if not ref:
        return None
    pid = ref.get("person")
    votes: Counter = Counter()
    resolvable = 0
    for a in ref.get("anchors") or []:
        fid = find_face(state, a)
        if fid is None:
            continue
        resolvable += 1
        p = state.db.execute("SELECT person_id FROM faces WHERE face_id=?", (fid,)).fetchone()[0]
        if p is not None:
            votes[_chain(state, p)] += 1
    if isinstance(pid, int) and _exists(state, pid):
        c = _chain(state, pid)
        if resolvable == 0 or votes.get(c, 0) * 2 >= resolvable:
            return c
    if resolvable and votes:
        best, n = votes.most_common(1)[0]
        voted = sum(votes.values())
        # enough of the anchors agree. After a rebuild some anchor faces belong to nobody (groups form differently), so the share is counted among the anchors that
        # belong to somebody, as long as at least THREE of them agree (two faces that moved could point at the wrong person: a name or a join must never land on
        # somebody else; a lone anchor is accepted only when it is the only one)
        if n * 2 >= resolvable or (n >= 3 and n * 2 > voted) or (resolvable == 1 and n == 1):
            if isinstance(pid, int) and pid != best:
                if _exists(state, pid):
                    _swap(state, best, pid)
                else:
                    _renumber(state, best, pid)
                return pid
            return best
    return None


# ---- applying ----
@dataclass
class Op:
    op_id: str
    t: str
    type: str
    body: dict
    source: str = ""


def parse_ops(text: str, source: str = "") -> tuple[list[Op], int]:
    """(ops, number of lines that could not be read). One JSON object per line; unknown types are kept out and counted as bad."""
    ops, bad = [], 0
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
            if d.get("type") not in TYPES or not isinstance(d.get("op_id"), str) or not isinstance(d.get("t"), str):
                raise ValueError
            ops.append(Op(d["op_id"], d["t"], d["type"], d, source))
        except (ValueError, AttributeError):
            bad += 1
    return ops, bad


def _set_face(state: State, face_id: int, person: int | None, rejected: int = 0) -> None:
    state.db.execute("UPDATE faces SET person_id=?, assigned_by='manual', rejected=?, dist=NULL WHERE face_id=?", (person, rejected, face_id))


def apply_op(state: State, op: Op) -> str:
    """'applied', 'already' (seen before) or 'skipped: reason' (the target is not in this database; the op stays in the log)."""
    if state.db.execute("SELECT 1 FROM ops_seen WHERE op_id=?", (op.op_id,)).fetchone():
        return "already"
    b, result = op.body, "applied"
    try:
        if op.type == "name_person":
            p = resolve_person(state, {"person": b.get("person"), "anchors": b.get("anchors")})
            if p is None:
                result = "skipped: person not found"
            else:
                state.db.execute("UPDATE persons SET name=? WHERE person_id=?", (str(b.get("name", ""))[:100] or None, p))
        elif op.type in ("hide_person", "unhide_person"):
            p = resolve_person(state, {"person": b.get("person"), "anchors": b.get("anchors")})
            if p is None:
                result = "skipped: person not found"
            else:
                state.db.execute("UPDATE persons SET hidden=? WHERE person_id=?", (1 if op.type == "hide_person" else 0, p))
        elif op.type == "merge_people":
            a, into = resolve_person(state, b.get("from")), resolve_person(state, b.get("into"))
            if a is None or into is None:
                result = "skipped: person not found"
            elif a != into:
                state.db.execute("UPDATE faces SET person_id=?, assigned_by='manual' WHERE person_id=?", (into, a))
                state.db.execute("UPDATE persons SET merged_into=? WHERE person_id=?", (into, a))
                state.db.execute("UPDATE persons SET name=COALESCE(name, (SELECT name FROM persons WHERE person_id=?)) WHERE person_id=?", (a, into))
        elif op.type == "move_face":
            f = find_face(state, b.get("face") or {})
            to = b.get("to")
            p = resolve_person(state, to) if to else None
            if f is None:
                result = "skipped: face not found"
            elif to and p is None:
                result = "skipped: person not found"
            else:
                _set_face(state, f, p)
        elif op.type == "new_person":
            # "Who is this?": faces of nobody are named as a person that did not exist (the faces become that person's, marked manual; later faces can join)
            ids = [fid for fid in (find_face(state, ref) for ref in (b.get("faces") or []) if isinstance(ref, dict)) if fid is not None]
            if not ids:
                result = "skipped: faces not found"
            else:
                cur = state.db.execute("INSERT INTO persons(name, created) VALUES (?,?)", (str(b.get("name", "")).strip()[:100] or None, time.time()))
                for fid in ids:
                    _set_face(state, fid, cur.lastrowid)
        elif op.type == "not_a_person":
            f = find_face(state, b.get("face") or {})
            if f is None:
                result = "skipped: face not found"
            else:
                _set_face(state, f, None, rejected=1)
        elif op.type == "set_cover":
            p = resolve_person(state, {"person": b.get("person"), "anchors": b.get("anchors")})
            f = find_face(state, b.get("face") or {})
            if p is None or f is None:
                result = "skipped: person or face not found"
            else:
                state.db.execute("UPDATE persons SET cover_face_id=? WHERE person_id=?", (f, p))
        elif op.type in ("hide_photo", "unhide_photo"):
            fid = int(b["file_id"])
            if op.type == "hide_photo":
                state.db.execute("INSERT OR IGNORE INTO hidden_files(file_id) VALUES (?)", (fid,))
            else:
                state.db.execute("DELETE FROM hidden_files WHERE file_id=?", (fid,))
        elif op.type == "unwanted_memory":
            state.db.execute("INSERT OR IGNORE INTO unwanted_memories(memory_key) VALUES (?)", (str(b["key"]),))
        elif op.type == "wanted_memory":        # the way back (Undo): the memory may be made again
            state.db.execute("DELETE FROM unwanted_memories WHERE memory_key=?", (str(b["key"]),))
    except (KeyError, TypeError, ValueError):
        result = "skipped: malformed"
    if result == "applied":     # skipped ops are NOT marked as seen: they may become applicable after a later grouping run
        state.db.execute("INSERT OR IGNORE INTO ops_seen(op_id, applied, result) VALUES (?,?,?)", (op.op_id, time.time(), result))
    state.db.commit()
    return result


def apply_ops(state: State, ops: list[Op]) -> dict[str, int]:
    """In order of (time, op_id). Returns counts of applied / already / skipped."""
    counts: Counter = Counter()
    for op in sorted(ops, key=lambda o: (o.t, o.op_id)):
        r = apply_op(state, op)
        counts[r.split(":")[0]] += 1
    return dict(counts)


def read_local_file(path) -> tuple[list[Op], int]:
    """Ops from a local .jsonl file (for example corrections made from a review page, kept in the private state folder)."""
    from pathlib import Path
    return parse_ops(Path(path).read_text(encoding="utf-8"), str(path))


# ---- reading and archiving the op files in Nextcloud ----
@dataclass
class OpFiles:
    ops: list[Op] = field(default_factory=list)
    pending: list[tuple[str, str]] = field(default_factory=list)       # (file path, device) of files not yet moved to applied/
    bad_lines: int = 0
    unreadable: list[str] = field(default_factory=list)


def _list(dav: Dav, path: str):
    try:
        return dav.list_folder(path)[1]
    except (ListingError, DavError):
        return []


def collect(dav: Dav, include_applied: bool = False) -> OpFiles:
    """Read the op files. A missing ops/ folder means no ops (and is never created here)."""
    out = OpFiles()
    for dev in _list(dav, OPS_DIR):
        if not dev.is_dir or dev.path.rstrip("/").endswith("/applied"):
            continue
        name = dev.path.rstrip("/").split("/")[-1]
        for f in _list(dav, dev.path):
            if f.is_dir or not f.path.endswith(".jsonl"):
                continue
            try:
                ops, bad = parse_ops(dav.get(f.path).decode("utf-8"), f.path)
            except (DavError, UnicodeDecodeError):
                out.unreadable.append(f.path)
                continue
            out.ops += ops
            out.bad_lines += bad
            out.pending.append((f.path, name))
    if include_applied:
        for dev in _list(dav, APPLIED_DIR):
            if not dev.is_dir:
                continue
            for f in _list(dav, dev.path):
                if f.is_dir or not f.path.endswith(".jsonl"):
                    continue
                try:
                    ops, bad = parse_ops(dav.get(f.path).decode("utf-8"), f.path)
                except (DavError, UnicodeDecodeError):
                    out.unreadable.append(f.path)
                    continue
                out.ops += ops
                out.bad_lines += bad
    return out


def sync(dav: Dav, state: State, replay: bool = False, move: bool = True) -> dict:
    """Apply new ops (and, with replay=True, the already archived ones too: after a rebuild of the private database), then move the new op files
    to ops/applied/<device>/. A file is moved only when every op in it was applied or already known; a file with skipped ops stays where it is."""
    files = collect(dav, include_applied=replay)
    counts = apply_ops(state, files.ops)
    moved = 0
    by_file: dict[str, list[Op]] = {}
    for op in files.ops:
        by_file.setdefault(op.source, []).append(op)
    for path, dev in (files.pending if move else []):
        ops = by_file.get(path, [])
        if any(state.db.execute("SELECT 1 FROM ops_seen WHERE op_id=?", (o.op_id,)).fetchone() is None for o in ops):
            continue                                                   # something in it is not applied yet: keep the file
        try:
            if not any(e.path.rstrip("/").endswith("/" + dev) for e in _list(dav, APPLIED_DIR)):
                try:
                    dav.mkcol(APPLIED_DIR)
                except DavError:
                    pass
                dav.mkcol(f"{APPLIED_DIR}/{dev}")
            dav.move(path, f"{APPLIED_DIR}/{dev}/{path.rsplit('/', 1)[-1]}")
            moved += 1
        except DavError:
            log.warning("could not move an op file to applied/")
    return {"counts": counts, "files_moved": moved, "bad_lines": files.bad_lines, "unreadable": len(files.unreadable)}
