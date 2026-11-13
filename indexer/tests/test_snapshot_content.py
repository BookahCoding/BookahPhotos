"""Phase 4d: snapshot 1.1 built from generated data, verified, and checked for privacy, hidden photos and the unchanged-run fingerprint."""
import shutil
import sqlite3
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pytest

from bookahphotos_indexer import build, content, faces, labels, memories, schema, verify
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.state import State

RNG = np.random.default_rng(5)


class FakeText:
    def encode(self, texts):
        out = np.zeros((len(texts), content.DIM), np.float32)
        for k in range(len(texts)):
            out[k, k % len(labels.LABELS)] = 1.0
        return out

    def close(self):
        pass


def unit(v):
    return (v / np.linalg.norm(v)).astype(np.float32)


def make(tmp_path, n=40):
    st = State(tmp_path / "s.db")
    day0 = date(2024, 5, 1)
    for i in range(1, n + 1):
        d = day0 + timedelta(days=i % 20)
        t = int(datetime(d.year, d.month, d.day, 12, tzinfo=timezone.utc).timestamp())
        st.upsert_listed(i, f"Photos/p{i}.jpg", f"e{i}", 100, "image/jpeg", 1)
        st.set_fields(i, {"taken_utc": t, "taken_offset_min": 0, "day_key": d.isoformat(), "width": 4000, "height": 3000}, level=1)
        v = unit(RNG.normal(size=content.DIM)) if i % 4 else np.eye(content.DIM, dtype=np.float32)[0]       # every 4th photo points at label 0 (beach)
        st.save_content(i, f"e{i}", "ok", "preview1024", content.MODEL_NAME, content.to_f16(v), 50.0)
        st.save_faces(i, f"e{i}", faces.FACE_MODEL, [(0.1, 0.1, 0.3, 0.4, 0.95, 90.0, 1, unit(RNG.normal(size=128)).tobytes())] if i <= 12 else [])
    # one person with 8 faces (photos 1-8), one with 4 (photos 9-12: too few to show)
    st.db.execute("INSERT INTO persons(person_id, name, created) VALUES (1, 'Alex', 0)")
    st.db.execute("INSERT INTO persons(person_id, name, created) VALUES (2, NULL, 0)")
    st.db.execute("UPDATE faces SET person_id=1 WHERE file_id<=8")
    st.db.execute("UPDATE faces SET person_id=2 WHERE file_id BETWEEN 9 AND 12")
    st.db.commit()
    return st


def cfg():
    return Config()


def snap(st, tmp_path, name="a.db", generation=1):
    return build.build_snapshot(st, cfg(), None, tmp_path / name, generation, {"search_model": "x"}, label_cache=tmp_path / "lv.npz", text_encoder_factory=FakeText)


def test_snapshot_is_complete_and_passes_verification(tmp_path):
    st = make(tmp_path)
    p = snap(st, tmp_path)
    problems, facts = verify.verify(p)
    assert problems == []
    assert (facts["assets"], facts["asset_vectors"], facts["faces"], facts["people"]) == (40, 40, 12, 1)
    c = sqlite3.connect(p)
    assert c.execute("PRAGMA user_version").fetchone()[0] == 1003
    assert c.execute("SELECT name, face_count, hidden FROM people").fetchall() == [("Alex", 8, 0)]
    assert c.execute("SELECT COUNT(*) FROM faces WHERE person_id IS NULL").fetchone()[0] == 4                 # the person with 4 faces is not shown: 'Who is this?'
    assert c.execute("SELECT cover_face_id FROM people").fetchone()[0] in {r[0] for r in c.execute("SELECT id FROM faces WHERE person_id=1")}
    assert c.execute("SELECT COUNT(*) FROM assets WHERE indexed_level=3").fetchone()[0] == 40                  # vector and faces read for every file
    assert c.execute("SELECT COUNT(*) FROM labels").fetchone()[0] == len(labels.LABELS)
    tagged = {r[0] for r in c.execute("SELECT asset_id FROM tags WHERE label_key='beach'")}
    assert tagged == {i for i in range(1, 41) if i % 4 == 0}                                                    # exactly the photos that point at 'beach'


