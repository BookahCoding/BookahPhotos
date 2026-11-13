"""Grouping faces into people, with stable person ids (design 3.3).

Rules, in this order, on every run:
1. Faces Chris fixed by hand (assigned_by='manual') and faces marked 'not a person' are never touched.
2. Each new usable face goes to the nearest existing person when it is close enough (distance < ASSIGN_DIST) AND clearly closer than to the second-nearest
   person (margin >= ASSIGN_MARGIN). A face that is in between stays unassigned: a wrong "this is mum" is worse than a missing one.
3. The CLEAR faces (score and size above CORE_*) still unassigned are grouped with density clustering (DBSCAN, cosine, EPS, MIN_SAMPLES); each group
   becomes a NEW person with a new id. Then step 2 runs once more so that the less clear faces can join the new people.
Existing groups are never renumbered or re-clustered. Input is sorted (file id, box) so the result is the same on every run.
The numbers are a first proposal and are tuned on the real library (see the 4b report)."""
from __future__ import annotations

import time
from dataclasses import dataclass, field

import numpy as np

from .state import State

EPS = 0.3                # cosine distance for the density clustering. Phase 0 used 0.4 on 1,537 photos; on the full library (24,000 photos) 0.4 chained
MIN_SAMPLES = 5          # 75% of all faces into one group, so it was tightened after a sweep (4b report). Chaining check: top_group_share.
CORE_SCORE = 0.8         # only clear faces (sharp enough, big enough) are used to FORM new people; all usable faces can JOIN them afterwards
CORE_WIDTH = 60.0
ASSIGN_DIST = 0.35
ASSIGN_MARGIN = 0.05


@dataclass
class GroupingResult:
    assigned_to_existing: int = 0          # net change: faces that now belong to a (new or different) person by joining
    removed_joins: int = 0                 # earlier joins taken back because they no longer qualify
    new_persons: int = 0
    clustered_faces: int = 0
    left_unassigned: int = 0
    pool: int = 0
    top_group_share: float = 0.0          # share of all grouped faces in the biggest person: near 1.0 means people were chained together
    sizes: list[int] = field(default_factory=list)


def _emb(blob: bytes) -> np.ndarray:
    return np.frombuffer(blob, np.float32)


def person_centres(state: State) -> tuple[list[int], np.ndarray]:
    """Unit-length mean embedding of the faces of every live (not merged away) person."""
    # only the faces that FORMED a group (and faces fixed by hand) define where a person is. Faces that joined later must not move the centre, otherwise every
    # run would let a few more faces in and the groups would slowly creep towards each other (and a second run would never change nothing).
    rows = state.db.execute("SELECT p.person_id, f.emb FROM faces f JOIN persons p ON p.person_id=f.person_id "
                            "WHERE p.merged_into IS NULL AND f.rejected=0 AND f.usable=1 AND (f.founder=1 OR f.assigned_by='manual') ORDER BY p.person_id, f.face_id").fetchall()
    sums: dict[int, np.ndarray] = {}
    for pid, blob in rows:
        sums[pid] = sums.get(pid, 0) + _emb(blob)
    ids = sorted(sums)
    if not ids:
        return [], np.zeros((0, 128), np.float32)
    m = np.stack([sums[i] for i in ids]).astype(np.float32)
    return ids, m / np.maximum(np.linalg.norm(m, axis=1, keepdims=True), 1e-12)


def pool_faces(state: State, core_only: bool = False) -> list:
    """Usable, automatic, not rejected faces without a person, in a fixed order. core_only: only the clear faces that may form new people."""
    extra = f" AND score>={CORE_SCORE} AND width_1280>={CORE_WIDTH}" if core_only else ""
    return state.db.execute("SELECT face_id, file_id, x1, y1, emb FROM faces WHERE usable=1 AND rejected=0 AND person_id IS NULL AND assigned_by='auto'"
                            + extra + " ORDER BY file_id, x1, y1, face_id").fetchall()


def reset_automatic(state: State) -> None:
    """Forget the automatic grouping (people and assignments) so it can be made again with other settings. Refuses when Chris's manual choices exist:
    they would be lost (replay the ops after a reset only on a database rebuilt from scratch)."""
    manual = state.db.execute("SELECT COUNT(*) FROM faces WHERE assigned_by='manual'").fetchone()[0]
    named = state.db.execute("SELECT COUNT(*) FROM persons WHERE name IS NOT NULL OR hidden=1 OR merged_into IS NOT NULL").fetchone()[0]
    if manual or named:
        raise RuntimeError("manual choices exist; refusing to reset the grouping")
    state.db.execute("UPDATE faces SET person_id=NULL, dist=NULL, founder=0")
    state.db.execute("DELETE FROM persons")
    state.db.execute("DELETE FROM sqlite_sequence WHERE name='persons'")
    state.db.commit()


