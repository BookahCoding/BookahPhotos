"""Greek review of the app's texts (Phase 6c).

  python tools/greek-review.py make [out.html]     writes a private page with every English text and its Greek text side by side
  python tools/greek-review.py apply results.json  applies the Greek texts Chris corrected (only the ones he changed) to values-el/strings.xml

The page works in the browser without a server: Chris edits the Greek, presses Save, and gets a file (greek-review-results.json) in his Downloads folder.
`apply` checks that the placeholders (%1$d, %1$s ...) of every corrected text are the same as in the English text, and refuses a text where they differ.
Texts are shown the way people write them (no XML or Android escapes); they are escaped again when written back (round trip tested by `make`).
"""
from __future__ import annotations

import html
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "android"
STRING = re.compile(r'(<string\s+name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sdf]")


def modules() -> list[str]:
    return sorted(p.relative_to(ROOT).parts[0] for p in ROOT.glob("*/src/main/res/values/strings.xml"))


def decode(raw: str) -> str:
    """XML text as stored (with Android escapes) -> the text a person reads."""
    s = raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&")
    out, i = [], 0
    while i < len(s):
        if s[i] == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?"}.get(n, "\\" + n))
            i += 2
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def encode(text: str) -> str:
    s = text.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n").replace("\t", "\\t")
    s = s.replace("&", "&amp;").replace("<", "&lt;")
    if s.startswith("@") or s.startswith("?"):
        s = "\\" + s
    return s


def load(module: str, lang: str) -> dict[str, str]:
    path = ROOT / module / "src/main/res" / lang / "strings.xml"
    if not path.exists():
        return {}
    return {m.group(2): m.group(3) for m in STRING.finditer(path.read_text(encoding="utf-8"))}


def make(out: Path) -> None:
    rows, bad = [], 0
    for mod in modules():
        en, el = load(mod, "values"), load(mod, "values-el")
        for key, raw in en.items():
            if encode(decode(raw)) != raw:
                bad += 1
            if key in el and encode(decode(el[key])) != el[key]:
                bad += 1
            rows.append({"m": mod, "k": key, "en": decode(raw), "el": decode(el.get(key, ""))})
    assert bad == 0, f"{bad} texts do not survive decode and encode unchanged: fix the tool before using it"
    page = TEMPLATE.replace("__ROWS__", json.dumps(rows, ensure_ascii=False)).replace("__COUNT__", str(len(rows)))
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding="utf-8")
    print(f"{len(rows)} texts in {len(modules())} modules -> {out}")


def apply(results: Path) -> None:
    data = json.loads(results.read_text(encoding="utf-8"))
    changes = data["changes"] if isinstance(data, dict) else data
    done = refused = 0
    for mod in sorted({c["m"] for c in changes}):
        path = ROOT / mod / "src/main/res/values-el/strings.xml"
        text = path.read_text(encoding="utf-8")
        en = load(mod, "values")
        for c in [c for c in changes if c["m"] == mod]:
            key, new = c["k"], c["el"]
            if key not in en:
                print(f"  skipped {mod}/{key}: not an English text"); refused += 1; continue
            if sorted(PLACEHOLDER.findall(new)) != sorted(PLACEHOLDER.findall(decode(en[key]))):
                print(f"  REFUSED {mod}/{key}: placeholders differ from the English text ({PLACEHOLDER.findall(decode(en[key]))})"); refused += 1; continue
            pattern = re.compile(r'(<string\s+name="' + re.escape(key) + r'"[^>]*>)(.*?)(</string>)', re.S)
            if pattern.search(text):
                text = pattern.sub(lambda m: m.group(1) + encode(new) + m.group(3), text, count=1)
            else:
                text = text.replace("</resources>", f'    <string name="{key}">{encode(new)}</string>\n</resources>')
            done += 1
        path.write_text(text, encoding="utf-8")
    print(f"applied {done}, refused {refused}")


