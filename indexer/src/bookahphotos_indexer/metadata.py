"""Turning what Nextcloud and Memories know about a file into snapshot columns.

Checked in Phase 0 on real files: the Memories field `datetaken` is the camera's local wall-clock time stored as if it were UTC,
and it ignores the EXIF UTC offset. So the real instant is `datetaken - offset`. When no offset was recorded the home zone is assumed
and the date is marked as assumed.
"""
from __future__ import annotations

import io
import json
import re
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from . import schema

_NAME_DATE = re.compile(r"(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])[-_. T]?([01]\d|2[0-3])?[-_.:]?([0-5]\d)?[-_.:]?([0-5]\d)?(?!\d)")
DATE_KEYS = ("DateTimeOriginal", "CreateDate", "DateTimeDigitized", "CreationDate", "MediaCreateDate", "TrackCreateDate", "DateTimeEpoch")
_OFFSET = re.compile(r"^([+-])(\d{2}):?(\d{2})$")
_SCREENSHOT = re.compile(r"screenshot|screen[ _-]?shot|στιγμιότυπο|εικόνα οθόνης", re.IGNORECASE)


def parse_offset(value: object) -> int | None:
    """'+03:00' -> 180, '-0530' -> -330, anything else -> None."""
    if not isinstance(value, str):
        return None
    m = _OFFSET.match(value.strip())
    if not m:
        return None
    minutes = int(m.group(2)) * 60 + int(m.group(3))
    return -minutes if m.group(1) == "-" else minutes


def parse_exif_datetime(value: object) -> datetime | None:
    if not isinstance(value, str):
        return None
    for fmt in ("%Y:%m:%d %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%Y:%m:%d %H:%M:%S.%f"):
        try:
            return datetime.strptime(value.strip()[:26], fmt)
        except ValueError:
            continue
    return None


def date_from_name(name: str) -> datetime | None:
    """Local wall-clock date taken from file names like IMG_20261110_192750.jpg or 2024-08-24 11.46.33.heic."""
    m = _NAME_DATE.search(name)
    if not m:
        return None
    y, mo, d, hh, mm, ss = m.groups()
    try:
        return datetime(int(y), int(mo), int(d), int(hh or 12), int(mm or 0), int(ss or 0))
    except ValueError:
        return None


