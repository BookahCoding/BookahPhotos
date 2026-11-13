from datetime import datetime, timezone

from bookahphotos_indexer import metadata as m
from bookahphotos_indexer import schema


def utc(y, mo, d, h=0, mi=0, s=0):
    return int(datetime(y, mo, d, h, mi, s, tzinfo=timezone.utc).timestamp())


def test_datetaken_is_wall_clock_minus_recorded_offset():
    # Phase 0 finding: datetaken = local wall time stored as UTC. 2024-08-24 11:46:33 at +03:00 is 08:46:33 UTC.
    wall_as_utc = utc(2024, 8, 24, 11, 46, 33)
    r = m.from_memories({"datetaken": wall_as_utc, "w": 4032, "h": 3024, "exif": {"DateTimeOriginal": "2024:08:24 11:46:33", "OffsetTimeOriginal": "+03:00", "Make": "Apple", "Model": "Apple Phone"}},
                        "image/heic", "Photos/a.heic", "Europe/Athens")
    assert r["taken_utc"] == utc(2024, 8, 24, 8, 46, 33)
    assert r["taken_offset_min"] == 180 and r["taken_zone_assumed"] == 0
    assert r["day_key"] == "2024-08-24"


def test_no_offset_assumes_home_zone_and_says_so():
    r = m.from_memories({"datetaken": utc(2018, 7, 29, 0, 33, 9), "exif": {"CreateDate": "2018:07:29 00:33:09", "Make": "Cam"}}, "image/tiff", "Photos/s.tif", "Europe/Athens")
    assert r["taken_zone_assumed"] == 1
    assert r["taken_offset_min"] == 180          # Athens in July is UTC+3
    assert r["taken_utc"] == utc(2018, 7, 28, 21, 33, 9)
    assert r["day_key"] == "2018-07-29"          # the local day, not the UTC day


def test_winter_time_offset_is_two_hours():
    r = m.from_memories({"datetaken": utc(2025, 12, 25, 15, 35, 4), "exif": {"CreateDate": "2025:12:25 15:35:04"}}, "image/jpeg", "Photos/a.jpg", "Europe/Athens")
    assert r["taken_offset_min"] == 120


def test_midnight_photo_keeps_local_day():
    r = m.from_memories({"datetaken": utc(2024, 1, 1, 0, 0, 5), "exif": {"DateTimeOriginal": "2024:01:01 00:00:05", "OffsetTimeOriginal": "+02:00"}}, "image/jpeg", "Photos/a.jpg", "Europe/Athens")
    assert r["day_key"] == "2024-01-01"
    assert r["taken_utc"] == utc(2023, 12, 31, 22, 0, 5)


def test_no_date_anywhere_gives_none_not_file_time():
    r = m.from_memories({"exif": {}}, "image/png", "Photos/pic.png", "Europe/Athens")
    assert r["taken_utc"] is None and r["taken_source"] == "none" and r["day_key"] is None


def test_video_offset_comes_from_datetimeepoch():
    # Real MOV from the library: local 16:36:58, true UTC 14:36:58. Works for any zone, not only the home zone.
    wall_as_utc = utc(2016, 3, 12, 16, 36, 58)
    r = m.from_memories({"datetaken": wall_as_utc, "exif": {"CreateDate": "2016:03:12 16:36:58+02:00", "DateTimeEpoch": utc(2016, 3, 12, 14, 36, 58)}},
                        "video/quicktime", "Photos/v.mov", "Europe/Athens")
    assert r["taken_utc"] == utc(2016, 3, 12, 14, 36, 58) and r["taken_offset_min"] == 120 and r["taken_zone_assumed"] == 0
    # a video shot in another zone (UTC+9) keeps its own offset, not Athens
    r = m.from_memories({"datetaken": utc(2024, 4, 1, 9, 0, 0), "exif": {"DateTimeEpoch": utc(2024, 4, 1, 0, 0, 0)}}, "video/quicktime", "Photos/v.mov", "Europe/Athens")
    assert r["taken_offset_min"] == 540 and r["taken_utc"] == utc(2024, 4, 1, 0, 0, 0)
    # DateTimeEpoch alone (no CreateDate) is enough to trust datetaken
    r = m.from_memories({"datetaken": utc(2024, 1, 6, 16, 36, 26), "exif": {"DateTimeEpoch": utc(2024, 1, 6, 14, 36, 26)}}, "video/quicktime", "Photos/v.mov", "Europe/Athens")
    assert r["taken_source"] == "memories" and r["taken_offset_min"] == 120
    # nonsense epochs are ignored and the home zone is assumed
    r = m.from_memories({"datetaken": utc(2024, 1, 6, 16, 36, 26), "exif": {"DateTimeEpoch": utc(2024, 1, 6, 3, 1, 1)}}, "video/quicktime", "Photos/v.mov", "Europe/Athens")
    assert r["taken_zone_assumed"] == 1


def test_memories_sync_time_is_not_a_capture_date():
    # Real server behaviour: with no EXIF date, `datetaken` is the upload day. It must be ignored.
    sync_day = utc(2026, 11, 11, 15, 0, 0)
    r = m.from_memories({"datetaken": sync_day, "exif": {"Make": "Cam"}}, "image/png", "Photos/Screenshot.png", "Europe/Athens")
    assert r["taken_utc"] is None and r["taken_source"] == "none"
    r = m.from_memories({"datetaken": sync_day, "exif": {}}, "image/jpeg", "Photos/IMG_20250716_101500.jpg", "Europe/Athens")
    assert r["taken_source"] == "filename" and r["day_key"] == "2025-07-16"


