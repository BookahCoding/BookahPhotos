"""Search quality on the whole library (the Phase 0 test again): the Phase 0 questions are run against the vectors inside a SNAPSHOT file (so this also proves the
snapshot can power search); the private page shows the top 10 pictures per question, Chris clicks the wrong ones, the results file is scored like Phase 0
(average number of accepted pictures of 10, for Greek, English, extra phrases and all). Real photos: the page lives in the state folder only."""
from __future__ import annotations

import html
import json
import sqlite3
from pathlib import Path

import numpy as np

from .labelreview import _thumb


def _is_greek(s: str) -> bool:
    return any("Ͱ" <= c <= "Ͽ" for c in s)


def top10(snapshot: Path, queries: list[str], text_encoder, k: int = 10) -> list[list[int]]:
    conn = sqlite3.connect(f"file:{snapshot}?mode=ro", uri=True)
    rows = conn.execute("SELECT asset_id, vec FROM asset_vectors ORDER BY asset_id").fetchall()
    conn.close()
    ids = np.array([r[0] for r in rows])
    V = np.stack([np.frombuffer(r[1], np.float16).astype(np.float32) for r in rows])
    Q = text_encoder.encode(queries)
    S = Q @ V.T
    return [[int(ids[j]) for j in np.argsort(-S[i])[:k]] for i in range(len(queries))]


def write_page(snapshot: Path, queries_file: Path, cache_dir: Path, out_dir: Path, text_encoder) -> dict:
    data = json.loads(Path(queries_file).read_text(encoding="utf-8"))
    queries, n_orig = data["queries"], data["n_original"]
    results = top10(snapshot, queries, text_encoder)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "queries.json").write_text(json.dumps({"queries": queries, "n_original": n_orig, "results": results}, ensure_ascii=False), encoding="utf-8")
    parts = ["<!doctype html><meta charset=utf-8><title>Search check</title><style>body{font:14px sans-serif;margin:14px;background:#111;color:#eee}h2{margin:24px 0 4px;color:#9cf}"
             ".row{display:flex;flex-wrap:wrap;gap:4px}img{width:170px;height:128px;object-fit:cover;border:4px solid transparent;cursor:pointer}img.bad{border-color:#e33;opacity:.45}"
             "#bar{position:sticky;top:0;background:#222;padding:8px;z-index:9}button{padding:6px 12px;font-size:15px}small{color:#999}</style>",
             '<div id="bar">Same questions as in the first test, now on the WHOLE library. Click every picture that does <b>not</b> match the question (it turns red). '
             'Then <button onclick="save()">Save results</button> and send me the downloaded file.</div>']
    for qi, (q, ids) in enumerate(zip(queries, results)):
        parts.append(f"<h2>{qi + 1}. {html.escape(q)} <small>{'(your question)' if qi < n_orig else '(my extra phrase)'}</small></h2><div class=row>")
        for rank, a in enumerate(ids):
            t = _thumb(cache_dir, out_dir, a)
            if t:
                parts.append(f"<img data-k='{qi}|{rank}' src='{t}' loading=lazy>")
        parts.append("</div>")
    parts.append("""<script>
const KEY='bookahphotos-search-whole';let st={};try{st=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
function put(){try{localStorage.setItem(KEY,JSON.stringify(st))}catch(e){}}
document.querySelectorAll('img').forEach(i=>{if(st[i.dataset.k])i.classList.add('bad');i.onclick=()=>{i.classList.toggle('bad');st[i.dataset.k]=i.classList.contains('bad');put()}});
function save(){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(st)],{type:'application/json'}));a.download='search-check-results.json';a.click()}
</script>""")
    (out_dir / "index.html").write_text("".join(parts), encoding="utf-8")
    return {"queries": len(queries)}


def small_top10(snapshot: Path, queries: list[str], tokenizer_asset: Path, small_model: Path, k: int = 10) -> list[list[int]]:
    """Top-k per question with the PHONE's model (the shrunk int8 text model and the phone tokenizer) against the vectors in the snapshot."""
    import onnxruntime as ort
    from .phone_tokenizer import load_asset
    tk = load_asset(tokenizer_asset)
    sess = ort.InferenceSession(str(small_model), providers=["CPUExecutionProvider"])
    vecs = []
    for q in queries:
        v = sess.run(None, {"text": np.array([tk.encode(q)], dtype=np.int32)})[0][0].astype(np.float32)
        vecs.append(v / np.linalg.norm(v))

    class _Fixed:
        def encode(self, texts):
            return np.stack([vecs[queries.index(t)] for t in texts])
    return top10(snapshot, queries, _Fixed(), k)


