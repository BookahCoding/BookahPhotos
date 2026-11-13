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
    assert bad == 0 and [o.type for o in parsed] == ["name_person", "hide_person", "unhide_person", "set_cover", "move_face", "not_a_person", "merge_people", "hide_photo", "unwanted_memory", "unwanted_memory", "wanted_memory", "hide_photo", "hide_photo", "unhide_photo", "new_person"]
    result = ops.apply_ops(st, parsed)
    assert sum(result.values()) == 15 and set(result) == {"applied"}
    q = lambda sql, *a: [tuple(r) for r in st.db.execute(sql, a).fetchall()]
    assert q("SELECT name FROM persons WHERE person_id=1") == [('Ελένη "Έλα"',)]
    assert q("SELECT hidden, merged_into FROM persons WHERE person_id=2") == [(0, 1)]            # hidden, un-hidden, merged
    newp = q("SELECT person_id FROM persons WHERE name=?", 'Νέα "Έλα"')
    assert len(newp) == 1 and q("SELECT person_id, rejected, assigned_by FROM faces WHERE file_id=12") == [(newp[0][0], 0, "manual")]      # moved to nobody, then named as a new person
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


def test_anchors_still_find_a_person_when_some_anchor_faces_belong_to_nobody_but_two_are_not_enough(tmp_path):
    """After a rebuild some anchor faces are in no group: three or more agreeing anchors are enough; two (which could be drifted faces) are not."""
    f = lambda n: {"file_id": n, "box": [0.1, 0.1, 0.3, 0.4]}
    anchors = [f(n) for n in range(1, 9)]                                                      # person 1 owns photos 1 to 8
    (tmp_path / "a").mkdir(); (tmp_path / "b").mkdir(); (tmp_path / "c").mkdir()
    st = make(tmp_path / "a")
    st.db.execute("UPDATE faces SET person_id=NULL WHERE file_id IN (1, 2, 3, 4, 5)"); st.db.commit()      # five of the eight anchors belong to nobody now: 3 of 8 agree
    assert ops.resolve_person(st, {"person": 999, "anchors": anchors}) == 999                  # found person 1, who takes the id back
    assert st.db.execute("SELECT 1 FROM persons WHERE person_id=999").fetchone()
    st2 = make(tmp_path / "b")
    st2.db.execute("UPDATE faces SET person_id=NULL WHERE file_id IN (1, 2, 3, 4, 5, 6)"); st2.db.commit()  # only 2 of 8 agree: not enough, nothing is renamed or renumbered
    assert ops.resolve_person(st2, {"person": 998, "anchors": anchors}) is None
    assert not st2.db.execute("SELECT 1 FROM persons WHERE person_id=998").fetchone()
    st3 = make(tmp_path / "c")
    st3.db.execute("UPDATE faces SET person_id=NULL WHERE file_id IN (1, 2, 3, 4, 5, 6)")
    st3.db.execute("UPDATE faces SET person_id=2 WHERE file_id IN (7, 8)"); st3.db.commit()                # the two faces that are left moved to person 2: the op must not land on the wrong person
    assert ops.resolve_person(st3, {"person": 997, "anchors": anchors}) is None


def test_new_person_from_faces_that_cannot_be_found_is_skipped_and_retried(tmp_path):
    st = make(tmp_path)
    op = ops.Op("np1", "2026-11-13T10:00:00.000Z", "new_person", {"name": "Nobody", "faces": [{"file_id": 999, "box": [0.1, 0.1, 0.3, 0.4]}]})
    assert ops.apply_op(st, op).startswith("skipped")                                     # not applied, not marked as seen: it is tried again next run
    assert st.db.execute("SELECT COUNT(*) FROM persons WHERE name='Nobody'").fetchone()[0] == 0
    assert st.db.execute("SELECT COUNT(*) FROM ops_seen WHERE op_id='np1'").fetchone()[0] == 0
