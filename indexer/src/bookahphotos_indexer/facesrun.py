"""Faces session: reads each picture from the local preview cache (Nextcloud is asked only for pictures missing from the cache, 2 at a time),
finds and describes faces on the CPU, saves at once. Resumable; a second run does nothing."""
from __future__ import annotations

import io
import logging
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np

from . import contentrun, faces
from .dav import Dav
from .state import State

log = logging.getLogger("bookahphotos.faces")


@dataclass
class FacesResult:
    photos: int = 0
    faces: int = 0
    usable: int = 0
    no_picture: int = 0
    transient: int = 0
    stopped: str = ""


def run_faces(dav: Dav | None, state: State, cache_dir: Path, stop: threading.Event | None = None, limit: int | None = None,
              workers: int | None = None, wave: int = 64, engine_factory: Callable[[], object] | None = None) -> FacesResult:
    res = FacesResult()
    cache_dir.mkdir(parents=True, exist_ok=True)
    todo =state.pending_faces(faces.FACE_MODEL)[: limit or None]
    if not todo:
        return res
    workers = workers or max(1, min(4, (os.cpu_count() or 4) // 4))
    net = threading.Semaphore(2)                    # at most 2 Nextcloud requests at a time, as everywhere
    local = threading.local()
    lock = threading.Lock()
    dummy = contentrun.ContentResult()
    make_engine = engine_factory or faces.FaceEngine

    def engine():
        if not hasattr(local, "e"):                  # OpenCV's detector is not thread safe: one per thread
            local.e = make_engine()
        return local.e

    def one(row):
        if stop is not None and stop.is_set():
            return row, "stop", None
        try:
            with net:
                data, source = contentrun.fetch_picture(dav, row, cache_dir, lock, dummy)
            if data is None:
                return row, "none", None
            if source == "original":                 # decode_picture writes the upright 1024 px copy; read that, not the sideways original
                if contentrun.decode_picture(data, source, row, cache_dir) is None:
                    return row, "none", None
                data = contentrun.cache_path(cache_dir, row["file_id"], row["etag"], 1024).read_bytes()
            from PIL import Image
            rgb = np.asarray(Image.open(io.BytesIO(data)).convert("RGB"))
            return row, "ok", engine().detect_and_embed(rgb)
        except contentrun._Transient:
            return row, "transient", None
        except Exception:
            log.exception("face reading failed for file id %s", row["file_id"])
            return row, "none", None

    with ThreadPoolExecutor(workers) as ex:
        for i in range(0, len(todo), wave):
            if stop is not None and stop.is_set():
                res.stopped = "time limit"
                break
            for row, kind, found in ex.map(one, todo[i:i + wave]):
                if kind == "ok":
                    rows = [(f.x1, f.y1, f.x2, f.y2, f.score, f.width_1280, int(f.usable), f.emb.astype(np.float32).tobytes()) for f in found]
                    state.save_faces(row["file_id"], row["etag"], faces.FACE_MODEL, rows)
                    res.photos += 1
                    res.faces += len(rows)
                    res.usable += sum(r[6] for r in rows)
                elif kind == "none":
                    state.save_faces(row["file_id"], row["etag"], faces.FACE_MODEL, [])      # nothing to read: final until the file changes
                    res.no_picture += 1
                elif kind == "transient":
                    res.transient += 1
    if stop is not None and stop.is_set() and not res.stopped:
        res.stopped = "time limit"
    return res