def assign_to_existing(state: State, assign_dist: float = ASSIGN_DIST, margin: float = ASSIGN_MARGIN) -> int:
    ids, C = person_centres(state)
    pool = pool_faces(state)
    if not ids or not pool:
        return 0
    E = np.stack([_emb(r["emb"]) for r in pool])
    D = 1.0 - E @ C.T                                       # cosine distance to every person
    order = np.argsort(D, axis=1)
    n = 0
    for k, r in enumerate(pool):
        best = order[k, 0]
        d1 = D[k, best]
        d2 = D[k, order[k, 1]] if len(ids) > 1 else 9.0
        if d1 < assign_dist and (d2 - d1) >= margin:
            state.db.execute("UPDATE faces SET person_id=?, dist=? WHERE face_id=?", (ids[best], float(d1), r["face_id"]))
            n += 1
    state.db.commit()
    return n


def cluster_pool(state: State, eps: float = EPS, min_samples: int = MIN_SAMPLES) -> tuple[int, int]:
    """DBSCAN over the unassigned pool; every cluster becomes a new person. Returns (new persons, faces grouped)."""
    from sklearn.cluster import DBSCAN
    pool = pool_faces(state, core_only=True)
    if len(pool) < min_samples:
        return 0, 0
    E = np.stack([_emb(r["emb"]) for r in pool])
    labels = DBSCAN(eps=eps, min_samples=min_samples, metric="cosine", n_jobs=1).fit_predict(E)
    made = grouped = 0
    for lab in sorted(set(labels.tolist()) - {-1}, key=lambda l: int(np.flatnonzero(labels == l)[0])):     # numbered by first face in the fixed order
        members = np.flatnonzero(labels == lab)
        cur = state.db.execute("INSERT INTO persons(created) VALUES (?)", (time.time(),))
        pid = int(cur.lastrowid)
        centre = E[members].mean(axis=0)
        centre /= max(float(np.linalg.norm(centre)), 1e-12)
        for m in members:
            state.db.execute("UPDATE faces SET person_id=?, dist=?, founder=1 WHERE face_id=?", (pid, float(1.0 - E[m] @ centre), pool[m]["face_id"]))
        made += 1
        grouped += len(members)
    state.db.commit()
    return made, grouped


def group(state: State) -> GroupingResult:
    res = GroupingResult()
    # Faces that only JOINED a person are decided again from scratch every run against the fixed centres (founders and manual faces), so the result is the
    # same on every run and an earlier too-generous join is corrected. Founders, manual faces and rejected faces are never touched.
    joiners = "founder=0 AND assigned_by='auto'"
    before = {r[0]: r[1] for r in state.db.execute(f"SELECT face_id, person_id FROM faces WHERE {joiners} AND person_id IS NOT NULL")}
    state.db.execute(f"UPDATE faces SET person_id=NULL, dist=NULL WHERE {joiners} AND person_id IS NOT NULL")
    state.db.commit()
    assign_to_existing(state)
    res.new_persons, res.clustered_faces = cluster_pool(state)
    assign_to_existing(state)                                        # the less clear faces join the people just formed
    after = {r[0]: r[1] for r in state.db.execute(f"SELECT face_id, person_id FROM faces WHERE {joiners} AND person_id IS NOT NULL")}
    res.assigned_to_existing = sum(1 for f, p in after.items() if before.get(f) != p)       # the net change: faces whose person is new or different
    res.removed_joins = sum(1 for f in before if f not in after)                               # earlier joins that no longer qualify
    res.pool = len(pool_faces(state))
    res.left_unassigned = res.pool
    sizes = [r[0] for r in state.db.execute("SELECT COUNT(*) FROM faces f JOIN persons p ON p.person_id=f.person_id WHERE p.merged_into IS NULL AND f.rejected=0 "
                                            "GROUP BY f.person_id ORDER BY 1 DESC")]
    res.sizes = sizes[:10]
    total = sum(sizes)
    res.top_group_share = (sizes[0] / total) if total else 0.0
    return res