def test_date_from_greek_and_phone_file_names():
    assert m.date_from_name("IMG_20261110_192750.jpg") == datetime(2026, 11, 10, 19, 27, 50)
    assert m.date_from_name("2024-08-24 11.46.33.heic") == datetime(2024, 8, 24, 11, 46, 33)
    assert m.date_from_name("Βάπτιση 20240716.jpg") == datetime(2024, 7, 16, 12, 0, 0)
    assert m.date_from_name("scan_0001.tif") is None
    assert m.date_from_name("IMG_20261340_000000.jpg") is None     # month 13 is not a date


def test_filename_fallback_used_only_when_nothing_else():
    r = m.from_memories({"exif": {}}, "image/jpeg", "Photos/Γιορτή/IMG_20240716_101500.jpg", "Europe/Athens")
    assert r["taken_source"] == "filename" and r["day_key"] == "2024-07-16" and r["taken_zone_assumed"] == 1


def test_gps_zero_zero_and_garbage_ignored():
    r = m.from_memories({"datetaken": 1, "exif": {"CreateDate": "x", "GPSLatitude": 0, "GPSLongitude": 0}}, "image/jpeg", "p/a.jpg", "Europe/Athens")
    assert r["latitude"] is None
    r = m.from_memories({"datetaken": 1, "exif": {"CreateDate": "x", "GPSLatitude": 39.6, "GPSLongitude": 19.9}}, "image/jpeg", "p/a.jpg", "Europe/Athens")
    assert (r["latitude"], r["longitude"]) == (39.6, 19.9)


def test_kind_flags():
    assert m.kind_flags("video/quicktime", "a.mov", 1920, 1080) & schema.FLAG_VIDEO
    assert m.kind_flags("image/png", "Screenshot_2024.png", 1080, 2400) & schema.FLAG_SCREENSHOT
    assert m.kind_flags("image/jpeg", "pano.jpg", 12000, 3000) & schema.FLAG_PANORAMA
    assert m.kind_flags("image/jpeg", "a.jpg", 4000, 3000) == 0


def test_parse_offset():
    assert m.parse_offset("+03:00") == 180 and m.parse_offset("-0530") == -330 and m.parse_offset("Z") is None and m.parse_offset(None) is None


def test_exif_head_fallback_on_generated_jpeg():
    import io
    from PIL import Image
    im = Image.new("RGB", (64, 48), "gray")
    ex = Image.Exif()
    ex[0x010F], ex[0x0110] = "TestMake", "TestModel"
    sub = ex.get_ifd(0x8769)
    sub[0x9003] = "2023:05:06 07:08:09"
    sub[0x9011] = "+03:00"
    buf = io.BytesIO()
    im.save(buf, "JPEG", exif=ex)
    r = m.from_exif_bytes(buf.getvalue(), "image/jpeg", "Photos/x.jpg", "Europe/Athens")
    assert r and r["taken_utc"] == utc(2023, 5, 6, 4, 8, 9) and r["camera"] == "TestMake TestModel" and r["taken_source"] == "exif-head"
    assert m.from_exif_bytes(b"not an image", "image/jpeg", "x.jpg", "Europe/Athens") is None


def test_video_duration_from_memories():
    r = m.from_memories({"datetaken": utc(2024, 1, 6, 16, 36, 26), "exif": {"DateTimeEpoch": utc(2024, 1, 6, 14, 36, 26), "Duration": 2.4757, "TrackDuration": 2.4}}, "video/mp4", "Photos/v.mp4", "Europe/Athens")
    assert r["duration_ms"] == 2476
    assert m.from_memories({"exif": {"TrackDuration": 119.08}}, "video/quicktime", "Photos/v.mov", "Europe/Athens")["duration_ms"] == 119080
    # photos never get a duration, nonsense values are ignored
    assert m.from_memories({"exif": {"Duration": 5}}, "image/jpeg", "Photos/a.jpg", "Europe/Athens")["duration_ms"] is None
    assert m.from_memories({"exif": {"Duration": -1}}, "video/mp4", "Photos/v.mp4", "Europe/Athens")["duration_ms"] is None


def test_epoch_equal_to_wall_clock_is_not_an_offset():
    # Memories reports DateTimeEpoch = the wall clock read as UTC for files without an offset tag; that must not become "UTC+0"
    wall = utc(2026, 11, 12, 6, 36, 55)
    r = m.from_memories({"datetaken": wall, "exif": {"DateTimeOriginal": "2026:11:12 06:36:55", "DateTimeEpoch": wall}}, "image/jpeg", "Photos/Screenshot.jpg", "Europe/Athens")
    assert r["taken_zone_assumed"] == 1 and r["taken_offset_min"] == 120
    assert r["taken_utc"] == utc(2026, 11, 12, 4, 36, 55)
    # an explicit +00:00 tag is still honoured
    r = m.from_memories({"datetaken": wall, "exif": {"DateTimeOriginal": "2026:11:12 06:36:55", "OffsetTimeOriginal": "+00:00", "DateTimeEpoch": wall}}, "image/jpeg", "Photos/a.jpg", "Europe/Athens")
    assert r["taken_zone_assumed"] == 0 and r["taken_offset_min"] == 0 and r["taken_utc"] == wall
