"""Phase 4a: the card guard, tags, and the content run (generated pictures, a fake server, a fake encoder: no card, no Nextcloud)."""
import io
import threading

import numpy as np
import pytest
from PIL import Image

from bookahphotos_indexer import content, contentrun, gpu, labels
from bookahphotos_indexer.dav import DavError
from bookahphotos_indexer.state import State


def jpeg(color=(200, 30, 30), size=(640, 480)) -> bytes:
    b = io.BytesIO()
    Image.new("RGB", size, color).save(b, "JPEG")
    return b.getvalue()


# ---- card ----
def test_find_adapter_by_name_not_number():
    ads = [(0, "AMD Radeon(TM) Graphics", 0.5), (1, "AMD Radeon RX 7600", 8.0)]
    assert gpu.find_adapter("RX 7600", ads) == 1
    with pytest.raises(RuntimeError):
        gpu.find_adapter("RTX 4090", ads)


def test_gpu_status_free_and_taken():
    free = gpu.gpu_status(ollama=lambda: [], utilisation=lambda: 3.0, processes=lambda: [])
    assert free.free and not free.reasons
    taken = gpu.gpu_status(ollama=lambda: ["qwen"], utilisation=lambda: 80.0, processes=lambda: ["whisper"])
    assert not taken.free and len(taken.reasons) == 3


# ---- preprocessing and tags ----
def test_preprocess_shape_and_crop_vs_squash():
    im = Image.new("RGB", (800, 400), (255, 0, 0))
    a = content.preprocess(im, "crop")
    assert a.shape == (3, 224, 224) and a.dtype == np.float32
    assert content.preprocess(im, "squash").shape == (3, 224, 224)


def test_f16_roundtrip_and_normalise():
    v = content.normalise(np.random.default_rng(1).normal(size=(1, 512)).astype(np.float32))[0]
    back = content.from_f16(content.to_f16(v))
    assert float(back @ v) > 0.9999


def test_assign_tags_threshold_and_limit():
    lab = np.eye(4, 8, dtype=np.float32)                       # four unit label vectors
    v = np.zeros((2, 8), np.float32); v[0, 0] = 1.0; v[1, 5] = 1.0
    tags = labels.assign_tags(v, lab, np.full(4, 0.5, np.float32))
    assert [i for i, _ in tags[0]] == [0] and tags[1] == []
    many = labels.assign_tags(np.ones((1, 4), np.float32) / 2, lab[:, :4], np.zeros(4, np.float32), max_tags=2)
    assert len(many[0]) == 2


def test_tighten_keeps_the_requested_share_and_never_lowers():
    i = [l[0] for l in labels.LABELS].index("boat")
    S = np.zeros((100, len(labels.LABELS)), np.float32); S[:, i] = np.linspace(0.2, 0.3, 100)
    th = np.full(len(labels.LABELS), 0.2, np.float32)
    new = labels.tighten(S, th, {"boat": 0.5})
    assert 0.24 < new[i] < 0.26 and (S[:, i] >= new[i]).sum() in range(48, 53)
    assert (new[np.arange(len(new)) != i] == 0.2).all()                  # other labels untouched
    nine = np.zeros((9, len(labels.LABELS)), np.float32); nine[:, i] = np.linspace(0.21, 0.29, 9)
    n = labels.tighten(nine, th, {"boat": 0.9})[i]
    assert (nine[:, i] >= n).sum() == 9 - 0 or (nine[:, i] >= n).sum() == 9    # 90% of 9 rounds up to 9: nothing lost
    one = labels.tighten(nine, th, {"boat": 0.01})[i]
    assert (nine[:, i] >= one).sum() == 1                                     # never fewer than the best picture


def test_exclusive_pairs_keep_only_the_higher_score(monkeypatch):
    monkeypatch.setattr(labels, "EXCLUSIVE", [("beach", "sea")])
    names = [l[0] for l in labels.LABELS]
    lab = np.eye(len(names), dtype=np.float32)
    v = np.zeros((1, len(names)), np.float32); v[0, names.index("beach")] = 0.9; v[0, names.index("sea")] = 0.8
    tags = labels.assign_tags(v, lab, np.full(len(names), 0.5, np.float32))
    assert [names[i] for i, _ in tags[0]] == ["beach"]


def test_agreed_thresholds_file_matches_the_labels():
    th = labels.load_thresholds()
    assert len(th) == len(labels.LABELS) and ((th >= 0.18) & (th <= 0.35)).all()


