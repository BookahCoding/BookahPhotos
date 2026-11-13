import json
import sqlite3

import numpy as np
from PIL import Image

from bookahphotos_indexer import content, searchcheck


class FakeText:
    def encode(self, texts):
        out = np.zeros((len(texts), content.DIM), np.float32)
        for k in range(len(texts)):
            out[k, k] = 1.0
        return out


def test_top10_page_and_score(tmp_path):
    snap = tmp_path / "s.db"
    c = sqlite3.connect(snap)
    c.execute("CREATE TABLE asset_vectors (asset_id INTEGER PRIMARY KEY, model_id TEXT, dim INTEGER, vec BLOB)")
    cache = tmp_path / "cache"; cache.mkdir()
    for i in range(1, 31):                                         # photo i points at direction (i % 3); query k points at direction k
        v = np.zeros(content.DIM, np.float32); v[i % 3] = 1.0
        c.execute("INSERT INTO asset_vectors VALUES (?,?,?,?)", (i, "m", content.DIM, content.to_f16(v)))
        Image.new("RGB", (60, 40)).save(cache / f"{i}-00000000-1024.jpg")
    c.commit(); c.close()
    qfile = tmp_path / "q.json"
    qfile.write_text(json.dumps({"queries": ["σκύλος", "beach", "extra phrase"], "n_original": 2}, ensure_ascii=False), encoding="utf-8")
    out = tmp_path / "out"
    searchcheck.write_page(snap, qfile, cache, out, FakeText())
    r = json.loads((out / "queries.json").read_text(encoding="utf-8"))["results"]
    assert all(i % 3 == q for q, ids in enumerate(r) for i in ids)                      # every top-10 picture points the way the question does
    assert "σκύλος" in (out / "index.html").read_text(encoding="utf-8")
    clicks = tmp_path / "clicks.json"
    clicks.write_text(json.dumps({"0|0": True, "0|1": True, "1|3": True}))
    s = searchcheck.score(out / "queries.json", clicks)
    assert (s["greek"], s["english"], s["extras"], s["all"]) == (8.0, 9.0, 10.0, 9.0)


def test_score_small_counts_old_and_new_wrong_clicks(tmp_path):
    q = {"queries": ["dog", "beach"], "n_original": 2, "results": [[1, 2, 3, 4], [5, 6, 7, 8]], "old_results": [[1, 2, 9, 10], [5, 6, 7, 11]]}
    small = tmp_path / "small.json"
    small.write_text(json.dumps(q), encoding="utf-8")
    old = tmp_path / "old.json"
    old.write_text(json.dumps({"0|1": True, "1|3": True}), encoding="utf-8")       # old: photo 2 of 'dog' was wrong; photo 11 of 'beach' (not in the new top) was wrong
    new = tmp_path / "new.json"
    new.write_text(json.dumps({"0|2": True}), encoding="utf-8")                      # new: photo 3 of 'dog' is wrong
    s = searchcheck.score_small(small, new, old)
    assert (s["english"], s["all"]) == (3.0, 3.0)                                    # dog: 4 shown - 2 wrong (photo 2 before, photo 3 now) = 2; beach: 4 - 0 = 4; mean 3.0