def test_app_queries_still_work_on_the_new_snapshot(tmp_path):
    """The app (0.8.x) reads these columns of assets and ignores everything else."""
    p = snap(make(tmp_path), tmp_path)
    c = sqlite3.connect(p)
    assert len(c.execute("SELECT id, day_key, taken_utc, flags, thumbhash, etag, mime_type, duration_ms, path, size FROM assets").fetchall()) == 40
    assert c.execute("SELECT value FROM meta WHERE key='generation'").fetchone()[0] == "1"


def test_no_face_description_can_be_in_the_snapshot(tmp_path):
    p = snap(make(tmp_path), tmp_path)
    c = sqlite3.connect(p)
    assert {r[1] for r in c.execute("PRAGMA table_info(faces)")} == {"id", "asset_id", "x", "y", "w", "h", "person_id", "quality"}
    st_blob = RNG.normal(size=128).astype(np.float32).tobytes()
    raw = open(p, "rb").read()
    # none of the stored 128-number descriptions appears anywhere in the file
    st = State(tmp_path / "s.db")
    for (emb,) in st.db.execute("SELECT emb FROM faces"):
        assert emb[:64] not in raw
    assert st_blob not in raw


def test_hidden_photos_are_left_out_everywhere(tmp_path):
    st = make(tmp_path)
    st.db.execute("INSERT INTO hidden_files(file_id) VALUES (3)"); st.db.commit()
    p = snap(st, tmp_path)
    problems, facts = verify.verify(p)
    c = sqlite3.connect(p)
    assert problems == [] and facts["assets"] == 39
    assert c.execute("SELECT COUNT(*) FROM asset_vectors WHERE asset_id=3").fetchone()[0] == 0 and c.execute("SELECT COUNT(*) FROM faces WHERE asset_id=3").fetchone()[0] == 0


def test_a_person_hidden_by_an_op_is_marked_not_dropped(tmp_path):
    st = make(tmp_path)
    st.db.execute("UPDATE persons SET hidden=1 WHERE person_id=1"); st.db.commit()
    c = sqlite3.connect(snap(st, tmp_path))
    assert c.execute("SELECT hidden FROM people").fetchone()[0] == 1


def test_fingerprint_is_stable_and_changes_when_anything_published_changes(tmp_path):
    st = make(tmp_path)
    d0 = build.rows_digest(st)
    assert build.rows_digest(st) == d0                                                        # a second run changes nothing
    st.db.execute("UPDATE persons SET name='Alexandra' WHERE person_id=1"); st.db.commit()
    d1 = build.rows_digest(st)
    assert d1 != d0
    st.save_content(5, "e5", "ok", "preview1024", content.MODEL_NAME, content.to_f16(unit(RNG.normal(size=content.DIM))), 50.0)
    d2 = build.rows_digest(st)
    assert d2 != d1
    st.db.execute("INSERT INTO unwanted_memories(memory_key) VALUES ('month:2024-05')"); st.db.commit()
    assert build.rows_digest(st) != d2


def test_two_builds_from_the_same_state_have_the_same_content(tmp_path):
    st = make(tmp_path)
    a, b = snap(st, tmp_path, "a.db", 1), snap(st, tmp_path, "b.db", 2)
    ca, cb = sqlite3.connect(a), sqlite3.connect(b)
    for table in ("assets", "asset_vectors", "tags", "people", "faces", "memories", "memory_assets"):
        assert ca.execute(f"SELECT * FROM {table} ORDER BY 1, 2").fetchall() == cb.execute(f"SELECT * FROM {table} ORDER BY 1, 2").fetchall(), table


