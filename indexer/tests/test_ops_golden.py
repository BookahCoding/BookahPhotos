"""The file the app writes (tests/fixtures/ops-golden.jsonl, byte for byte the same as the Kotlin writer's output in its tests) is read and applied by the indexer."""
from pathlib import Path

from bookahphotos_indexer import ops
from bookahphotos_indexer.state import State

GOLDEN = Path(__file__).parent / "fixtures" / "ops-golden.jsonl"


def make(tmp_path):
    import numpy as np
    from bookahphotos_indexer import faces
    st = State(tmp_path / "s.db")
    for i in range(1, 31):
        st.upsert_listed(i, f"Photos/p{i}.jpg", f"e{i}", 100, "image/jpeg", 1)
        st.save_faces(i, f"e{i}", faces.FACE_MODEL, [(0.1, 0.1, 0.3, 0.4, 0.95, 90.0, 1, np.zeros(128, np.float32).tobytes())] if i <= 12 else [])
    st.db.execute("INSERT INTO persons(person_id, name, created) VALUES (1, 'Alex', 0), (2, NULL, 0)")
    st.db.execute("UPDATE faces SET person_id=1 WHERE file_id<=8")
    st.db.execute("UPDATE faces SET person_id=2 WHERE file_id BETWEEN 9 AND 12")
    st.db.commit()
    return st


def test_golden_file_parses_and_applies_in_order(tmp_path):
    st = make(tmp_path)
    parsed, bad = ops.parse_ops(GOLDEN.read_text(encoding="utf-8"), "golden")
    assert bad == 0 and [o.type for o in parsed] == ["name_person", "hide_person", "unhide_person", "set_cover", "move_face", "not_a_person", "merge_people", "hide_photo", "unwanted_memory", "unwanted_memory", "wanted_memory", "hide_photo", "hide_photo", "unhide_photo"]
    result = ops.apply_ops(st, parsed)
    assert sum(result.values()) == 14 and set(result) == {"applied"}
    q = lambda sql, *a: [tuple(r) for r in st.db.execute(sql, a).fetchall()]
    assert q("SELECT name FROM persons WHERE person_id=1") == [('Ελένη "Έλα"',)]
    assert q("SELECT hidden, merged_into FROM persons WHERE person_id=2") == [(0, 1)]            # hidden, un-hidden, merged
    assert q("SELECT person_id, rejected FROM faces WHERE file_id=12") == [(None, 0)]           # moved to nobody
    assert q("SELECT rejected FROM faces WHERE file_id=11") == [(1,)]                           # not a person
    assert q("SELECT COUNT(*) FROM faces WHERE person_id=1") == [(10,)]                         # 8 + faces of photos 9 and 10
    assert q("SELECT cover_face_id FROM persons WHERE person_id=1") == q("SELECT face_id FROM faces WHERE file_id=3")
    assert q("SELECT file_id FROM hidden_files ORDER BY file_id") == [(20,), (22,)]                 # 21 was hidden and shown again
    assert q("SELECT memory_key FROM unwanted_memories") == [("trip:2024-08-02:12345",)]          # the second one is unwanted, the first was given back
    again = ops.apply_ops(st, parsed)
    assert set(again) == {"already"}                                                            # applying twice changes nothing


def test_the_android_test_copy_of_the_golden_file_is_identical():
    android = Path(__file__).resolve().parents[2] / "android" / "core-data" / "src" / "test" / "resources" / "ops-golden.jsonl"
    assert android.read_bytes() == GOLDEN.read_bytes()      # the Kotlin writer test and the indexer test use the same file


def test_not_a_person_is_undone_by_moving_the_face_back(tmp_path):
    st = make(tmp_path)
    f = lambda n: {"file_id": n, "box": [0.1, 0.1, 0.3, 0.4]}
    body = lambda i, t, typ, extra: {"op_id": f"u{i}", "t": t, "type": typ, **extra}
    person1 = {"person": 1, "anchors": [f(1), f(2), f(3)]}
    ops.apply_ops(st, [ops.Op("u1", "2026-11-13T10:00:00.000Z", "not_a_person", body(1, "2026-11-13T10:00:00.000Z", "not_a_person", {"face": f(5)}))])
    assert [tuple(r) for r in st.db.execute("SELECT person_id, rejected FROM faces WHERE file_id=5")] == [(None, 1)]
    ops.apply_ops(st, [ops.Op("u2", "2026-11-13T10:00:01.000Z", "move_face", body(2, "2026-11-13T10:00:01.000Z", "move_face", {"face": f(5), "to": person1}))])
    assert [tuple(r) for r in st.db.execute("SELECT person_id, rejected FROM faces WHERE file_id=5")] == [(1, 0)]


def test_anchors_still_find_a_person_when_some_anchor_faces_belong_to_nobody(tmp_path):
    """After a rebuild some anchor faces are in no group; two or more agreeing anchors are enough, one alone is not."""
    st = make(tmp_path)
    f = lambda n: {"file_id": n, "box": [0.1, 0.1, 0.3, 0.4]}
    st.db.execute("UPDATE faces SET person_id=NULL WHERE file_id IN (1, 2, 3, 4)")        # four anchor faces now belong to nobody
    st.db.commit()
    two = {"person": 999, "anchors": [f(1), f(2), f(3), f(4), f(5), f(6)]}               # 5 and 6 are person 1's: two votes of six anchors
    assert ops.resolve_person(st, two) == 999 and st.db.execute("SELECT 1 FROM persons WHERE person_id=999").fetchone()    # found person 1, who takes the id back
    st2 = make(tmp_path / "b") if (tmp_path / "b").mkdir() is None else None
    st2.db.execute("UPDATE faces SET person_id=NULL WHERE file_id IN (1, 2, 3, 4, 5)"); st2.db.commit()
    one = {"person": 998, "anchors": [f(1), f(2), f(3), f(4), f(5), f(6)]}               # only face 6 is somebody's: one vote is not enough
    assert ops.resolve_person(st2, one) is None