def test_dropped_labels_are_really_gone_and_the_tuning_list_only_names_real_labels():
    names = {l[0] for l in labels.LABELS}
    for gone in ("cat", "bird", "horse", "screenshot", "receipt", "map", "painting", "game_art", "book", "art_statue", "bridge", "cake", "lake_river"):
        assert gone not in names
    assert set(labels.KEEP_AFTER_REVIEW) <= names


def test_labels_are_complete_and_unique():
    slugs = [l[0] for l in labels.LABELS]
    assert len(slugs) == len(set(slugs)) and 40 <= len(slugs) <= 70
    assert all(all(part.strip() for part in l) for l in labels.LABELS)


def test_sharpness_flat_is_lower_than_noise():
    flat = Image.new("RGB", (300, 300), (128, 128, 128))
    noise = Image.fromarray(np.random.default_rng(0).integers(0, 255, (300, 300, 3), dtype=np.uint8))
    assert contentrun.sharpness(flat) < contentrun.sharpness(noise)


# ---- the run ----
class FakeDav:
    def __init__(self, previews: dict[int, bytes | None], originals: dict[str, bytes] | None = None, fail_network: set[int] | None = None):
        self.previews, self.originals, self.fail_network = previews, originals or {}, fail_network or set()
        self.calls: list[int] = []

    def preview_fit(self, file_id, size=1024):
        self.calls.append(file_id)
        if file_id in self.fail_network:
            raise DavError("GET failed after 3 tries: ReadTimeout")
        if self.previews.get(file_id) is None:
            raise DavError("GET HTTP 404")
        return self.previews[file_id]

    def get(self, path, byte_range=None):
        if path in self.originals:
            return self.originals[path]
        raise DavError("GET HTTP 404")


class FakeEncoder:
    def __init__(self):
        self.closed = False
        self.batches = 0

    def encode(self, batch, batch_size=16):
        self.batches += 1
        out = np.zeros((len(batch), content.DIM), np.float32); out[:, 0] = 1.0
        return out

    def close(self):
        self.closed = True


def make_state(tmp_path, n=6) -> State:
    st = State(tmp_path / "s.db")
    for i in range(1, n + 1):
        st.upsert_listed(i, f"Photos/p{i}.jpg", f"etag{i}", 1000, "image/jpeg", 1)
        st.set_fields(i, {}, level=1)
    st.db.commit()
    return st


def free_guard():
    return gpu.GpuGuard(lambda: gpu.GpuStatus(True, []))


def run(st, dav, tmp_path, **kw):
    enc = FakeEncoder()
    res = contentrun.run_content(dav, st, tmp_path / "cache", 0, kw.pop("guard", free_guard()), encoder_factory=lambda: enc, wave=2, **kw)
    return res, enc


def test_run_makes_vectors_saves_and_second_run_does_nothing(tmp_path):
    st = make_state(tmp_path, 5)
    dav = FakeDav({i: jpeg() for i in range(1, 6)})
    res, enc = run(st, dav, tmp_path)
    assert res.done == 5 and res.no_picture == 0 and enc.closed
    assert st.content_counts() == {"ok": 5}
    assert len(content.from_f16(st.db.execute("SELECT vec FROM content WHERE file_id=1").fetchone()[0])) == content.DIM
    calls = len(dav.calls)
    res2, _ = run(st, dav, tmp_path)
    assert res2.done == 0 and len(dav.calls) == calls          # a second run changes nothing and asks the server nothing


def test_cache_is_used_after_the_database_is_lost(tmp_path):
    st = make_state(tmp_path, 3)
    dav = FakeDav({i: jpeg() for i in range(1, 4)})
    run(st, dav, tmp_path)
    st.db.execute("DELETE FROM content"); st.db.commit()      # a rebuild of the vectors must not fetch again
    before = len(dav.calls)
    res, _ = run(st, dav, tmp_path)
    assert res.done == 3 and res.from_cache == 3 and len(dav.calls) == before


def test_no_preview_falls_back_to_the_original_and_final_failures_are_remembered(tmp_path):
    st = make_state(tmp_path, 3)
    dav = FakeDav({1: jpeg(), 2: None, 3: None}, originals={"Photos/p2.jpg": jpeg((10, 200, 10))})   # 2 has an original, 3 has nothing
    res, _ = run(st, dav, tmp_path)
    assert (res.done, res.no_picture, res.from_original) == (2, 1, 1)
    assert st.content_counts() == {"ok": 2, "no_picture": 1}
    assert st.pending_content(content.MODEL_NAME) == []        # 'no picture' is final until the file changes


