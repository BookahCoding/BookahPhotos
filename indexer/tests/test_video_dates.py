"""Video header dates, with synthetic MP4 bytes (generated here, no real video)."""
from datetime import datetime, timezone

from bookahphotos_indexer import metadata as m
from bookahphotos_indexer import video_dates as v


def box(typ: bytes, payload: bytes) -> bytes:
    return (len(payload) + 8).to_bytes(4, "big") + typ + payload


def mvhd(unix_utc: int, timescale: int = 1000, duration: int = 5000, version: int = 0) -> bytes:
    created = unix_utc + v.EPOCH_1904
    if version == 0:
        body = b"\x00\x00\x00\x00" + created.to_bytes(4, "big") + created.to_bytes(4, "big") + timescale.to_bytes(4, "big") + duration.to_bytes(4, "big")
    else:
        body = b"\x01\x00\x00\x00" + created.to_bytes(8, "big") + created.to_bytes(8, "big") + timescale.to_bytes(4, "big") + duration.to_bytes(8, "big")
    return box(b"mvhd", body + b"\x00" * 80)


def reader(data: bytes) -> v.Reader:
    return lambda off, n: data[off:off + n]


UTC_2018 = int(datetime(2018, 7, 28, 21, 33, 9, tzinfo=timezone.utc).timestamp())


def test_moov_at_the_start():
    f = box(b"ftyp", b"isom0000") + box(b"moov", mvhd(UTC_2018)) + box(b"mdat", b"x" * 5000)
    h = v.read_video_header(reader(f), len(f))
    assert h and h["creation_utc"] == UTC_2018 and h["duration_ms"] == 5000


def test_moov_at_the_end_is_found_without_reading_the_big_mdat():
    reads = []
    f = box(b"ftyp", b"isom0000") + box(b"mdat", b"x" * 3_000_000) + box(b"moov", mvhd(UTC_2018, version=1))
    def rd(off, n):
        reads.append(n)
        return f[off:off + n]
    h = v.read_video_header(rd, len(f))
    assert h and h["creation_utc"] == UTC_2018
    assert max(reads) < 1000          # only headers and the small moov were read, never the 3 MB of video data


def test_unset_or_silly_creation_time_is_not_a_date():
    f = box(b"ftyp", b"isom0000") + box(b"moov", mvhd(-v.EPOCH_1904 + 0))      # creation_time 0 = not set
    h = v.read_video_header(reader(f), len(f))
    assert h is None or h["creation_utc"] is None
    future = int(datetime(2090, 1, 1, tzinfo=timezone.utc).timestamp())
    f = box(b"ftyp", b"isom0000") + box(b"moov", mvhd(future, version=1))
    h = v.read_video_header(reader(f), len(f))
    assert h is None or h["creation_utc"] is None


def test_iphone_mov_local_time_with_offset():
    qt = b"com.apple.quicktime.creationdate" + b"\x00" * 8 + b"2016-03-12T16:36:58+0200"
    f = box(b"ftyp", b"qt  0000") + box(b"moov", mvhd(UTC_2018) + box(b"meta", qt))
    h = v.read_video_header(reader(f), len(f))
    assert h["local"] == (datetime(2016, 3, 12, 16, 36, 58), 120)


def test_garbage_and_truncated_files_do_not_crash():
    assert v.read_video_header(reader(b"not a video at all"), 18) is None
    assert v.read_video_header(reader(b""), 0) is None
    f = box(b"ftyp", b"isom0000") + (10_000_000).to_bytes(4, "big") + b"moov"      # moov claims 10 MB but the file ends
    h = v.read_video_header(reader(f), len(f))
    assert h is None


def test_columns_for_a_video_dated_from_its_header():
    h = {"creation_utc": UTC_2018, "duration_ms": 5000, "local": None}
    r = m.from_video_header(h, "video/mp4", "Photos/x/VID_0001.mp4", "Europe/Athens")
    assert r["taken_utc"] == UTC_2018 and r["taken_source"] == "video-header" and r["taken_zone_assumed"] == 1
    assert r["taken_offset_min"] == 180 and r["day_key"] == "2018-07-29"       # 21:33 UTC is 00:33 the next day in Athens in summer
    assert r["duration_ms"] == 5000
    # an iPhone MOV with a recorded local time keeps its own offset and day
    h = {"creation_utc": UTC_2018, "duration_ms": None, "local": (datetime(2016, 3, 12, 16, 36, 58), 120)}
    r = m.from_video_header(h, "video/quicktime", "Photos/a.mov", "Europe/Athens")
    assert r["taken_offset_min"] == 120 and r["taken_zone_assumed"] == 0 and r["day_key"] == "2016-03-12"
    # no usable header: the file-name date still works
    r = m.from_video_header({}, "video/mp4", "Photos/VID_20240716_101500.mp4", "Europe/Athens")
    assert r["taken_source"] == "filename" and r["day_key"] == "2024-07-16"
