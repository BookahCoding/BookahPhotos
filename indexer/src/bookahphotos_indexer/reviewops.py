"""Turns the results file of the people review page into ops (docs/ops-format.md): a name on a group -> name_person; the same name on several groups ->
merge_people into the biggest; a face clicked as 'different person' -> move_face to nobody (it stays out of the group and out of automatic grouping).
Names starting with 'Unknown' are ignored. The output holds NAMES: keep it in the private state folder only."""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timedelta, timezone

from .state import State

N_ANCHORS = 8


def _ref(state: State, face_id: int) -> dict:
    r = state.db.execute("SELECT file_id, x1, y1, x2, y2 FROM faces WHERE face_id=?", (face_id,)).fetchone()
    return {"file_id": r["file_id"], "box": [round(r["x1"], 4), round(r["y1"], 4), round(r["x2"], 4), round(r["y2"], 4)]}


def person_ref(state: State, pid: int) -> dict:
    """The person with its best (clearest) faces as anchors."""
    rows = state.db.execute("SELECT face_id FROM faces WHERE person_id=? AND rejected=0 ORDER BY score*width_1280 DESC, face_id LIMIT ?", (pid, N_ANCHORS)).fetchall()
    return {"person": pid, "anchors": [_ref(state, r[0]) for r in rows]}


def results_to_ops(state: State, results: dict, start: datetime | None = None, device: str = "review-page") -> list[dict]:
    t0 = start or datetime.now(timezone.utc)
    clock = [0]

    def stamp() -> str:
        clock[0] += 1
        return (t0 + timedelta(seconds=clock[0])).strftime("%Y-%m-%dT%H:%M:%SZ")

    def base(typ: str, **kw) -> dict:
        return {"op_id": str(uuid.uuid4()), "t": stamp(), "type": typ, "device": device, "v": 1, **kw}

    names = {int(k.split("|", 1)[1]): v.strip() for k, v in results.items()
             if k.startswith("name|") and isinstance(v, str) and v.strip() and not v.strip().lower().startswith("unknown")}
    out: list[dict] = []
    # 1. faces clicked as a different person leave their group first (they would otherwise colour the anchors)
    for k, v in results.items():
        if v is True and "|" in k and not k.startswith("name|"):
            pid, fid = (int(x) for x in k.split("|"))
            if state.db.execute("SELECT 1 FROM faces WHERE face_id=? AND person_id=?", (fid, pid)).fetchone():
                out.append(base("move_face", face=_ref(state, fid), to=None))
    # 2. names
    size = {r[0]: r[1] for r in state.db.execute("SELECT person_id, COUNT(*) FROM faces WHERE person_id IS NOT NULL AND rejected=0 GROUP BY person_id")}
    for pid, name in sorted(names.items()):
        if pid in size:
            out.append(base("name_person", name=name, **person_ref(state, pid)))
    # 3. the same name on several groups: one person (merge into the biggest group)
    by_name: dict[str, list[int]] = {}
    for pid, name in names.items():
        if pid in size:
            by_name.setdefault(name.casefold(), []).append(pid)
    for pids in by_name.values():
        if len(pids) > 1:
            pids.sort(key=lambda p: (-size[p], p))
            for other in pids[1:]:
                out.append(base("merge_people", **{"from": person_ref(state, other), "into": person_ref(state, pids[0])}))
    return out


def write_ops_file(ops: list[dict], path) -> None:
    from pathlib import Path
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(json.dumps(o, ensure_ascii=False) for o in ops) + "\n", encoding="utf-8")
