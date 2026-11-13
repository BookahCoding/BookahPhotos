import sqlite3

from bookahphotos_indexer import schema
from bookahphotos_indexer.fake import make_fake_snapshot
from bookahphotos_indexer.snapshot import build_manifest, read_counts


def test_fake_snapshot_has_final_shape(tmp_path):
    p = make_fake_snapshot(tmp_path / "fake.db", n_assets=500)
    conn = sqlite3.connect(f"file:{p}?mode=ro", uri=True)
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert schema.EXPECTED_TABLES <= tables
    assert conn.execute("PRAGMA user_version").fetchone()[0] == schema.USER_VERSION
    assert conn.execute("SELECT COUNT(*) FROM assets").fetchone()[0] == 500
    # later-phase tables exist but are empty
    for t in ("asset_vectors", "faces", "people", "memories", "tags"):
        assert conn.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0] == 0
    assert conn.execute("SELECT value FROM meta WHERE key='schema_major'").fetchone()[0] == "1"
    conn.close()
    assert not list(tmp_path.glob("fake.db-*")), "no side files may be left next to a published snapshot"


def test_manifest_matches_file(tmp_path):
    p = make_fake_snapshot(tmp_path / "fake.db", n_assets=100)
    m = build_manifest(p, generation=3)
    assert m["generation"] == 3 and m["size"] == p.stat().st_size and len(m["sha256"]) == 64
    assert m["counts"]["assets"] == 100 == read_counts(p)["assets"]


def test_fake_is_deterministic_and_synthetic(tmp_path):
    a = make_fake_snapshot(tmp_path / "a.db", n_assets=200, seed=5)
    b = make_fake_snapshot(tmp_path / "b.db", n_assets=200, seed=5)
    ca = sqlite3.connect(a).execute("SELECT path, taken_utc FROM assets ORDER BY id").fetchall()
    cb = sqlite3.connect(b).execute("SELECT path, taken_utc FROM assets ORDER BY id").fetchall()
    assert ca == cb and all(path.startswith("Fake/") for path, _ in ca)
