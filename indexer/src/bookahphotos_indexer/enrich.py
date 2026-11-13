"""The slower steps after listing: reading metadata for new files and making placeholders. Both are resumable and gentle."""
from __future__ import annotations

import io
import logging
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass

import thumbhash
from PIL import Image

from . import metadata, schema, video_dates
from .config import Config
from .dav import Dav, DavError
from .places import PlaceIndex
from .state import State

log = logging.getLogger("bookahphotos.enrich")

# Bump this whenever the rules that turn Memories/EXIF answers into columns change; every file is then read again on the next run.
METADATA_VERSION = 5   # 2: videos use DateTimeEpoch for the real UTC offset; 3: videos also get duration_ms; 4: epoch delta 0 is not an offset; 5: videos unknown to Memories are dated from their own header
NON_VIDEO_VERSION = 2  # versions 3 and 4 changed nothing for most photos; the files affected by 4 are marked by `state.reset_offset_zero()`


@dataclass
class EnrichResult:
    metadata_done: int = 0
    metadata_fallback_head: int = 0
    metadata_no_exif: int = 0
    metadata_failed: int = 0
    metadata_undated: int = 0
    metadata_gave_up: int = 0
    metadata_video_header: int = 0
    previews_done: int = 0
    previews_failed: int = 0


def make_thumbhash(jpeg_bytes: bytes) -> bytes:
    im = Image.open(io.BytesIO(jpeg_bytes)).convert("RGBA")
    im.thumbnail((100, 100))  # ThumbHash input must be at most 100x100
    return bytes(thumbhash.rgba_to_thumb_hash(im.width, im.height, list(im.tobytes())))


def enrich_metadata(dav: Dav, state: State, cfg: Config, places: PlaceIndex | None, limit: int | None = None) -> EnrichResult:
    res = EnrichResult()
    for row in state.pending_metadata(METADATA_VERSION, non_video_version=NON_VIDEO_VERSION)[: limit or None]:
        fid, path, mime = row["file_id"], row["path"], row["mime"]
        try:
            info = dav.memories_info(fid)
            fields = metadata.from_memories(info, mime, path, cfg.home_zone) if info else None
            if fields is None and mime in ("image/jpeg", "image/heic", "image/heif") and row["size"] > 0:
                head = dav.get(path, (0, 262_143))
                fields = metadata.from_exif_bytes(head, mime, path, cfg.home_zone)
                if fields:
                    res.metadata_fallback_head += 1
            if fields is None and mime.startswith("video/") and row["size"] > 0:
                # Memories does not know this video: read its creation date and length from its own header (a few small range requests)
                try:
                    header = video_dates.read_video_header(lambda off, n: dav.get(path, (off, off + n - 1)), row["size"])
                except Exception:     # any trouble reading the header just means: no date from it
                    header = None
                if header:
                    fields = metadata.from_video_header(header, mime, path, cfg.home_zone)
                    res.metadata_video_header += 1
            if fields is None:
                fields = metadata.from_memories({}, mime, path, cfg.home_zone)   # file name date at best
                res.metadata_no_exif += 1
            if fields.get("taken_utc") is None:
                res.metadata_undated += 1
            if places and fields.get("latitude") is not None:
                p = places.nearest(fields["latitude"], fields["longitude"])
                fields["place_id"] = p.id if p else None
            state.set_fields(fid, fields, level=schema.LEVEL_METADATA, meta_version=METADATA_VERSION)
            res.metadata_done += 1
        except DavError:
            state.bump_meta_try(fid)
            res.metadata_failed += 1
            if state.get_file(fid)["meta_tries"] >= 3:
                # give up reading it, but keep the photo in the library: file-name date at best, no date otherwise
                state.set_fields(fid, metadata.from_memories({}, mime, path, cfg.home_zone), level=schema.LEVEL_METADATA, meta_version=0)
                res.metadata_gave_up += 1
    return res


def enrich_previews(dav: Dav, state: State, cfg: Config, limit: int | None = None, stop: threading.Event | None = None) -> EnrichResult:
    """Placeholders from the 256 px preview. At most cfg.preview_workers requests at a time; every result is saved at once so a stop loses nothing."""
    res = EnrichResult()
    todo = state.pending_previews(cfg.tiff_last)[: limit or None]
    lock = threading.Lock()

    STOPPED = b""

    def one(row) -> tuple[int, bytes | None]:
        if stop is not None and stop.is_set():
            return row["file_id"], STOPPED         # not attempted: must not count as a failure
        try:
            return row["file_id"], make_thumbhash(dav.preview(row["file_id"], cfg.preview_size))
        except Exception:     # a broken preview must not stop the run; it is retried on the next run (up to 3 times)
            return row["file_id"], None

    with ThreadPoolExecutor(max(1, cfg.preview_workers)) as ex:
        # submit in small waves so that the priority order (TIFF last) is respected and a stop takes effect quickly
        for i in range(0, len(todo), 20):
            futs = [ex.submit(one, r) for r in todo[i:i + 20]]
            for f in as_completed(futs):
                fid, blob = f.result()
                with lock:
                    if blob == STOPPED:
                        continue
                    if blob:
                        state.set_thumbhash(fid, blob)
                        res.previews_done += 1
                    else:
                        state.bump_preview_try(fid)
                        res.previews_failed += 1
            if stop is not None and stop.is_set():
                break
    return res