def resolve_time(wall: datetime, offset_min: int | None, home_zone: str) -> tuple[int, int, bool]:
    """Wall-clock time (naive) + optional recorded offset -> (utc_epoch, offset_minutes, zone_assumed)."""
    if offset_min is not None:
        utc = wall.replace(tzinfo=timezone.utc).timestamp() - offset_min * 60
        return int(utc), offset_min, False
    aware = wall.replace(tzinfo=ZoneInfo(home_zone))
    off = int(aware.utcoffset().total_seconds() // 60)
    return int(aware.astimezone(timezone.utc).timestamp()), off, True


def kind_flags(mime: str, path: str, width: int | None, height: int | None) -> int:
    flags = 0
    name = path.rsplit("/", 1)[-1]
    if mime.startswith("video/"):
        flags |= schema.FLAG_VIDEO
    elif _SCREENSHOT.search(name):
        flags |= schema.FLAG_SCREENSHOT
    if width and height and not mime.startswith("video/") and max(width, height) >= 3 * min(width, height):
        flags |= schema.FLAG_PANORAMA
    return flags


def _num(v: object) -> float | None:
    try:
        return float(v)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


def from_memories(info: dict, mime: str, path: str, home_zone: str) -> dict:
    """Columns from the Memories `image/info` answer. Never raises on odd data; missing things stay None."""
    exif = info.get("exif") if isinstance(info.get("exif"), dict) else {}
    out: dict = {"width": info.get("w") or None, "height": info.get("h") or None, "taken_source": "none", "taken_utc": None,
                 "taken_offset_min": None, "taken_zone_assumed": 0, "day_key": None, "latitude": None, "longitude": None}
    wall = None
    dt = info.get("datetaken")
    # Found on a real server: for a file with no capture date in its EXIF, Memories fills `datetaken` with the file's sync time
    # (the day it was uploaded). That must not become the photo's date, so `datetaken` is trusted only when the EXIF has a real date tag.
    has_exif_date = any(exif.get(k) for k in DATE_KEYS)
    if isinstance(dt, (int, float)) and dt > 0 and has_exif_date:
        wall = datetime.fromtimestamp(dt, tz=timezone.utc).replace(tzinfo=None)
        out["taken_source"] = "memories"
    if wall is None:
        wall = parse_exif_datetime(exif.get("DateTimeOriginal") or exif.get("CreateDate"))
        if wall:
            out["taken_source"] = "exif"
    offset = parse_offset(exif.get("OffsetTimeOriginal")) if exif else None
    if offset is None and exif:
        offset = parse_offset(exif.get("OffsetTime"))
    # Videos: Memories also gives `DateTimeEpoch`, the true UTC instant. The difference to the local wall time is the real offset.
    epoch = exif.get("DateTimeEpoch") if exif else None
    if offset is None and wall is not None and out["taken_source"] == "memories" and isinstance(epoch, (int, float)) and epoch > 0:
        delta = round((wall.replace(tzinfo=timezone.utc).timestamp() - epoch) / 60)
        # delta 0 means "the epoch is just the wall clock read as UTC", which is what Memories reports for files whose EXIF has no
        # offset (found on the real library: 15,000 photos and screenshots were stored as UTC+0 and sorted two hours late). It carries no
        # information, so the home zone is assumed instead. A camera that really is on UTC+0 writes an explicit offset tag.
        if delta != 0 and -14 * 60 <= delta <= 14 * 60 and delta % 15 == 0:
            offset = int(delta)
    if wall is None:
        wall = date_from_name(path.rsplit("/", 1)[-1])
        if wall:
            out["taken_source"] = "filename"
    if wall is not None:
        utc, off, assumed = resolve_time(wall, offset, home_zone)
        out.update(taken_utc=utc, taken_offset_min=off, taken_zone_assumed=int(assumed), day_key=wall.strftime("%Y-%m-%d"))
    lat, lon = _num(exif.get("GPSLatitude")), _num(exif.get("GPSLongitude"))
    if lat is not None and lon is not None and -90 <= lat <= 90 and -180 <= lon <= 180 and not (lat == 0 and lon == 0):
        out.update(latitude=lat, longitude=lon)
    make, model = (exif.get("Make") or "").strip(), (exif.get("Model") or "").strip()
    out["camera"] = (model if model.startswith(make) else f"{make} {model}").strip() or None
    out["lens"] = (exif.get("LensModel") or "").strip() or None
    exposure = {k: exif.get(k) for k in ("ExposureTime", "FNumber", "ISO", "FocalLength") if exif.get(k) is not None}
    out["exposure"] = json.dumps(exposure) if exposure else None
    out["flags"] = kind_flags(mime, path, out["width"], out["height"])
    # Videos: Memories gives the length in seconds (`Duration`, or `TrackDuration`). Stored in milliseconds.
    out["duration_ms"] = None
    if mime.startswith("video/") and exif:
        secs = _num(exif.get("Duration")) or _num(exif.get("TrackDuration"))
        if secs is not None and 0 < secs < 24 * 3600:
            out["duration_ms"] = int(round(secs * 1000))
    return out


def from_exif_bytes(head: bytes, mime: str, path: str, home_zone: str) -> dict | None:
    """Fallback for files Memories has not indexed: read EXIF from the first bytes of the original (works for JPEG; HEIC may not)."""
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(head))
        ex = im.getexif()
        sub = ex.get_ifd(0x8769)
        gps = ex.get_ifd(0x8825)
    except Exception:
        return None
    info: dict = {"w": None, "h": None, "exif": {}}
    e = info["exif"]
    e["Make"], e["Model"] = ex.get(0x010F) or "", ex.get(0x0110) or ""
    e["DateTimeOriginal"] = sub.get(0x9003) or ex.get(0x0132)
    e["OffsetTimeOriginal"], e["OffsetTime"] = sub.get(0x9011), sub.get(0x9010)
    e["LensModel"] = sub.get(0xA434)
    if gps and 2 in gps and 4 in gps:
        try:
            lat = sum(float(v) / 60 ** i for i, v in enumerate(gps[2]))
            lon = sum(float(v) / 60 ** i for i, v in enumerate(gps[4]))
            e["GPSLatitude"] = -lat if gps.get(1) == "S" else lat
            e["GPSLongitude"] = -lon if gps.get(3) == "W" else lon
        except Exception:
            pass
    if not e["DateTimeOriginal"] and not e["Make"] and "GPSLatitude" not in e:
        return None
    out = from_memories(info, mime, path, home_zone)
    if out["taken_source"] == "exif":
        out["taken_source"] = "exif-head"
    return out


def from_video_header(header: dict, mime: str, path: str, home_zone: str) -> dict:
    """Columns for a video Memories does not know, from its own header (see video_dates.py). Falls back to the file-name date.

    The creation time in the header is a true UTC instant. An iPhone MOV also carries the local time with its offset; otherwise the home
    zone is assumed and the date is marked as assumed.
    """
    out = from_memories({}, mime, path, home_zone)
    local = header.get("local")
    utc = header.get("creation_utc")
    if local is not None:
        wall, off = local
        t, off, assumed = resolve_time(wall, off, home_zone)
        out.update(taken_source="video-header", taken_utc=t, taken_offset_min=off, taken_zone_assumed=0, day_key=wall.strftime("%Y-%m-%d"))
    elif utc:
        zone = ZoneInfo(home_zone)
        aware = datetime.fromtimestamp(utc, tz=timezone.utc).astimezone(zone)
        off = int(aware.utcoffset().total_seconds() // 60)
        out.update(taken_source="video-header", taken_utc=int(utc), taken_offset_min=off, taken_zone_assumed=1, day_key=aware.strftime("%Y-%m-%d"))
    if header.get("duration_ms"):
        out["duration_ms"] = header["duration_ms"]
    return out
