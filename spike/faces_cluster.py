"""Cluster the faces per pipeline (DBSCAN, cosine) and build a local review page for Chris. Page stays in out\\review_faces (outside Git)."""
import json, os, base64, io, sys
import numpy as np
from sklearn.cluster import DBSCAN
from PIL import Image

D = r"C:\BookahPC\bookahphotos\spike"; OUT = D + r"\out\review_faces"; os.makedirs(OUT, exist_ok=True)
CFG = {"bl": ("buffalo_l (ArcFace R50)", 0.5), "bs": ("buffalo_s (ArcFace MBF)", 0.5), "yunet": ("YuNet + SFace (permissive licence)", 0.4)}  # 0.6 merged 1,410 faces into one group
TOPN, SHOW = 14, 18
rng = np.random.default_rng(1)
html = ['<!doctype html><meta charset="utf-8"><title>Face groups review</title><style>body{font:14px system-ui;margin:14px;background:#111;color:#eee}h1{font-size:20px}h2{margin:28px 0 6px;color:#9cf}.c{margin:10px 0 18px;padding:8px;background:#1b1b1b;border-radius:6px}.faces{display:flex;flex-wrap:wrap;gap:3px}img{width:72px;height:72px;border:3px solid transparent;cursor:pointer}img.bad{border-color:#e33;opacity:.5}input{width:150px;margin-left:8px}#bar{position:sticky;top:0;background:#222;padding:8px;z-index:9}button{padding:6px 12px;font-size:15px}</style>',
        '<div id="bar">Per group: click any face that is a <b>different person</b> (turns red). Type a short name for the group (the same name on two groups means they are split). Then <button onclick="save()">Save results</button></div>']
stats = {}
for kind, (title, eps) in CFG.items():
    z = np.load(f"{D}\\out\\faces_{kind}.npz"); E, crops = z["E"], z["crops"]; meta = json.load(open(f"{D}\\out\\faces_{kind}_meta.json"))["meta"]
    ok = np.array([m["score"] >= 0.6 and m["w"] >= 40 for m in meta])
    idx = np.where(ok)[0]
    lab = DBSCAN(eps=eps, min_samples=3, metric="cosine").fit_predict(E[idx])
    ids, counts = np.unique(lab[lab >= 0], return_counts=True); order = np.argsort(-counts)
    stats[kind] = dict(faces=len(E), usable=int(ok.sum()), clusters=int(len(ids)), clustered=int((lab >= 0).sum()), noise=int((lab < 0).sum()), top=[int(c) for c in counts[order][:10]], singletons_5plus=int((counts >= 5).sum()))
    print(kind, stats[kind])
    html.append(f"<h2>{title} — {len(ids)} groups, {int((lab>=0).sum())} of {int(ok.sum())} usable faces grouped</h2>")
    for rank, o in enumerate(order[:TOPN]):
        members = idx[lab == ids[o]]; pick = rng.choice(members, size=min(SHOW, len(members)), replace=False)
        html.append(f'<div class="c" data-group="{kind}|{rank}"><b>Group {rank+1}</b> ({len(members)} faces) name:<input class="nm"><div class="faces">')
        for f in pick:
            buf = io.BytesIO(); Image.fromarray(crops[f]).save(buf, "PNG")
            html.append(f'<img data-f="{kind}|{rank}|{int(f)}" src="data:image/png;base64,{base64.b64encode(buf.getvalue()).decode()}">')
        html.append("</div></div>")
html.append("""<script>
const KEY='bookahphotos-faces';let st={};try{st=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
function put(){try{localStorage.setItem(KEY,JSON.stringify(st))}catch(e){}}
document.querySelectorAll('img').forEach(i=>{if(st[i.dataset.f])i.classList.add('bad');i.onclick=()=>{i.classList.toggle('bad');st[i.dataset.f]=i.classList.contains('bad');put()}});
document.querySelectorAll('.c').forEach(c=>{const n=c.querySelector('.nm');n.value=st['name|'+c.dataset.group]||'';n.oninput=()=>{st['name|'+c.dataset.group]=n.value;put()}});
function save(){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(st)],{type:'application/json'}));a.download='faces-review-results.json';a.click()}
</script>""")
open(OUT + r"\index.html", "w", encoding="utf-8").write("\n".join(html))
json.dump(stats, open(D + r"\out\faces_stats.json", "w"))
print("page:", OUT + r"\index.html")
