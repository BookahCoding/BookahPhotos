"""Enrich, build and publish against fakes."""
import io
import sqlite3

import pytest
from PIL import Image

from bookahphotos_indexer import build, enrich, publish, schema
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.dav import DavError, WriteRefused, Dav
from bookahphotos_indexer.places import Place, PlaceIndex
from bookahphotos_indexer.scan import scan
from bookahphotos_indexer.snapshot import build_manifest
from bookahphotos_indexer.state import State
from fakes import FakeDav


def jpeg(color=(200, 80, 40), exif_date=None) -> bytes:
    im = Image.new("RGB", (64, 48), color)
    buf = io.BytesIO()
    if exif_date:
        ex = Image.Exif()
        ex.get_ifd(0x8769)[0x9003] = exif_date
        im.save(buf, "JPEG", exif=ex)
    else:
        im.save(buf, "JPEG")
    return buf.getvalue()


class PipeDav(FakeDav):
    """FakeDav plus the calls the enrich/publish steps use."""
    def __init__(self):
        super().__init__()
        self.info: dict[int, dict] = {}
        self.blobs: dict[str, bytes] = {}
        self.remote: dict[str, bytes] = {}
        self.fail_preview: set[int] = set()
        self.preview_calls = 0

    def memories_info(self, fid):
        if fid == -1:
            raise DavError("boom")
        return self.info.get(fid)

    def get(self, path, byte_range=None):
        return self.blobs[path][: (byte_range[1] + 1) if byte_range else None]

    def preview(self, fid, size=256):
        self.preview_calls += 1
        if fid in self.fail_preview:
            raise DavError("no preview")
        return jpeg()

    # writes
    def mkcol(self, p): self._w(p)
    def _w(self, p):
        if not (p + "/").startswith("BookahPhotos/"):
            raise WriteRefused("no")
    def put(self, p, d): self._w(p); self.remote[p] = d
    def move(self, a, b): self._w(a); self._w(b); self.remote[b] = self.remote.pop(a)
    def delete(self, p): self._w(p); self.remote.pop(p, None)
    def size_of(self, p): return len(self.remote[p]) if p in self.remote else None
    def list_folder(self, path):
        if path.startswith("BookahPhotos/"):
            from bookahphotos_indexer.dav import Entry
            kids = [Entry(k, False, "e", 1, len(v), "", "") for k, v in self.remote.items() if k.startswith(path + "/") and "/" not in k[len(path) + 1:]]
            return Entry(path, True, "e", 1, 0, "", ""), kids
        return super().list_folder(path)


@pytest.fixture
def env(tmp_path):
    cfg = Config(roots=["Photos"], safeguard_min_known=10, preview_workers=2)
    dav = PipeDav()
    a = dav.add("Photos/a.jpg"); b = dav.add("Photos/b.jpg"); c = dav.add("Photos/c.tif", mime="image/tiff"); d = dav.add("Photos/d.mov", mime="video/quicktime")
    dav.info[a] = {"datetaken": 1_700_000_000, "w": 4000, "h": 3000, "exif": {"DateTimeOriginal": "2023:11:14 22:13:20", "OffsetTimeOriginal": "+02:00", "GPSLatitude": 39.62, "GPSLongitude": 19.92, "Make": "T", "Model": "T Phone"}}
    dav.blobs["Photos/b.jpg"] = jpeg(exif_date="2023:05:06 07:08:09")           # Memories has not indexed b: the file head is used
    st = State(tmp_path / "s.db")
    places = PlaceIndex([Place(1, "Testville", "Δοκιμή", "R", "XX", 39.6, 19.9)])
    return cfg, dav, st, places, (a, b, c, d)


