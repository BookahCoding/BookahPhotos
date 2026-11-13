"""A private review page for the people groups (the Phase 0 page, now on the real run): the biggest groups with 18 random faces each. Chris clicks the faces
that are a different person and types a name. It holds REAL faces: it lives in the state folder only. His names never go into Git, logs or notes."""
from __future__ import annotations

import base64
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

from .state import State

CROP = 96


def _crop(cache_dir: Path, file_id: int, box: tuple[float, float, float, float]) -> bytes | None:
    src = next(iter(sorted(cache_dir.glob(f"{file_id}-*-1024.jpg"))), None)
    if src is None:
        return None
    im = Image.open(src).convert("RGB")
    w, h = im.size
    x1, y1, x2, y2 = box
    bw, bh = (x2 - x1) * w, (y2 - y1) * h
    side = max(bw, bh) * 1.4                                              # a little context around the face, square
    cx, cy = (x1 + x2) / 2 * w, (y1 + y2) / 2 * h
    c = im.crop((int(cx - side / 2), int(cy - side / 2), int(cx + side / 2), int(cy + side / 2))).resize((CROP, CROP))
    b = io.BytesIO()
    c.save(b, "JPEG", quality=80)
    return b.getvalue()


def write_review(state: State, cache_dir: Path, out_file: Path, top: int = 40, show: int = 18, seed: int = 1) -> dict:
    rng = np.random.default_rng(seed)
    persons = state.db.execute(
        "SELECT p.person_id, COUNT(*) AS n FROM faces f JOIN persons p ON p.person_id=f.person_id WHERE p.merged_into IS NULL AND f.rejected=0 "
        "GROUP BY p.person_id ORDER BY n DESC, p.person_id").fetchall()
    total_faces = state.db.execute("SELECT COUNT(*) FROM faces WHERE usable=1 AND rejected=0").fetchone()[0]
    grouped = sum(r["n"] for r in persons)
    html = ['<!doctype html><meta charset="utf-8"><title>People groups review</title><style>body{font:14px system-ui;margin:14px;background:#111;color:#eee}'
            'h2{margin:26px 0 6px;color:#9cf}.c{margin:10px 0 18px;padding:8px;background:#1b1b1b;border-radius:6px}.faces{display:flex;flex-wrap:wrap;gap:3px}'
            'img{width:84px;height:84px;border:3px solid transparent;cursor:pointer}img.bad{border-color:#e33;opacity:.5}input{width:160px;margin-left:8px}'
            '#bar{position:sticky;top:0;background:#222;padding:8px;z-index:9}button{padding:6px 12px;font-size:15px}</style>',
            '<div id="bar">Per group: click every face that is a <b>different person</b> (it turns red). Type a short name for the group if you want '
            '(the same name on two groups means they are one person). Then <button onclick="save()">Save results</button> (a file is downloaded: send it to me).</div>',
            f"<h2>{len(persons)} groups, {grouped} of {total_faces} usable faces grouped; the biggest {min(top, len(persons))} are shown</h2>"]
    shown = 0
    for rank, p in enumerate(persons[:top]):
        rows = state.db.execute("SELECT face_id, file_id, x1, y1, x2, y2 FROM faces WHERE person_id=? AND rejected=0 ORDER BY face_id", (p["person_id"],)).fetchall()
        pick = [rows[i] for i in rng.choice(len(rows), size=min(show, len(rows)), replace=False)]
        html.append(f'<div class="c" data-group="{p["person_id"]}"><b>Group {rank + 1}</b> (id {p["person_id"]}, {p["n"]} faces) name:<input class="nm"><div class="faces">')
        for r in pick:
            jpg = _crop(cache_dir, r["file_id"], (r["x1"], r["y1"], r["x2"], r["y2"]))
            if jpg:
                html.append(f'<img data-f="{p["person_id"]}|{r["face_id"]}" src="data:image/jpeg;base64,{base64.b64encode(jpg).decode()}">')
        html.append("</div></div>")
        shown += 1
    html.append("""<script>
const KEY='bookahphotos-people';let st={};try{st=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
function put(){try{localStorage.setItem(KEY,JSON.stringify(st))}catch(e){}}
document.querySelectorAll('img').forEach(i=>{if(st[i.dataset.f])i.classList.add('bad');i.onclick=()=>{i.classList.toggle('bad');st[i.dataset.f]=i.classList.contains('bad');put()}});
document.querySelectorAll('.c').forEach(c=>{const n=c.querySelector('.nm');n.value=st['name|'+c.dataset.group]||'';n.oninput=()=>{st['name|'+c.dataset.group]=n.value;put()}});
function save(){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(st)],{type:'application/json'}));a.download='people-review-results.json';a.click()}
</script>""")
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text("\n".join(html), encoding="utf-8")
    return {"groups": len(persons), "grouped": grouped, "usable_faces": total_faces, "shown": shown}
