"""Build a local HTML page: for each question, the top 10 photos per model. Chris clicks the ones that fit.
Thumbnails and page stay in C:\\BookahPC\\bookahphotos\\spike\\out\\review (outside Git, outside Nextcloud)."""
import json, os, sys, hashlib
from PIL import Image, ImageOps
import pillow_heif
pillow_heif.register_heif_opener()

OUT = r"C:\BookahPC\bookahphotos\spike\out"
REV = OUT + r"\review"; TH = REV + r"\thumbs"; os.makedirs(TH, exist_ok=True)
models = sys.argv[1:] or ["siglip2"]
data = {m: json.load(open(f"{OUT}\\{m}_top10.json", encoding="utf-8")) for m in models}
queries = data[models[0]]["queries"]; n_orig = data[models[0]]["n_original"]

def thumb(p):
    h = hashlib.md5(p.encode("utf-8")).hexdigest()[:12] + ".jpg"; dst = os.path.join(TH, h)
    if not os.path.exists(dst):
        im = Image.open(p); im = ImageOps.exif_transpose(im).convert("RGB"); im.thumbnail((220, 220)); im.save(dst, quality=75)
    return "thumbs/" + h

rows = []
for qi, q in enumerate(queries):
    block = f'<section><h2>{qi+1}. {q} <small>{"(your question)" if qi < n_orig else "(my extra phrase)"}</small></h2>'
    for m in models:
        block += f'<h3>{m}</h3><div class="row">'
        for rank, p in enumerate(data[m]["results"]["int8" if "int8" in data[m]["results"] else list(data[m]["results"])[0]][q]):
            block += f'<img data-key="{m}|{qi}|{rank}" src="{thumb(p)}" title="{m} #{rank+1}">'
        block += "</div>"
    rows.append(block + "</section>")

html = """<!doctype html><meta charset="utf-8"><title>BookahPhotos search review</title>
<style>body{font:15px system-ui;margin:16px;background:#111;color:#eee}h2{margin:24px 0 4px}h3{margin:6px 0;color:#9ab}small{color:#888;font-weight:400}
.row{display:flex;gap:4px;flex-wrap:wrap}img{width:150px;height:150px;object-fit:cover;border:4px solid transparent;cursor:pointer}img.ok{border-color:#3c3}
#bar{position:sticky;top:0;background:#222;padding:8px;z-index:9}button{font-size:15px;padding:6px 12px}</style>
<div id="bar">Click the photos that fit the question (green = accepted). <button onclick="save()">Save results</button> <span id="cnt"></span></div>
""" + "".join(rows) + """
<script>
const KEY='bookahphotos-review';let st={};try{st=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
document.querySelectorAll('img').forEach(i=>{if(st[i.dataset.key])i.classList.add('ok');i.onclick=()=>{i.classList.toggle('ok');st[i.dataset.key]=i.classList.contains('ok');try{localStorage.setItem(KEY,JSON.stringify(st))}catch(e){};cnt()}});
function cnt(){document.getElementById('cnt').textContent=Object.values(st).filter(Boolean).length+' accepted'}cnt();
function save(){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(st)],{type:'application/json'}));a.download='review-results.json';a.click()}
</script>"""
open(REV + r"\index.html", "w", encoding="utf-8").write(html)
print("review page:", REV + r"\index.html", "thumbs:", len(os.listdir(TH)))
