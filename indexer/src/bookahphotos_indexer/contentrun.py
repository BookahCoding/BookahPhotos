"""One content session: fetch each picture once (1024 px preview, cached on disk), make its vector on the graphics card, save at once.
Resumable and polite: 2 Nextcloud requests at a time, backs off when the server is slow, gives way when someone else uses the card."""
from __future__ import annotations

import hashlib
import io
import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from . import content
from .dav import Dav, DavError
from .gpu import GpuGuard
from .state import State

log = logging.getLogger("bookahphotos.content")
MAX_ORIGINAL_BYTES = 80 * 1024 * 1024
SLOW_REQUEST_S = 5.0


@dataclass
class ContentResult:
    done: int = 0
    no_picture: int = 0
    transient: int = 0            # network trouble: not saved, tried again next run
    from_cache: int = 0
    from_original: int = 0
    stopped: str = ""             # why the session ended early ("" = it did everything it was asked)
    waited_for_gpu_s: float = 0.0
    reasons: list[str] = field(default_factory=list)


def is_tiff(row) -> bool:
    return row["mime"] in ("image/tiff", "image/x-tiff") or row["path"].lower().endswith((".tif", ".tiff"))


def cache_path(cache_dir: Path, file_id: int, etag: str, size: int) -> Path:
    return cache_dir / f"{file_id}-{hashlib.sha1(etag.encode()).hexdigest()[:8]}-{size}.jpg"


def sharpness(im) -> float:
    """Variance of the Laplacian of a 256 px grey copy: high = sharp, low = blurry or flat (used to pick covers)."""
    g = np.asarray(im.convert("L").resize((256, 256)), np.float32)
    lap = g[1:-1, 1:-1] * 4 - g[:-2, 1:-1] - g[2:, 1:-1] - g[1:-1, :-2] - g[1:-1, 2:]
    return float(lap.var())


class _Transient(Exception):
    pass


def fetch_picture(dav: Dav, row, cache_dir: Path, result_lock: threading.Lock, res: ContentResult) -> tuple[bytes | None, str]:
    """(image bytes, source) or (None, '') when the file has no usable picture. Raises _Transient on network trouble."""
    size = 256 if is_tiff(row) else 1024
    cp = cache_path(cache_dir, row["file_id"], row["etag"], size)
    if cp.exists():
        with result_lock:
            res.from_cache += 1
        return cp.read_bytes(), f"preview{size}"
    t0 = time.monotonic()
    try:
        data = dav.preview_fit(row["file_id"], size)
        cp.write_bytes(data)
        if time.monotonic() - t0 > SLOW_REQUEST_S:
            time.sleep(SLOW_REQUEST_S)         # the server is struggling: ease off
        return data, f"preview{size}"
    except DavError as e:
        if "failed after" in str(e):
            raise _Transient(str(e)) from e
    # no Nextcloud preview (HEIC variants, odd names): read the original once, decode on this PC, keep nothing of it
    if row["mime"].startswith("video/") or row["size"] > MAX_ORIGINAL_BYTES:
        return None, ""
    try:
        data = dav.get(row["path"])
    except DavError as e:
        if "failed after" in str(e):
            raise _Transient(str(e)) from e
        return None, ""
    with result_lock:
        res.from_original += 1
    return data, "original"


def decode_picture(data: bytes, source: str, row, cache_dir: Path):
    """Bytes of a preview or an original -> PIL image, or None. An original (no Nextcloud preview) is decoded in full, turned upright, and a
    1024 px upright copy is kept in the cache so the big file is never downloaded twice (faces read that copy too). Camera RAW: the JPEG inside."""
    from PIL import Image, ImageOps
    try:
        import pillow_heif
        pillow_heif.register_heif_opener()
    except Exception:
        pass
    try:
        im = Image.open(io.BytesIO(data))
        if source != "original":
            im.draft("RGB", (content.SIZE * 2, content.SIZE * 2))       # previews are decoded small; the cache file itself is untouched
        im.load()
    except Exception:
        im = content.embedded_jpeg(data) if source == "original" else None
        if im is None:
            return None
    if source == "original":
        small = ImageOps.exif_transpose(im).convert("RGB")
        small.thumbnail((1024, 1024))
        small.save(cache_path(cache_dir, row["file_id"], row["etag"], 1024), "JPEG", quality=90)
    return im


def run_content(dav: Dav, state: State, cache_dir: Path, adapter: int | None, guard: GpuGuard, stop: threading.Event | None = None,
                limit: int | None = None, workers: int = 2, wave: int = 32, wait_busy_s: float = 600.0,
                sleep: Callable[[float], None] = time.sleep, encoder_factory: Callable[[], object] | None = None, spread: bool = False) -> ContentResult:
    res = ContentResult()
    cache_dir.mkdir(parents=True, exist_ok=True)
    todo = state.pending_content(content.MODEL_NAME)
    if spread and limit and len(todo) > limit:       # a sample spread evenly over the whole library (for tuning), not just the first files
        step = len(todo) / limit
        todo = [todo[int(i * step)] for i in range(limit)]
    else:
        todo = todo[: limit or None]
    if not todo:
        return res
    lock = threading.Lock()
    enc = None

    def stopped() -> bool:
        return stop is not None and stop.is_set()

    def wait_for_card() -> bool:
        """True when the card is free. Waits (up to wait_busy_s) while someone else uses it; never takes it by force."""
        waited = 0.0
        while True:
            st = guard.ensure_free()
            if st.free:
                return True
            res.reasons = st.reasons
            if waited >= wait_busy_s or stopped():
                return False
            sleep(30.0)
            waited += 30.0
            res.waited_for_gpu_s += 30.0

    def load(row):
        if stopped():
            return row, "stop", None, "", 0.0
        try:
            data, source = fetch_picture(dav, row, cache_dir, lock, res)
        except _Transient:
            return row, "transient", None, "", 0.0
        if data is None:
            return row, "none", None, "", 0.0
        try:
            im = decode_picture(data, source, row, cache_dir)
            if im is None:
                return row, "none", None, "", 0.0
            return row, "ok", content.preprocess(im, "crop"), source, sharpness(im)
        except Exception:
            return row, "none", None, "", 0.0

    try:
        with ThreadPoolExecutor(max(1, workers)) as ex:
            for i in range(0, len(todo), wave):
                if stopped():
                    res.stopped = "time limit"
                    break
                if not wait_for_card():
                    res.stopped = "the graphics card is in use: " + "; ".join(res.reasons)
                    break
                if enc is None:
                    enc = encoder_factory() if encoder_factory else content.ImageEncoder(adapter)
                loaded = list(ex.map(load, todo[i:i + wave]))
                good = [x for x in loaded if x[1] == "ok"]
                if good:
                    vecs = enc.encode([x[2] for x in good])
                    for (row, _, _, source, sharp), v in zip(good, vecs):
                        state.save_content(row["file_id"], row["etag"], "ok", source, content.MODEL_NAME, content.to_f16(v), sharp)
                        res.done += 1
                for row, kind, *_ in loaded:
                    if kind == "none":
                        state.save_content(row["file_id"], row["etag"], "no_picture", None, None, None, None)
                        res.no_picture += 1
                    elif kind == "transient":
                        res.transient += 1
                if res.transient >= 20 and res.transient > res.done:
                    res.stopped = "the server keeps failing"
                    break
            else:
                pass
    finally:
        if enc is not None:
            enc.close()
    if stopped() and not res.stopped:
        res.stopped = "time limit"
    return res
