"""A private review page for the tag labels (design D7): for every label, example pictures that pass the threshold and a few just below it, so that
Chris can say which labels work. The page holds REAL photos and therefore lives only in the private state folder, never in Git or Nextcloud."""
from __future__ import annotations

import html
import json
from pathlib import Path

import numpy as np
from PIL import Image

from . import content, labels
from .state import State

THUMB = 220


def load_vectors(state: State) -> tuple[list[int], np.ndarray]:
    rows = state.db.execute("SELECT c.file_id, c.vec FROM content c JOIN files f ON f.file_id=c.file_id AND f.etag=c.etag "
                            "WHERE c.status='ok' AND c.model=? AND f.deleted=0 ORDER BY c.file_id", (content.MODEL_NAME,)).fetchall()
    ids = [r[0] for r in rows]
    return ids, (np.stack([content.from_f16(r[1]) for r in rows]) if rows else np.zeros((0, content.DIM), np.float32))


def suggest_thresholds(scores: np.ndarray, floor: float = 0.19, k: float = 2.5, ceil: float = 0.32) -> np.ndarray:
    """First guess per label from the sample itself: mean + k standard deviations of that label's scores, between `floor` and `ceil`
    (real scores of this model rarely pass 0.3). Labels are not equally easy for the model, so one global number does not work."""
    return np.clip(scores.mean(axis=0) + k * scores.std(axis=0), floor, ceil).astype(np.float32)


def _thumb(cache_dir: Path, out_dir: Path, file_id: int) -> str | None:
    dst = out_dir / "img" / f"{file_id}.jpg"
    if not dst.exists():
        src = next(iter(sorted(cache_dir.glob(f"{file_id}-*-*.jpg"))), None)
        if src is None:
            return None
        im = Image.open(src).convert("RGB")
        im.thumbnail((THUMB, THUMB))
        dst.parent.mkdir(parents=True, exist_ok=True)
        im.save(dst, "JPEG", quality=80)
    return f"img/{file_id}.jpg"


def write_review(state: State, cache_dir: Path, out_dir: Path, text_encoder, thresholds_file: Path | None = None, per_label: int = 12, below: int = 6,
                 keep: dict[str, float] | None = None) -> dict:
    """keep: round 2 after Chris's feedback: tighten those labels (labels.tighten), show only them, and write agreed_thresholds.json next to the page."""
    ids, V = load_vectors(state)
    if not len(ids):
        raise RuntimeError("no vectors yet: run `content` first")
    L = labels.label_vectors(text_encoder)
    S = V @ L.T
    th = suggest_thresholds(S)
    if keep:
        th = labels.tighten(S, th, keep)
    elif thresholds_file and thresholds_file.exists():                   # thresholds Chris and I already agreed on win over the first guess
        saved = json.loads(thresholds_file.read_text(encoding="utf-8"))
        for i, lab in enumerate(labels.LABELS):
            if lab[0] in saved:
                th[i] = saved[lab[0]]
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(7)
    parts = ["<!doctype html><meta charset=utf-8><title>Tag labels review</title><style>body{font:14px sans-serif;margin:16px;background:#fafafa}"
             "h2{margin:28px 0 4px}.row{display:flex;flex-wrap:wrap;gap:4px}.c{width:220px;position:relative}.c img{width:220px;height:165px;object-fit:cover;display:block}"
             ".c span{position:absolute;left:0;bottom:0;background:#000a;color:#fff;font-size:11px;padding:1px 4px}.b img{outline:3px solid #e90}"
             "small{color:#555}</style><h1>Tag labels: examples</h1><p>Private page with real photos. For each label: the pictures that PASS (top, random pick) "
             "and a few just BELOW the threshold (orange frame). Tell me per label: good / too many wrong / too strict / drop it.</p>"]
    summary = {}
    for i, lab in enumerate(labels.LABELS):
        if keep and lab[0] not in keep:
            continue
        col = S[:, i]
        passing = np.flatnonzero(col >= th[i])
        pick = rng.choice(passing, min(per_label, len(passing)), replace=False) if len(passing) else []
        near = np.flatnonzero(col < th[i])
        near = near[np.argsort(-col[near])][:below]
        summary[lab[0]] = {"threshold": round(float(th[i]), 3), "tagged": int(len(passing)), "of": len(ids)}
        parts.append(f"<h2>{html.escape(lab[1])} / {html.escape(lab[2])} <small>({lab[0]}) threshold {th[i]:.3f}, tagged {len(passing)} of {len(ids)}</small></h2><div class=row>")
        for j, cls in ([(j, "") for j in pick] + [(j, "b") for j in near]):
            t = _thumb(cache_dir, out_dir, ids[j])
            if t:
                parts.append(f"<div class='c {cls}'><img src='{t}' loading=lazy><span>{col[j]:.3f}</span></div>")
        parts.append("</div>")
    (out_dir / "index.html").write_text("".join(parts), encoding="utf-8")
    if keep:
        (out_dir / "agreed_thresholds.json").write_text(json.dumps({l[0]: round(float(th[i]), 4) for i, l in enumerate(labels.LABELS)}, indent=1), encoding="utf-8")
    (out_dir / "suggested_thresholds.json").write_text(json.dumps({l[0]: round(float(th[i]), 4) for i, l in enumerate(labels.LABELS)}, indent=1), encoding="utf-8")
    return summary