@pytest.mark.parametrize("sql,expect", [
    ("INSERT INTO faces(id, asset_id, x, y, w, h, quality) VALUES (999, 99999, 0.1, 0.1, 0.2, 0.2, 0.9)", "faces for unknown assets"),
    ("UPDATE faces SET x=0.9, w=0.5 WHERE id=1", "face boxes outside the picture"),
    ("INSERT INTO tags(asset_id, label_key, score) VALUES (1, 'nonsense', 0.3)", "tags with unknown label"),
    ("UPDATE asset_vectors SET dim=100 WHERE asset_id=1", "vectors with the wrong size"),
    ("UPDATE people SET face_count=99", "people whose face_count is wrong"),
    ("UPDATE memories SET key=NULL", "memories without a key"),
])
def test_verification_catches_a_broken_snapshot(tmp_path, sql, expect):
    st = make(tmp_path, 70)                 # 70 photos in one month: that month becomes a memory, so the memory checks have something to break
    p = snap(st, tmp_path)
    assert sqlite3.connect(p).execute("SELECT COUNT(*) FROM memories").fetchone()[0] >= 1
    copy = tmp_path / "broken.db"
    shutil.copy(p, copy)
    c = sqlite3.connect(copy)
    c.execute(sql); c.commit(); c.close()
    problems, _ = verify.verify(copy)
    assert any(expect in x for x in problems), problems


def test_an_old_minor_version_file_is_not_accepted_as_1_1(tmp_path):
    p = snap(make(tmp_path), tmp_path)
    copy = tmp_path / "old.db"
    shutil.copy(p, copy)
    c = sqlite3.connect(copy)
    c.execute("PRAGMA user_version = 1000"); c.commit(); c.close()
    assert any("user_version" in x for x in verify.verify(copy)[0])


def test_applied_ops_are_listed_in_the_snapshot_and_change_the_fingerprint(tmp_path):
    """Schema 1.2: the app learns from `applied_ops` that its corrections arrived (it then drops its pending copies)."""
    st = make(tmp_path)
    before = build.rows_digest(st)
    p0 = snap(st, tmp_path, "a.db")
    assert sqlite3.connect(p0).execute("SELECT COUNT(*) FROM applied_ops").fetchone()[0] == 0
    st.db.execute("INSERT INTO ops_seen(op_id, applied, result) VALUES ('op-1', 0, 'applied'), ('op-2', 0, 'applied')")
    st.db.commit()
    assert build.rows_digest(st) != before                       # a correction that changes nothing else still produces a new generation
    p1 = snap(st, tmp_path, "b.db", 2)
    c = sqlite3.connect(p1)
    assert [r[0] for r in c.execute("SELECT op_id FROM applied_ops ORDER BY op_id")] == ["op-1", "op-2"]
    assert c.execute("PRAGMA user_version").fetchone()[0] == 1003
    assert verify.verify(p1)[0] == []


def test_hidden_photos_are_listed_only_in_hidden_assets(tmp_path):
    """Schema 1.3: a photo hidden with an op is in hidden_assets (so the app can un-hide it) and in no other table."""
    st = make(tmp_path)
    st.db.execute("INSERT INTO hidden_files(file_id) VALUES (5)")
    st.db.commit()
    p = snap(st, tmp_path)
    assert verify.verify(p)[0] == []
    c = sqlite3.connect(p)
    assert c.execute("SELECT COUNT(*) FROM assets").fetchone()[0] == 39
    row = c.execute("SELECT id, path, etag, size, mime_type FROM hidden_assets").fetchall()
    assert row == [(5, "Photos/p5.jpg", "e5", 100, "image/jpeg")]
    for table, col in (("asset_vectors", "asset_id"), ("tags", "asset_id"), ("faces", "asset_id"), ("memory_assets", "asset_id")):
        assert c.execute(f"SELECT COUNT(*) FROM {table} WHERE {col}=5").fetchone()[0] == 0
    before = build.rows_digest(st)
    st.db.execute("DELETE FROM hidden_files"); st.db.commit()
    assert build.rows_digest(st) != before                                    # showing it again makes a new generation
    p2 = snap(st, tmp_path, "b.db", 2)
    assert sqlite3.connect(p2).execute("SELECT COUNT(*) FROM hidden_assets").fetchone()[0] == 0 and sqlite3.connect(p2).execute("SELECT COUNT(*) FROM assets").fetchone()[0] == 40