def test_camera_raw_uses_the_jpeg_inside_and_caches_a_small_copy(tmp_path):
    st = make_state(tmp_path, 1)
    raw = b"II*\x00" + b"\x00" * 500 + jpeg((10, 10, 200), (320, 240)) + b"\x00" * 300 + jpeg((200, 10, 10), (1600, 1200)) + b"\x01" * 400
    dav = FakeDav({1: None}, originals={"Photos/p1.jpg": raw})
    res, _ = run(st, dav, tmp_path)
    assert res.done == 1 and res.from_original == 1
    cached = list((tmp_path / "cache").glob("1-*-1024.jpg"))
    assert len(cached) == 1 and max(Image.open(cached[0]).size) == 1024      # the largest inner picture, shrunk to 1024
    before = len(dav.calls)
    st.db.execute("DELETE FROM content"); st.db.commit()
    run(st, dav, tmp_path)
    assert len(dav.calls) == before                                          # second time: from the cache


def test_cached_copy_of_an_original_is_upright_and_full_size(tmp_path):
    st = make_state(tmp_path, 1)
    b = io.BytesIO()
    exif = Image.Exif(); exif[0x0112] = 6                                  # "rotate 90 degrees to view": stored sideways
    Image.new("RGB", (2400, 1200), (90, 90, 200)).save(b, "JPEG", exif=exif)
    dav = FakeDav({1: None}, originals={"Photos/p1.jpg": b.getvalue()})
    run(st, dav, tmp_path)
    w, h = Image.open(next((tmp_path / "cache").glob("1-*-1024.jpg"))).size
    assert (w, h) == (512, 1024)                                           # upright (taller than wide) and 1024 on the long side


def test_network_trouble_saves_nothing_and_is_retried(tmp_path):
    st = make_state(tmp_path, 2)
    dav = FakeDav({1: jpeg(), 2: jpeg()}, fail_network={2})
    res, _ = run(st, dav, tmp_path)
    assert res.done == 1 and res.transient == 1
    assert [r["file_id"] for r in st.pending_content(content.MODEL_NAME)] == [2]


def test_changed_file_is_read_again(tmp_path):
    st = make_state(tmp_path, 1)
    run(st, FakeDav({1: jpeg()}), tmp_path)
    st.upsert_listed(1, "Photos/p1.jpg", "etag-new", 1000, "image/jpeg", 2)
    assert st.pending_content(content.MODEL_NAME) == []         # not before its metadata is read again (level 0)
    st.set_fields(1, {}, level=1)
    assert [r["file_id"] for r in st.pending_content(content.MODEL_NAME)] == [1]


def test_card_in_use_stops_the_run_without_loading_a_model(tmp_path):
    st = make_state(tmp_path, 2)
    busy = gpu.GpuGuard(lambda: gpu.GpuStatus(False, ["Ollama has a model loaded: x"]))
    made = []
    res = contentrun.run_content(FakeDav({1: jpeg(), 2: jpeg()}), st, tmp_path / "cache", 0, busy, wait_busy_s=60, sleep=lambda s: None,
                                 encoder_factory=lambda: made.append(1) or FakeEncoder())
    assert res.done == 0 and not made and "in use" in res.stopped and res.waited_for_gpu_s >= 60


def test_time_limit_stops_between_waves(tmp_path):
    st = make_state(tmp_path, 6)
    stop = threading.Event(); stop.set()
    res, _ = run(st, FakeDav({i: jpeg() for i in range(1, 7)}), tmp_path, stop=stop)
    assert res.done == 0 and res.stopped == "time limit"


def test_tiffs_and_videos_come_last(tmp_path):
    st = State(tmp_path / "s.db")
    for fid, path, mime in [(1, "a.tif", "image/tiff"), (2, "b.mp4", "video/mp4"), (3, "c.jpg", "image/jpeg")]:
        st.upsert_listed(fid, path, "e", 10, mime, 1); st.set_fields(fid, {}, level=1)
    assert [r["file_id"] for r in st.pending_content(content.MODEL_NAME)] == [3, 2, 1]      # photo, then video, then scan