def write_small_page(snapshot: Path, old_queries_json: Path, cache_dir: Path, out_dir: Path, tokenizer_asset: Path, small_model: Path) -> dict:
    """The phone model's top 10 on the whole library versus the full model's top 10 that Chris already judged. The page shows ONLY the photos that are new
    in the phone model's top 10, so Chris judges just those."""
    old = json.loads(Path(old_queries_json).read_text(encoding="utf-8"))
    queries, old_ids, n_orig = old["queries"], old["results"], old["n_original"]
    new_ids = small_top10(snapshot, queries, tokenizer_asset, small_model)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "queries.json").write_text(json.dumps({"queries": queries, "n_original": n_orig, "results": new_ids, "old_results": old_ids}, ensure_ascii=False), encoding="utf-8")
    overlap = [len(set(a) & set(b)) for a, b in zip(old_ids, new_ids)]
    parts = ["<!doctype html><meta charset=utf-8><title>Phone model check</title><style>body{font:14px sans-serif;margin:14px;background:#111;color:#eee}h2{margin:22px 0 4px;color:#9cf}"
             ".row{display:flex;flex-wrap:wrap;gap:4px}img{width:170px;height:128px;object-fit:cover;border:4px solid transparent;cursor:pointer}img.bad{border-color:#e33;opacity:.45}"
             "#bar{position:sticky;top:0;background:#222;padding:8px;z-index:9}button{padding:6px 12px;font-size:15px}small{color:#999}</style>",
             '<div id="bar">The phone\'s search model gives a slightly different top 10 than the full model you already judged. Only the NEW photos are shown here. '
             'Click every one that does <b>not</b> match the question (it turns red), then <button onclick="save()">Save results</button> and send me the file.</div>']
    shown = 0
    for qi, (q, ids) in enumerate(zip(queries, new_ids)):
        fresh = [(r, a) for r, a in enumerate(ids) if a not in set(old_ids[qi])]
        if not fresh:
            continue
        parts.append(f"<h2>{qi + 1}. {html.escape(q)} <small>({len(fresh)} new of 10)</small></h2><div class=row>")
        for r, a in fresh:
            t = _thumb(cache_dir, out_dir, a)
            if t:
                parts.append(f"<img data-k='{qi}|{r}' src='{t}' loading=lazy>")
                shown += 1
        parts.append("</div>")
    parts.append("""<script>
const KEY='bookahphotos-search-small';let st={};try{st=JSON.parse(localStorage.getItem(KEY)||'{}')}catch(e){}
function put(){try{localStorage.setItem(KEY,JSON.stringify(st))}catch(e){}}
document.querySelectorAll('img').forEach(i=>{if(st[i.dataset.k])i.classList.add('bad');i.onclick=()=>{i.classList.toggle('bad');st[i.dataset.k]=i.classList.contains('bad');put()}});
function save(){const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([JSON.stringify(st)],{type:'application/json'}));a.download='search-small-results.json';a.click()}
</script>""")
    (out_dir / "index.html").write_text("".join(parts), encoding="utf-8")
    return {"queries": len(queries), "mean_overlap": round(sum(overlap) / len(overlap), 2), "min_overlap": min(overlap), "new_photos": shown}


def score_small(small_json: Path, new_clicks: Path, old_clicks: Path) -> dict:
    """Accepted of 10 for the phone's model: a photo counts as wrong when Chris marked it wrong before (it was in the full model's top 10) or marks it wrong now."""
    d = json.loads(Path(small_json).read_text(encoding="utf-8"))
    queries, n_orig, new_ids, old_ids = d["queries"], d["n_original"], d["results"], d["old_results"]
    new_c = json.loads(Path(new_clicks).read_text(encoding="utf-8"))
    old_c = json.loads(Path(old_clicks).read_text(encoding="utf-8"))
    per = []
    for qi in range(len(queries)):
        wrong_old = {old_ids[qi][r] for r in range(len(old_ids[qi])) if old_c.get(f"{qi}|{r}") is True}
        wrong = {a for r, a in enumerate(new_ids[qi]) if (a in wrong_old) or new_c.get(f"{qi}|{r}") is True}
        per.append(len(new_ids[qi]) - len(wrong))
    avg = lambda xs: round(sum(xs) / len(xs), 2) if xs else 0.0
    return {"greek": avg([per[i] for i in range(n_orig) if _is_greek(queries[i])]), "english": avg([per[i] for i in range(n_orig) if not _is_greek(queries[i])]),
            "extras": avg(per[n_orig:]), "all": avg(per), "worst": [(queries[i], per[i]) for i in sorted(range(len(per)), key=lambda i: per[i])[:5]]}


def score(queries_json: Path, results_file: Path) -> dict[str, float]:
    """Average accepted pictures of 10: Greek, English (your questions), extra phrases, all. Results file = the clicks of the page."""
    q = json.loads(Path(queries_json).read_text(encoding="utf-8"))
    clicks = json.loads(Path(results_file).read_text(encoding="utf-8"))
    queries, n_orig = q["queries"], q["n_original"]
    per = []
    for qi in range(len(queries)):
        wrong = sum(1 for r in range(len(q["results"][qi])) if clicks.get(f"{qi}|{r}") is True)
        per.append(len(q["results"][qi]) - wrong)
    avg = lambda xs: round(sum(xs) / len(xs), 2) if xs else 0.0
    return {"greek": avg([per[i] for i in range(n_orig) if _is_greek(queries[i])]), "english": avg([per[i] for i in range(n_orig) if not _is_greek(queries[i])]),
            "extras": avg(per[n_orig:]), "all": avg(per), "worst": [(queries[i], per[i]) for i in sorted(range(len(per)), key=lambda i: per[i])[:5]]}
