"""Creation date and length of a video, read from its own header with a few small range requests (never the whole file).

Phone videos (MP4 and QuickTime MOV) keep their creation time in the `mvhd` box inside `moov`, in seconds since 1904 UTC. iPhone MOV files also
keep the local time with its offset in the `com.apple.quicktime.creationdate` item. The `moov` box is at the start of the file or at the end,
so the top-level boxes are walked with 16-byte reads until it is found, then only that box is read.
"""
from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Callable

EPOCH_1904 = 2_082_844_800
MAX_MOOV = 24 * 1024 * 1024
_QT_DATE = re.compile(rb"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})([+-])(\d{2}):?(\d{2})")

Reader = Callable[[int, int], bytes]       # read(offset, length) -> bytes (may be shorter at the end of the file)


def find_moov(read: Reader, size: int) -> tuple[int, int] | None:
    """Position and size of the top-level `moov` box, or None."""
    pos, hops = 0, 0
    while pos + 8 <= size and hops < 64:
        hdr = read(pos, 16)
        if len(hdr) < 8:
            return None
        sz, typ, hdrlen = int.from_bytes(hdr[:4], "big"), hdr[4:8], 8
        if sz == 1 and len(hdr) >= 16:
            sz, hdrlen = int.from_bytes(hdr[8:16], "big"), 16
        elif sz == 0:
            sz = size - pos
        if sz < hdrlen:
            return None
        if typ == b"moov":
            return pos, sz
        pos += sz
        hops += 1
    return None


def _children(data: bytes, start: int, end: int):
    pos = start
    while pos + 8 <= end:
        sz, typ, hdrlen = int.from_bytes(data[pos:pos + 4], "big"), data[pos + 4:pos + 8], 8
        if sz == 1:
            sz, hdrlen = int.from_bytes(data[pos + 8:pos + 16], "big"), 16
        elif sz == 0:
            sz = end - pos
        if sz < hdrlen or pos + sz > end:
            return
        yield typ, pos + hdrlen, pos + sz
        pos += sz


def parse_moov(moov: bytes) -> dict:
    """{'creation_utc': epoch seconds or None, 'duration_ms': int or None, 'local': (datetime-without-zone, offset_minutes) or None}."""
    out: dict = {"creation_utc": None, "duration_ms": None, "local": None}
    for typ, s, e in _children(moov, 8, len(moov)):
        if typ == b"mvhd" and e - s >= 20:
            version = moov[s]
            if version == 1 and e - s >= 32:
                creation = int.from_bytes(moov[s + 4:s + 12], "big"); timescale = int.from_bytes(moov[s + 20:s + 24], "big"); duration = int.from_bytes(moov[s + 24:s + 32], "big")
            else:
                creation = int.from_bytes(moov[s + 4:s + 8], "big"); timescale = int.from_bytes(moov[s + 12:s + 16], "big"); duration = int.from_bytes(moov[s + 16:s + 20], "big")
            if creation > EPOCH_1904 + 631_152_000:            # 0 and tiny values mean "not set"; also nothing before 1990
                out["creation_utc"] = creation - EPOCH_1904
            if timescale and duration:
                out["duration_ms"] = int(round(duration * 1000 / timescale))
    m = _QT_DATE.search(moov)
    if m and b"quicktime.creationdate" in moov:
        y, mo, d, h, mi, se, sign, oh, om = m.groups()
        try:
            wall = datetime(int(y), int(mo), int(d), int(h), int(mi), int(se))
        except ValueError:
            wall = None
        if wall is not None:
            off = (int(oh) * 60 + int(om)) * (-1 if sign == b"-" else 1)
            out["local"] = (wall, off)
    return out


def read_video_header(read: Reader, size: int) -> dict | None:
    """Date and length of a video, or None when the header cannot be found or read. Reads at most the `moov` box."""
    found = find_moov(read, size)
    if not found:
        return None
    pos, sz = found
    if sz > MAX_MOOV:
        return None
    data = read(pos, sz)
    if len(data) < 16:
        return None
    info = parse_moov(data)
    now = datetime.now(timezone.utc).timestamp() + 86_400
    if info["creation_utc"] is not None and info["creation_utc"] > now:
        info["creation_utc"] = None
    return info if (info["creation_utc"] or info["local"] or info["duration_ms"]) else None