def _row(fid, path, mime="image/jpeg", size=1000, taken=1_700_000_000, w=4000, h=3000, lens=None, dur=None):
    return {"file_id": fid, "path": path, "mime": mime, "size": size, "taken_utc": taken, "width": w, "height": h, "lens": lens, "duration_ms": dur}


def test_selfies_motion_photos_and_duplicates_are_worked_out_from_the_rows():
    rows = [
        _row(1, "Photos/a/IMG_1.HEIC", lens="iPhone 13 mini front camera 2.7mm f/2.2", size=111, taken=10),
        _row(2, "Photos/a/IMG_2.HEIC", lens="iPhone 13 mini back dual wide camera", size=222, taken=20),
        _row(3, "Photos/a/IMG_3.HEIC", size=333, taken=30), _row(4, "Photos/a/IMG_3.MOV", mime="video/quicktime", dur=2300, size=9999, taken=30),        # a Live Photo
        _row(5, "Photos/a/IMG_5.HEIC", size=555, taken=50), _row(6, "Photos/a/IMG_5.MOV", mime="video/quicktime", dur=45_000, size=8888, taken=50),    # a photo and a LONG video: not motion
        _row(7, "Photos/b/MVIMG_20260610_204612.jpg", size=777, taken=70),
        _row(8, "Photos/x/same.jpg", size=4242, taken=80), _row(9, "Photos/y/copy.jpg", size=4242, taken=80), _row(10, "Photos/z/other.jpg", size=4243, taken=80),
        _row(13, "Photos/scan/775.tiff", mime="image/tiff", size=39_862_276, taken=90), _row(14, "Photos/scan/776.tiff", mime="image/tiff", size=39_862_276, taken=90),   # two scans of different frames: same size by construction
        _row(11, "Photos/x/nodate.jpg", size=5000, taken=None), _row(12, "Photos/y/nodate2.jpg", size=5000, taken=None),                                # no capture time: never grouped
    ]
    d = build.derived(rows)
    assert d[1][0] & schema.FLAG_SELFIE and not d[2][0] & schema.FLAG_SELFIE
    assert d[3][0] & schema.FLAG_MOTION_PHOTO and not d[5][0] & schema.FLAG_MOTION_PHOTO and d[7][0] & schema.FLAG_MOTION_PHOTO
    assert not d[4][0] & schema.FLAG_MOTION_PHOTO                                                      # the video itself is not marked
    assert d[13][1] is None and d[14][1] is None                                                      # uncompressed scans are never grouped by size
    assert d[8][1] == d[9][1] == 8 and d[10][1] is None and d[11][1] is None and d[12][1] is None   # the group number is the smallest file id


def test_the_snapshot_carries_the_derived_flags_and_duplicate_groups(tmp_path):
    st = make(tmp_path)
    st.db.execute("UPDATE files SET size = 1000 + file_id")                                         # in this fixture every photo has the same size: make them different
    st.db.execute("UPDATE files SET lens='iPhone front camera' WHERE file_id=3")
    st.db.execute("UPDATE files SET size=777, taken_utc=1700000000, width=4000, height=3000 WHERE file_id IN (6, 7)")
    st.db.commit()
    before = build.rows_digest(st)
    p = snap(st, tmp_path)
    c = sqlite3.connect(p)
    assert c.execute("SELECT flags & 4 FROM assets WHERE id=3").fetchone()[0] == 4
    assert c.execute("SELECT duplicate_group FROM assets WHERE id IN (6, 7)").fetchall() == [(6,), (6,)]
    assert c.execute("SELECT COUNT(*) FROM assets WHERE duplicate_group IS NOT NULL").fetchone()[0] == 2
    st.db.execute("UPDATE files SET lens=NULL WHERE file_id=3"); st.db.commit()
    assert build.rows_digest(st) != before                                                             # the derived values are part of the fingerprint
