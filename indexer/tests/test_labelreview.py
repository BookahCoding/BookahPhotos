"""The label review page, with generated pictures and a fake text encoder."""
import json

import numpy as np
from PIL import Image

from bookahphotos_indexer import content, labelreview, labels
from bookahphotos_indexer.state import State


class FakeText:
    """Label i gets the unit vector i (mod 512): a picture 'is' label i when its vector points the same way."""
    def encode(self, texts):
        out = np.zeros((len(texts), content.DIM), np.float32)
        for k in range(len(texts)):
            out[k, k % len(labels.LABELS)] = 1.0
        return out


def test_suggested_thresholds_have_a_floor_and_follow_the_spread():
    s = np.zeros((100, 2), np.float32); s[:, 1] = np.linspace(0.1, 0.4, 100)
    th = labelreview.suggest_thresholds(s)
    assert th[0] == np.float32(0.19) and th[1] > 0.3


def test_review_page_is_written_with_thumbnails_and_counts(tmp_path):
    st = State(tmp_path / "s.db")
    cache = tmp_path / "cache"; cache.mkdir()
    rng = np.random.default_rng(3)
    for fid in range(1, 41):
        st.upsert_listed(fid, f"P/{fid}.jpg", f"e{fid}", 10, "image/jpeg", 1)
        v = rng.normal(size=content.DIM).astype(np.float32) * 0.01
        v[(fid % 5)] += 1.0                                       # pictures of labels 0..4
        v = content.normalise(v[None])[0]
        st.save_content(fid, f"e{fid}", "ok", "preview1024", content.MODEL_NAME, content.to_f16(v), 10.0)
        Image.new("RGB", (400, 300), (fid * 5, 50, 50)).save(cache / f"{fid}-00000000-1024.jpg")
    out = tmp_path / "review"
    summary = labelreview.write_review(st, cache, out, FakeText())
    assert summary["beach"]["tagged"] == 8                       # label 0 = every fifth picture
    page = (out / "index.html").read_text(encoding="utf-8")
    assert "Παραλία" in page and "img/" in page
    assert len(list((out / "img").glob("*.jpg"))) >= 8
    assert "beach" in json.loads((out / "suggested_thresholds.json").read_text())


def test_agreed_thresholds_win_over_the_first_guess(tmp_path):
    st = State(tmp_path / "s.db")
    cache = tmp_path / "cache"; cache.mkdir()
    st.upsert_listed(1, "P/1.jpg", "e1", 10, "image/jpeg", 1)
    v = np.zeros(content.DIM, np.float32); v[0] = 1.0
    st.save_content(1, "e1", "ok", "preview1024", content.MODEL_NAME, content.to_f16(v), 1.0)
    Image.new("RGB", (50, 50)).save(cache / "1-00000000-1024.jpg")
    agreed = tmp_path / "agreed.json"; agreed.write_text(json.dumps({"beach": 0.5}))
    summary = labelreview.write_review(st, cache, tmp_path / "r", FakeText(), agreed)
    assert summary["beach"]["threshold"] == 0.5
