"""A private review page for the memories (design D8): the home place, then every trip, month and person memory with its cover and a few pictures, and a
sample of the 'on this day' ones. Real photos: it lives in the state folder only."""
from __future__ import annotations

import html
from pathlib import Path

from .labelreview import _thumb
from .memories import Memory
from .places import PlaceIndex, haversine_km


def home_description(home: tuple[float, float] | None, places: PlaceIndex | None) -> str:
    if home is None:
        return "no home place could be found (no photos with a location)"
    name = ""
    if places is not None:
        best = None
        for p in places.by_id.values():
            if abs(p.lat - home[0]) < 1 and abs(p.lon - home[1]) < 1:
                d = haversine_km(home[0], home[1], p.lat, p.lon)
                if best is None or d < best[0]:
                    best = (d, p)
        if best:
            name = f"near {best[1].name} ({best[1].name_el or ''}), {best[0]:.0f} km from its centre; "
    return f"{name}coordinates {home[0]:.2f}, {home[1]:.2f}"


def write_review(memories: list[Memory], home: tuple[float, float] | None, places: PlaceIndex | None, cache_dir: Path, out_dir: Path, per_memory: int = 8) -> dict:
    out_dir.mkdir(parents=True, exist_ok=True)
    kinds = {k: [m for m in memories if m.type == k] for k in ("trip", "month", "person", "on_this_day")}
    parts = ["<!doctype html><meta charset=utf-8><title>Memories review</title><style>body{font:14px sans-serif;margin:16px;background:#fafafa}h2{margin:30px 0 6px}"
             ".m{margin:10px 0 16px}.row{display:flex;flex-wrap:wrap;gap:3px}.row img{width:150px;height:112px;object-fit:cover}.row img.cover{outline:3px solid #2a9d3a}"
             "small{color:#555}</style><h1>Memories found</h1>",
             f"<p>Private page with real photos. <b>Home place used:</b> {html.escape(home_description(home, places))}.<br>"
             "Trips are days more than 50 km from this place. If it is wrong, tell me the right place.</p>",
             "<p>" + ", ".join(f"{len(v)} {k.replace('_', ' ')}" for k, v in kinds.items()) + "</p>"]
    for kind, label, limit in (("trip", "Trips", None), ("month", "Months in review", None), ("person", "A person over the years", None), ("on_this_day", "On this day (a sample of the busiest)", 12)):
        items = kinds[kind]
        if kind == "on_this_day":
            items = sorted(items, key=lambda m: -m.total_photos)[:limit]
        parts.append(f"<h2>{label}</h2>")
        for m in items:
            thumbs = [m.cover_asset] + [a for a in m.asset_ids if a != m.cover_asset][: per_memory - 1]
            imgs = []
            for a in thumbs:
                t = _thumb(cache_dir, out_dir, a)
                if t:
                    imgs.append(f"<img src='{t}' loading=lazy{' class=cover' if a == m.cover_asset else ''}>")
            when = m.start_day if m.start_day == m.end_day else f"{m.start_day} to {m.end_day}"
            parts.append(f"<div class=m><b>{html.escape(m.title_en)}</b> / {html.escape(m.title_el)} <small>{when}, {m.total_photos} photos, key {html.escape(m.key)}</small>"
                         f"<div class=row>{''.join(imgs)}</div></div>")
    (out_dir / "index.html").write_text("".join(parts), encoding="utf-8")
    return {k: len(v) for k, v in kinds.items()}