TEMPLATE = r"""<!doctype html><html lang="el"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>BookahPhotos: Greek review</title>
<style>
:root{--bg:#fff;--fg:#1c1c1e;--mut:#6b6b73;--line:#e3e3e8;--acc:#2f6fed;--bad:#c62828;--ok:#2e7d32;--card:#f6f6f9}
@media (prefers-color-scheme:dark){:root{--bg:#111113;--fg:#f2f2f5;--mut:#a0a0aa;--line:#2a2a30;--card:#1b1b1f}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.4 system-ui,sans-serif}
header{position:sticky;top:0;background:var(--bg);border-bottom:1px solid var(--line);padding:10px 16px;z-index:5}
h1{font-size:18px;margin:0 0 6px} .bar{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
input[type=search]{flex:1;min-width:180px;padding:8px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg)}
button{padding:8px 14px;border:0;border-radius:8px;background:var(--acc);color:#fff;font-weight:600;cursor:pointer}
button.sec{background:var(--card);color:var(--fg);border:1px solid var(--line)}
main{padding:8px 16px 80px;max-width:1100px;margin:auto}
.mod{margin:18px 0 4px;font-weight:700;color:var(--mut);text-transform:uppercase;font-size:12px;letter-spacing:.06em}
.row{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:8px 0;border-bottom:1px solid var(--line)}
.row .en{white-space:pre-wrap;color:var(--mut)} .key{grid-column:1/-1;font:12px ui-monospace,monospace;color:var(--mut)}
textarea{width:100%;box-sizing:border-box;min-height:44px;padding:8px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg);font:inherit;resize:vertical}
.row.changed textarea{border-color:var(--acc)} .row.warn textarea{border-color:var(--bad)} .w{color:var(--bad);font-size:13px}
.ph{background:rgba(47,111,237,.18);border-radius:4px;padding:0 3px;font-family:ui-monospace,monospace}
@media (max-width:640px){.row{grid-template-columns:1fr}}
</style></head><body>
<header><h1>Greek review: __COUNT__ texts. Left: English. Right: Greek, edit it where it is wrong.</h1>
<div class="bar"><input type="search" id="q" placeholder="Search English or Greek..."><label><input type="checkbox" id="onlyChanged"> only changed</label>
<span id="count"></span><button id="save">Save results</button><button class="sec" id="reset">Undo all my changes</button></div></header>
<main id="main"></main>
<script>
const ROWS=__ROWS__;
const PH=/%(?:\d+\$)?[sdf]/g;
const ph=s=>(s.match(PH)||[]).slice().sort().join(' ');
const main=document.getElementById('main'), q=document.getElementById('q'), only=document.getElementById('onlyChanged'), count=document.getElementById('count');
const KEY='bookahphotos-greek-review-v1';
let saved={}; try{saved=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
const id=r=>r.m+'/'+r.k;
ROWS.forEach(r=>{r.orig=r.el; if(saved[id(r)]!==undefined) r.el=saved[id(r)]});
function esc(s){return s.replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}
function hl(s){return esc(s).replace(PH,m=>'<span class="ph">'+m+'</span>')}
function changed(r){return r.el!==r.orig}
function refresh(){const n=ROWS.filter(changed).length; count.textContent=n+' changed'}
function render(){
  const f=q.value.toLowerCase(); main.innerHTML=''; let last='';
  ROWS.forEach(r=>{
    if(only.checked&&!changed(r))return;
    if(f&&!(r.en.toLowerCase().includes(f)||r.el.toLowerCase().includes(f)||r.k.toLowerCase().includes(f)))return;
    if(r.m!==last){const h=document.createElement('div');h.className='mod';h.textContent=r.m;main.appendChild(h);last=r.m}
    const d=document.createElement('div');d.className='row'+(changed(r)?' changed':'');
    d.innerHTML='<div class="key">'+esc(r.k)+'</div><div class="en">'+hl(r.en)+'</div><div><textarea></textarea><div class="w"></div></div>';
    const t=d.querySelector('textarea'), w=d.querySelector('.w'); t.value=r.el;
    const check=()=>{const bad=ph(r.en)!==ph(t.value); d.classList.toggle('warn',bad); w.textContent=bad?'Keep the same codes as the English text: '+(ph(r.en)||'(none)'):'' };
    check();
    t.addEventListener('input',()=>{r.el=t.value;d.classList.toggle('changed',changed(r));check();
      if(changed(r))saved[id(r)]=r.el; else delete saved[id(r)]; try{localStorage.setItem(KEY,JSON.stringify(saved))}catch(e){} refresh()});
    main.appendChild(d)});
  refresh()}
q.addEventListener('input',render); only.addEventListener('change',render);
document.getElementById('reset').onclick=()=>{if(confirm('Undo all your changes on this page?')){ROWS.forEach(r=>r.el=r.orig);saved={};try{localStorage.removeItem(KEY)}catch(e){};render()}};
document.getElementById('save').onclick=()=>{
  const ch=ROWS.filter(changed).map(r=>({m:r.m,k:r.k,el:r.el})); const bad=ch.filter(c=>{const r=ROWS.find(x=>x.m===c.m&&x.k===c.k);return ph(r.en)!==ph(c.el)});
  if(bad.length&&!confirm(bad.length+' text(s) have different codes (%1$d, %s ...) than the English. They will be refused when applied. Save anyway?'))return;
  const blob=new Blob([JSON.stringify({changes:ch},null,1)],{type:'application/json'}); const a=document.createElement('a');
  a.href=URL.createObjectURL(blob); a.download='greek-review-results.json'; a.click()};
render();
</script></body></html>
"""

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "make":
        make(Path(sys.argv[2]) if len(sys.argv) > 2 else Path(r"C:\BookahPC\bookahphotos\review\greek\index.html"))
    elif cmd == "apply" and len(sys.argv) > 2:
        apply(Path(sys.argv[2]))
    else:
        print(__doc__)
        sys.exit(2)