def test_metadata_sources_and_places(env):
    cfg, dav, st, places, (a, b, c, d) = env
    rid = st.start_run(); scan(dav, st, cfg, rid)
    r = enrich.enrich_metadata(dav, st, cfg, places)
    assert r.metadata_done == 4 and r.metadata_fallback_head == 1 and r.metadata_no_exif == 2 and r.metadata_failed == 0
    ra, rb, rd = st.get_file(a), st.get_file(b), st.get_file(d)
    assert ra["taken_source"] == "memories" and ra["place_id"] == 1 and ra["width"] == 4000
    assert rb["taken_source"] == "exif-head" and rb["day_key"] == "2023-05-06"
    assert rd["flags"] & schema.FLAG_VIDEO and rd["taken_utc"] is None
    assert all(st.get_file(x)["level"] == 1 for x in (a, b, c, d))


def test_metadata_error_is_retried_three_times_then_the_photo_stays_in_the_library(env):
    cfg, dav, st, places, (a, *_ ) = env
    dav.add("Photos/IMG_20240716_101500.jpg", file_id=-1)         # Memories answers with an error for this one
    rid = st.start_run(); scan(dav, st, cfg, rid)
    results = [enrich.enrich_metadata(dav, st, cfg, places) for _ in range(4)]
    row = st.get_file(-1)
    assert row["meta_tries"] == 3 and results[2].metadata_gave_up == 1
    assert row["level"] == 1 and row["day_key"] == "2024-07-16"      # kept, dated from the file name, not lost
    assert st.count_stuck() == (0, 0)


def test_files_made_with_older_rules_are_read_again(env):
    cfg, dav, st, places, (a, b, c, d) = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    assert enrich.enrich_metadata(dav, st, cfg, places).metadata_done == 0          # nothing pending now
    st.db.execute("UPDATE files SET meta_version=0"); st.db.commit()               # as if the rules had been improved since
    assert enrich.enrich_metadata(dav, st, cfg, places).metadata_done == 4
    assert st.reset_metadata() == 4 and len(st.pending_metadata(enrich.METADATA_VERSION)) == 4


def test_old_state_files_are_migrated(tmp_path):
    import sqlite3
    db = sqlite3.connect(tmp_path / "old.db")
    db.executescript("CREATE TABLE files (file_id INTEGER PRIMARY KEY, path TEXT NOT NULL, etag TEXT NOT NULL, size INTEGER NOT NULL, mime TEXT NOT NULL, "
                     "deleted INTEGER NOT NULL DEFAULT 0, last_seen INTEGER NOT NULL DEFAULT 0, level INTEGER NOT NULL DEFAULT 0, meta_tries INTEGER NOT NULL DEFAULT 0, "
                     "preview_tries INTEGER NOT NULL DEFAULT 0, thumbhash BLOB, taken_utc INTEGER, taken_offset_min INTEGER, taken_zone_assumed INTEGER NOT NULL DEFAULT 0, "
                     "taken_source TEXT, day_key TEXT, width INTEGER, height INTEGER, duration_ms INTEGER, latitude REAL, longitude REAL, place_id INTEGER, camera TEXT, "
                     "lens TEXT, exposure TEXT, flags INTEGER NOT NULL DEFAULT 0, content_hash TEXT, duplicate_group INTEGER);"
                     "INSERT INTO files(file_id, path, etag, size, mime, level) VALUES (1,'p','e',1,'image/jpeg',1);")
    db.commit(); db.close()
    st = State(tmp_path / "old.db")
    assert st.get_file(1)["meta_version"] == 1


def test_previews_tiff_last_and_failures_counted(env):
    cfg, dav, st, places, (a, b, c, d) = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    order = [r["file_id"] for r in st.pending_previews(True)]
    assert order[-1] == c and order.index(d) > order.index(b)                  # photos, then video, TIFF last
    dav.fail_preview.add(b)
    r = enrich.enrich_previews(dav, st, cfg)
    assert r.previews_done == 3 and r.previews_failed == 1 and st.get_file(b)["preview_tries"] == 1
    assert st.get_file(a)["thumbhash"] and len(st.get_file(a)["thumbhash"]) > 10


def test_stop_event_does_not_count_failures(env):
    import threading
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    stop = threading.Event(); stop.set()
    r = enrich.enrich_previews(dav, st, cfg, None, stop)
    assert r.previews_done == 0 and r.previews_failed == 0 and all(x["preview_tries"] == 0 for x in st.live_rows())


def test_build_digest_and_snapshot_content(env, tmp_path):
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places); enrich.enrich_previews(dav, st, cfg)
    d1 = build.rows_digest(st)
    assert build.rows_digest(st) == d1
    snap = build.build_snapshot(st, cfg, places, tmp_path / "x.db", 1)
    conn = sqlite3.connect(snap)
    assert conn.execute("SELECT COUNT(*) FROM assets").fetchone()[0] == 4
    assert conn.execute("SELECT name FROM places").fetchall() == [("Testville",)]
    assert conn.execute("SELECT value FROM meta WHERE key='generation'").fetchone()[0] == "1"
    st.set_fields(ids[0], {"camera": "other"})
    assert build.rows_digest(st) != d1


def test_unchanged_library_gives_the_same_digest_after_another_run(env):
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places); enrich.enrich_previews(dav, st, cfg)
    d1 = build.rows_digest(st)
    rid = st.start_run(); scan(dav, st, cfg, rid)          # a second run: only the bookkeeping (last_seen) changes
    enrich.enrich_metadata(dav, st, cfg, places); enrich.enrich_previews(dav, st, cfg)
    assert build.rows_digest(st) == d1


def test_deleted_files_leave_the_snapshot(env, tmp_path):
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    dav.remove("Photos/a.jpg")
    rid = st.start_run(); scan(dav, st, cfg, rid, accept_deletions=True)
    snap = build.build_snapshot(st, cfg, places, tmp_path / "x.db", 2)
    assert sqlite3.connect(snap).execute("SELECT COUNT(*) FROM assets").fetchone()[0] == 3


def test_publish_order_manifest_last_and_pruning(env, tmp_path):
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    folder = "BookahPhotos/test-root/idx"
    for g in (1, 2, 3):
        snap = build.build_snapshot(st, cfg, places, tmp_path / publish.snapshot_name(g), g)
        publish.publish(dav, folder, snap, build_manifest(snap, g), keep=2)
    names = sorted(k.rsplit("/", 1)[-1] for k in dav.remote)
    assert names == [".nomedia", "bookahphotos-g000002.db", "bookahphotos-g000003.db", "manifest.json"]
    assert b'"generation": 3' in dav.remote[f"{folder}/manifest.json"]


def test_publish_does_not_write_manifest_if_upload_is_short(env, tmp_path, monkeypatch):
    cfg, dav, st, places, ids = env
    rid = st.start_run(); scan(dav, st, cfg, rid); enrich.enrich_metadata(dav, st, cfg, places)
    snap = build.build_snapshot(st, cfg, places, tmp_path / "bookahphotos-g000001.db", 1)
    real_put = dav.put
    dav.put = lambda p, d: real_put(p, d[:-5] if "/upload-bookahphotos" in p else d)      # a truncated upload
    with pytest.raises(publish.PublishError):
        publish.publish(dav, "BookahPhotos/test-root/idx", snap, build_manifest(snap, 1))
    assert "BookahPhotos/test-root/idx/manifest.json" not in dav.remote


def test_writes_outside_bookahphotos_are_refused():
    d = Dav("https://example.invalid", "u", "p")
    for bad in ("Photos/x.jpg", "Photos/BookahPhotos/x", "BookahPhotos/../Photos/x", "BookahPhotosX/y"):
        with pytest.raises(WriteRefused):
            d.put(bad, b"x")
        with pytest.raises(WriteRefused):
            d.delete(bad)
    with pytest.raises(WriteRefused):
        d.move("BookahPhotos/a", "Photos/a")
