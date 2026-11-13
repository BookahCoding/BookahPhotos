"""Phase 4b: grouping, ops and the face runner, with MADE-UP embeddings and generated pictures only (never real faces)."""
import json

import numpy as np
import pytest

from bookahphotos_indexer import faces, facesrun, grouping, ops
from bookahphotos_indexer.dav import DavError, Entry, ListingError
from bookahphotos_indexer.state import State
from test_content import FakeDav, jpeg

RNG = np.random.default_rng(42)


def unit(v):
    return (v / np.linalg.norm(v)).astype(np.float32)


def centres(n):
    return [unit(RNG.normal(size=128)) for _ in range(n)]


def noisy(c, noise=0.12):
    return unit(c + RNG.normal(size=128) * noise / np.sqrt(128) * 5)


def add_face(st: State, file_id: int, emb, box=(0.1, 0.1, 0.3, 0.4), usable=1):
    st.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb) VALUES (?,?,?,?,?,?,?,?,?)",
                  (file_id, *box, 0.9, 80.0, usable, emb.astype(np.float32).tobytes()))
    st.db.commit()


def world(tmp_path, n_people=5, per=30, name="s.db", file_order=1):
    """n_people identities, `per` faces each, one face per file. Returns (state, identity of each face_id, centres)."""
    st = State(tmp_path / name)
    cs = centres(n_people)
    ident = {}
    n = n_people * per
    k = 0
    for p, c in enumerate(cs):
        for j in range(per):
            fid = 1 + k if file_order == 1 else n - k
            add_face(st, fid, noisy(c), box=(0.1 + 0.01 * (j % 5), 0.1, 0.3 + 0.01 * (j % 5), 0.4))
            ident[(fid)] = p
            k += 1
    return st, ident, cs


def person_identities(st, ident):
    """person_id -> set of true identities in it"""
    out = {}
    for r in st.db.execute("SELECT file_id, person_id FROM faces WHERE person_id IS NOT NULL"):
        out.setdefault(r["person_id"], set()).add(ident[r["file_id"]])
    return out


# ---- grouping ----
def test_clustering_finds_the_people_without_mixing_them(tmp_path):
    st, ident, _ = world(tmp_path)
    r = grouping.group(st)
    assert r.new_persons == 5 and r.clustered_faces == 150
    assert all(len(v) == 1 for v in person_identities(st, ident).values())
    assert 0.15 < r.top_group_share < 0.3                      # nobody swallowed everybody


def test_second_run_changes_nothing(tmp_path):
    st, _, _ = world(tmp_path)
    grouping.group(st)
    before = st.db.execute("SELECT face_id, person_id FROM faces ORDER BY face_id").fetchall()
    r = grouping.group(st)
    assert (r.new_persons, r.assigned_to_existing) == (0, 0)
    assert st.db.execute("SELECT face_id, person_id FROM faces ORDER BY face_id").fetchall() == before


def test_new_faces_join_existing_people_and_strangers_stay_unassigned(tmp_path):
    st, ident, cs = world(tmp_path)
    grouping.group(st)
    ids_before = {r[0] for r in st.db.execute("SELECT person_id FROM persons")}
    for j in range(10):
        add_face(st, 1000 + j, noisy(cs[2]))
        ident[1000 + j] = 2
    for j in range(3):
        add_face(st, 2000 + j, unit(RNG.normal(size=128)))      # strangers, each alone
    r = grouping.group(st)
    assert r.assigned_to_existing == 10 and r.new_persons == 0 and r.left_unassigned == 3
    assert {r[0] for r in st.db.execute("SELECT person_id FROM persons")} == ids_before     # ids are stable
    assert all(len(v) == 1 for v in person_identities(st, ident).values())


def test_face_between_two_people_is_not_forced_into_one(tmp_path):
    st, _, cs = world(tmp_path, n_people=2)
    grouping.group(st)
    add_face(st, 5000, unit(cs[0] + cs[1]))
    grouping.group(st)
    assert st.db.execute("SELECT person_id FROM faces WHERE file_id=5000").fetchone()[0] is None


def test_unclear_faces_cannot_form_a_person_but_can_join_one(tmp_path):
    st = State(tmp_path / "s.db")
    c = centres(1)[0]
    for j in range(20):                                         # twenty faces that are usable but small and unsure
        st.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb) VALUES (?,?,?,?,?,?,?,?,?)",
                      (j + 1, 0.1, 0.1, 0.2, 0.2, 0.65, 45.0, 1, noisy(c).tobytes()))
    st.db.commit()
    assert grouping.group(st).new_persons == 0                  # nobody is made from unclear faces alone
    for j in range(10):
        add_face(st, 100 + j, noisy(c))                          # ten clear ones form the person
    r = grouping.group(st)
    assert r.new_persons == 1
    assert st.db.execute("SELECT COUNT(*) FROM faces WHERE person_id IS NOT NULL").fetchone()[0] >= 28      # the unclear ones joined afterwards


def test_faces_that_joined_later_do_not_move_the_centre_so_groups_cannot_creep(tmp_path):
    """Five clear faces form a person. Six unclear faces at the edge (distance 0.30) join. A seventh face is close to those edge faces but 0.46 from the
    person: it must stay out, in the first run AND in every later one (with a centre that follows the joiners it would be let in on the second run)."""
    st = State(tmp_path / "s.db")
    e0, e1 = np.eye(128, dtype=np.float32)[0], np.eye(128, dtype=np.float32)[1]
    at = lambda angle: (np.cos(angle) * e0 + np.sin(angle) * e1).astype(np.float32)
    for j in range(5):
        add_face(st, 1 + j, at(0.0))                                                    # clear faces: they form the person
    def unclear(file_id, emb):
        st.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb) VALUES (?,?,?,?,?,?,?,?,?)", (file_id, 0.1, 0.1, 0.2, 0.2, 0.7, 50.0, 1, emb.tobytes()))
    for j in range(6):
        unclear(100 + j, at(float(np.arccos(1 - 0.30))))                                # distance 0.30 from the person: joins
    unclear(200, at(1.0))                                                               # distance 0.46 from the person, 0.02 from the edge faces
    st.db.commit()
    r1 = grouping.group(st)
    assert r1.new_persons == 1 and r1.assigned_to_existing == 6
    assert st.db.execute("SELECT person_id FROM faces WHERE file_id=200").fetchone()[0] is None
    for _ in range(3):
        again = grouping.group(st)
        assert (again.new_persons, again.assigned_to_existing) == (0, 0)                # nothing changes, ever
    assert st.db.execute("SELECT person_id FROM faces WHERE file_id=200").fetchone()[0] is None


def test_a_join_that_was_too_generous_is_taken_back(tmp_path):
    """A face that joined a person earlier (for example under the old, drifting rule) but is not close enough to the fixed centre goes back to unassigned."""
    st, ident, cs = world(tmp_path, n_people=2, per=12)
    grouping.group(st)
    other = int(st.db.execute("SELECT person_id FROM faces WHERE file_id=1").fetchone()[0])
    far = unit(RNG.normal(size=128))
    st.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb, person_id, dist) VALUES (777, 0.1, 0.1, 0.2, 0.2, 0.7, 50, 1, ?, ?, 0.3)", (far.tobytes(), other))
    st.db.commit()
    grouping.group(st)
    assert st.db.execute("SELECT person_id FROM faces WHERE file_id=777").fetchone()[0] is None


def test_old_state_files_get_founders_from_their_close_clear_faces(tmp_path):
    import sqlite3
    path = tmp_path / "old.db"
    State(path).close()
    c = sqlite3.connect(path)
    c.execute("ALTER TABLE faces RENAME TO faces_new"); c.execute("DROP INDEX faces_file"); c.execute("DROP INDEX faces_person")
    c.execute("CREATE TABLE faces (face_id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL, x1 REAL NOT NULL, y1 REAL NOT NULL, x2 REAL NOT NULL, y2 REAL NOT NULL, "
              "score REAL NOT NULL, width_1280 REAL NOT NULL, usable INTEGER NOT NULL, emb BLOB NOT NULL, person_id INTEGER, assigned_by TEXT NOT NULL DEFAULT 'auto', "
              "rejected INTEGER NOT NULL DEFAULT 0, dist REAL)")
    c.execute("DROP TABLE faces_new")
    for fid, score, w, dist in ((1, 0.9, 90, 0.1), (2, 0.9, 90, 0.34), (3, 0.7, 90, 0.1)):
        c.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb, person_id, dist) VALUES (?,?,?,?,?,?,?,?,?,?,?)", (fid, 0, 0, 1, 1, score, w, 1, b"\0", 1, dist))
    c.commit(); c.close()
    st = State(path)
    assert [r[0] for r in st.db.execute("SELECT file_id FROM faces WHERE founder=1 ORDER BY file_id")] == [1, 2]     # the clear faces of people; the unclear one only ever joined


def test_reset_refuses_when_manual_choices_exist(tmp_path):
    st, _, _ = world(tmp_path, n_people=1, per=8)
    grouping.group(st)
    grouping.reset_automatic(st)
    assert st.db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == 0
    grouping.group(st)
    ops.apply_ops(st, [op("2026-11-13T10:00:00Z", "not_a_person", face=box_of(st, st.db.execute("SELECT face_id FROM faces").fetchone()[0]))])
    with pytest.raises(RuntimeError):
        grouping.reset_automatic(st)


def test_unusable_faces_are_never_grouped(tmp_path):
    st = State(tmp_path / "s.db")
    c = centres(1)[0]
    for j in range(10):
        add_face(st, j + 1, noisy(c), usable=0)
    assert grouping.group(st).new_persons == 0


# ---- ops ----
def op(t, typ, **kw):
    return ops.Op(kw.pop("op_id", f"id-{t}-{typ}"), t, typ, {"type": typ, "op_id": kw.get("op_id", ""), "t": t, **kw})


def box_of(st, face_id):
    r = st.db.execute("SELECT file_id, x1, y1, x2, y2 FROM faces WHERE face_id=?", (face_id,)).fetchone()
    return {"file_id": r["file_id"], "box": [r["x1"], r["y1"], r["x2"], r["y2"]]}


def anchors(st, pid, n=6):
    rows = st.db.execute("SELECT face_id FROM faces WHERE person_id=? ORDER BY face_id LIMIT ?", (pid, n)).fetchall()
    return [box_of(st, r[0]) for r in rows]


def test_iou_matching_tolerates_small_box_changes_but_not_other_faces(tmp_path):
    st = State(tmp_path / "s.db")
    add_face(st, 1, centres(1)[0], box=(0.10, 0.10, 0.30, 0.40))
    assert ops.find_face(st, {"file_id": 1, "box": [0.11, 0.11, 0.31, 0.41]}) is not None
    assert ops.find_face(st, {"file_id": 1, "box": [0.6, 0.6, 0.8, 0.9]}) is None
    assert ops.find_face(st, {"file_id": 2, "box": [0.10, 0.10, 0.30, 0.40]}) is None


def test_each_op_and_idempotence(tmp_path):
    st, ident, _ = world(tmp_path, n_people=3, per=12)
    grouping.group(st)
    p = [r[0] for r in st.db.execute("SELECT person_id FROM persons ORDER BY person_id")]
    a, b, c = p
    some = st.db.execute("SELECT face_id FROM faces WHERE person_id=? ORDER BY face_id", (a,)).fetchall()
    f1, f2 = some[0][0], some[1][0]
    all_ops = [
        op("2026-11-13T10:00:00Z", "name_person", person=a, anchors=anchors(st, a), name="Alex"),
        op("2026-11-13T10:01:00Z", "hide_person", person=c, anchors=anchors(st, c)),
        op("2026-11-13T10:02:00Z", "not_a_person", face=box_of(st, f1)),
        op("2026-11-13T10:03:00Z", "move_face", face=box_of(st, f2), to={"person": b, "anchors": anchors(st, b)}),
        op("2026-11-13T10:04:00Z", "set_cover", person=b, anchors=anchors(st, b), face=box_of(st, st.db.execute("SELECT face_id FROM faces WHERE person_id=?", (b,)).fetchone()[0])),
        op("2026-11-13T10:05:00Z", "hide_photo", file_id=7),
        op("2026-11-13T10:06:00Z", "unwanted_memory", key="trip:2024-08:abc"),
    ]
    assert ops.apply_ops(st, all_ops) == {"applied": 7}
    assert st.db.execute("SELECT name FROM persons WHERE person_id=?", (a,)).fetchone()[0] == "Alex"
    assert st.db.execute("SELECT hidden FROM persons WHERE person_id=?", (c,)).fetchone()[0] == 1
    r = st.db.execute("SELECT rejected, person_id, assigned_by FROM faces WHERE face_id=?", (f1,)).fetchone()
    assert tuple(r) == (1, None, "manual")
    assert tuple(st.db.execute("SELECT person_id, assigned_by FROM faces WHERE face_id=?", (f2,)).fetchone()) == (b, "manual")
    assert st.db.execute("SELECT cover_face_id FROM persons WHERE person_id=?", (b,)).fetchone()[0] is not None
    assert st.db.execute("SELECT 1 FROM hidden_files WHERE file_id=7").fetchone() and st.db.execute("SELECT 1 FROM unwanted_memories").fetchone()
    assert ops.apply_ops(st, all_ops) == {"already": 7}                  # applying the same ops again changes nothing


def test_merge_keeps_old_ids_resolvable_and_name(tmp_path):
    st, _, _ = world(tmp_path, n_people=2, per=12)
    grouping.group(st)
    a, b = [r[0] for r in st.db.execute("SELECT person_id FROM persons ORDER BY person_id")]
    anch_a, anch_b = anchors(st, a), anchors(st, b)
    ops.apply_ops(st, [op("2026-11-13T10:00:00Z", "name_person", person=b, anchors=anch_b, name="Bea"),
                       op("2026-11-13T10:01:00Z", "merge_people", **{"from": {"person": b, "anchors": anch_b}, "into": {"person": a, "anchors": anch_a}})])
    assert st.db.execute("SELECT COUNT(*) FROM faces WHERE person_id=?", (a,)).fetchone()[0] == 24
    assert st.db.execute("SELECT name FROM persons WHERE person_id=?", (a,)).fetchone()[0] == "Bea"     # the merged-away person's name is kept
    assert ops.resolve_person(st, {"person": b, "anchors": anch_b}) == a                              # an old id still leads to the right person


def test_skipped_ops_are_reported_and_not_marked_done(tmp_path):
    st, _, _ = world(tmp_path, n_people=1, per=6)
    grouping.group(st)
    bad = op("2026-11-13T10:00:00Z", "not_a_person", face={"file_id": 999, "box": [0, 0, 1, 1]})
    assert ops.apply_ops(st, [bad]) == {"skipped": 1}
    assert st.db.execute("SELECT COUNT(*) FROM ops_seen").fetchone()[0] == 0


def test_manual_faces_are_never_moved_by_automatic_grouping(tmp_path):
    st, _, _ = world(tmp_path, n_people=2, per=12)
    grouping.group(st)
    a, b = [r[0] for r in st.db.execute("SELECT person_id FROM persons ORDER BY person_id")]
    f = st.db.execute("SELECT face_id FROM faces WHERE person_id=?", (a,)).fetchone()[0]
    ops.apply_ops(st, [op("2026-11-13T10:00:00Z", "move_face", face=box_of(st, f), to={"person": b, "anchors": anchors(st, b)})])
    add_face(st, 9000, unit(RNG.normal(size=128)))
    grouping.group(st)
    assert st.db.execute("SELECT person_id FROM faces WHERE face_id=?", (f,)).fetchone()[0] == b


def test_rebuild_then_replay_gives_the_same_touched_people(tmp_path):
    """The 'done when' test: delete the private database, group again (people get other ids), replay the ops, compare."""
    st, ident, _ = world(tmp_path, n_people=5, per=20, name="live.db")
    grouping.group(st)
    pid_of = {r["file_id"]: r["person_id"] for r in st.db.execute("SELECT file_id, person_id FROM faces")}
    people = sorted(set(pid_of.values()))
    a, b, c, d = people[0], people[1], people[2], people[3]
    by_person = lambda p: [r[0] for r in st.db.execute("SELECT face_id FROM faces WHERE person_id=? ORDER BY face_id", (p,))]
    moved_face = box_of(st, by_person(a)[0])
    rejected_face = box_of(st, by_person(d)[0])
    log = [
        op("2026-11-13T10:00:00Z", "name_person", person=a, anchors=anchors(st, a, 8), name="Alex"),
        op("2026-11-13T10:01:00Z", "merge_people", **{"from": {"person": c, "anchors": anchors(st, c, 8)}, "into": {"person": b, "anchors": anchors(st, b, 8)}}),
        op("2026-11-13T10:02:00Z", "move_face", face=moved_face, to={"person": b, "anchors": anchors(st, b, 8)}),
        op("2026-11-13T10:03:00Z", "not_a_person", face=rejected_face),
    ]
    ops.apply_ops(st, log)
    live = {r["file_id"]: (r["person_id"], r["assigned_by"], r["rejected"]) for r in st.db.execute("SELECT file_id, person_id, assigned_by, rejected FROM faces")}
    live_name_id = st.db.execute("SELECT person_id FROM persons WHERE name='Alex'").fetchone()[0]
    st.close()

    # rebuild: same faces and embeddings, other file numbering (so the automatic groups get other ids), then replay
    st2 = State(tmp_path / "rebuilt.db")
    old = State(tmp_path / "live.db")
    for r in old.db.execute("SELECT * FROM faces ORDER BY face_id"):
        new_file = 10_000 - r["file_id"]
        st2.db.execute("INSERT INTO faces(file_id, x1, y1, x2, y2, score, width_1280, usable, emb) VALUES (?,?,?,?,?,?,?,?,?)",
                       (new_file, r["x1"], r["y1"], r["x2"], r["y2"], r["score"], r["width_1280"], r["usable"], r["emb"]))
    st2.db.commit()
    grouping.group(st2)
    # the app wrote file ids that still exist in the library: translate our test numbering back (the real file ids do not change on a rebuild)
    st2.db.execute("UPDATE faces SET file_id = 10000 - file_id"); st2.db.commit()
    assert ops.apply_ops(st2, log) == {"applied": 4}

    rebuilt = {r["file_id"]: (r["person_id"], r["assigned_by"], r["rejected"]) for r in st2.db.execute("SELECT file_id, person_id, assigned_by, rejected FROM faces")}
    # (1) the named person is the same group of faces, with the same id and name
    assert st2.db.execute("SELECT person_id FROM persons WHERE name='Alex'").fetchone()[0] == live_name_id
    members = lambda d, pid: {f for f, v in d.items() if v[0] == pid}
    assert members(rebuilt, live_name_id) == members(live, live_name_id)
    # (2) the merge: the faces of c and b are one person again
    assert members(rebuilt, b) == members(live, b)
    # (3) the manually moved face and the rejected face come back exactly
    assert rebuilt[moved_face["file_id"]] == live[moved_face["file_id"]]
    assert rebuilt[rejected_face["file_id"]][2] == 1 and rebuilt[rejected_face["file_id"]][0] is None


def test_review_results_become_ops_that_name_merge_and_move(tmp_path):
    from bookahphotos_indexer import reviewops
    st, ident, _ = world(tmp_path, n_people=3, per=12)
    grouping.group(st)
    a, b, c = [r[0] for r in st.db.execute("SELECT person_id FROM persons ORDER BY person_id")]
    wrong_face = st.db.execute("SELECT face_id FROM faces WHERE person_id=? ORDER BY face_id", (c,)).fetchone()[0]
    results = {f"name|{a}": "Una", f"name|{b}": "Una", f"name|{c}": "Unknown1", f"{c}|{wrong_face}": True, f"{c}|99999": True}
    made = reviewops.results_to_ops(st, results)
    assert sorted(o["type"] for o in made) == ["merge_people", "move_face", "name_person", "name_person"]       # Unknown* ignored, bogus face id ignored
    path = tmp_path / "ops.jsonl"
    reviewops.write_ops_file(made, path)
    parsed, bad = ops.read_local_file(path)
    assert bad == 0 and ops.apply_ops(st, parsed) == {"applied": 4}
    assert st.db.execute("SELECT COUNT(*) FROM persons WHERE merged_into IS NULL AND name='Una'").fetchone()[0] == 1       # a and b are one person now
    assert st.db.execute("SELECT person_id, assigned_by FROM faces WHERE face_id=?", (wrong_face,)).fetchone()[0] is None


# ---- op files in Nextcloud ----
class OpsDav:
    """An in-memory Nextcloud folder tree for the op files."""
    def __init__(self, files: dict[str, str]):
        self.files = dict(files)
        self.dirs = set()
        for p in self.files:
            parts = p.split("/")
            for i in range(1, len(parts)):
                self.dirs.add("/".join(parts[:i]))

    def list_folder(self, path):
        path = path.strip("/")
        if path not in self.dirs:
            raise ListingError("missing")
        kids = []
        for d in sorted(self.dirs):
            if d.rsplit("/", 1)[0] == path and "/" in d:
                kids.append(Entry(d, True, "e", 0, 0, "", ""))
        for f in sorted(self.files):
            if f.rsplit("/", 1)[0] == path:
                kids.append(Entry(f, False, "e", 0, len(self.files[f]), "", ""))
        return Entry(path, True, "e", 0, 0, "", ""), kids

    def get(self, path, byte_range=None):
        return self.files[path.strip("/")].encode()

    def mkcol(self, path):
        path = path.strip("/")
        if path in self.dirs:
            raise DavError("MKCOL HTTP 405")
        self.dirs.add(path)

    def move(self, src, dst):
        self.files[dst] = self.files.pop(src)


def line(op_id, t, typ, **kw):
    return json.dumps({"op_id": op_id, "t": t, "type": typ, **kw})


def test_sync_applies_new_op_files_and_moves_them_to_applied(tmp_path):
    st, _, _ = world(tmp_path, n_people=1, per=8)
    grouping.group(st)
    p = st.db.execute("SELECT person_id FROM persons").fetchone()[0]
    text = line("o1", "2026-11-13T09:00:00Z", "name_person", person=p, anchors=anchors(st, p), name="Mum") + "\nnot json\n"
    dav = OpsDav({"BookahPhotos/ops/phone-1/a.jsonl": text})
    r = ops.sync(dav, st)
    assert r["counts"] == {"applied": 1} and r["files_moved"] == 1 and r["bad_lines"] == 1
    assert "BookahPhotos/ops/applied/phone-1/a.jsonl" in dav.files and "BookahPhotos/ops/phone-1/a.jsonl" not in dav.files
    assert ops.sync(dav, st)["counts"] == {}                              # nothing new: nothing happens


def test_sync_keeps_a_file_with_a_skipped_op_and_never_creates_ops_folder(tmp_path):
    st, _, _ = world(tmp_path, n_people=1, per=8)
    grouping.group(st)
    text = line("o2", "2026-11-13T09:00:00Z", "not_a_person", face={"file_id": 999999, "box": [0, 0, 1, 1]})
    dav = OpsDav({"BookahPhotos/ops/phone-1/b.jsonl": text})
    r = ops.sync(dav, st)
    assert r["counts"] == {"skipped": 1} and r["files_moved"] == 0 and "BookahPhotos/ops/phone-1/b.jsonl" in dav.files
    empty = OpsDav({"BookahPhotos/index/x": ""})
    assert ops.sync(empty, st) == {"counts": {}, "files_moved": 0, "bad_lines": 0, "unreadable": 0}
    assert "BookahPhotos/ops" not in empty.dirs


def test_replay_reads_archived_ops_too(tmp_path):
    st, _, _ = world(tmp_path, n_people=1, per=8)
    grouping.group(st)
    p = st.db.execute("SELECT person_id FROM persons").fetchone()[0]
    text = line("o3", "2026-11-13T09:00:00Z", "name_person", person=p, anchors=anchors(st, p), name="Mum")
    dav = OpsDav({"BookahPhotos/ops/applied/phone-1/c.jsonl": text})
    assert ops.sync(dav, st)["counts"] == {}                              # normal run: archived files are history
    assert ops.sync(dav, st, replay=True)["counts"] == {"applied": 1}      # after a rebuild they are replayed
    assert st.db.execute("SELECT name FROM persons WHERE person_id=?", (p,)).fetchone()[0] == "Mum"


# ---- the face runner ----
class FakeEngine:
    def detect_and_embed(self, rgb):
        h, w = rgb.shape[:2]
        if w % 2:                                    # pictures of odd width have no face in this fake
            return []
        e = unit(np.ones(128))
        return [faces.Face(0.1, 0.1, 0.4, 0.5, 0.9, 90.0, e), faces.Face(0.6, 0.1, 0.7, 0.2, 0.9, 10.0, e)]     # second one is too small


def face_state(tmp_path):
    from bookahphotos_indexer import content
    st = State(tmp_path / "f.db")
    rows = [(1, "image/jpeg", 0, "preview1024", 640), (2, "image/jpeg", 0, "preview1024", 641), (3, "image/jpeg", 2, "preview1024", 640),
            (4, "image/tiff", 0, "preview256", 640)]
    for fid, mime, flags, source, _ in rows:
        st.upsert_listed(fid, f"P/{fid}.jpg", f"e{fid}", 10, mime, 1)
        st.set_fields(fid, {"flags": flags}, level=1)
        st.save_content(fid, f"e{fid}", "ok", source, content.MODEL_NAME, b"\0" * 1024, 1.0)
    return st, {fid: jpeg(size=(w, 480)) for fid, _, _, _, w in rows}


def test_face_runner_skips_screenshots_and_scans_records_empty_photos_and_is_resumable(tmp_path):
    st, pics = face_state(tmp_path)
    dav = FakeDav(pics)
    r = facesrun.run_faces(dav, st, tmp_path / "cache", workers=2, engine_factory=FakeEngine)
    assert (r.photos, r.faces, r.usable) == (2, 2, 1)                     # files 1 and 2 only: 3 is a screenshot, 4 a scan
    assert st.db.execute("SELECT n_faces FROM faces_done WHERE file_id=2").fetchone()[0] == 0
    assert st.db.execute("SELECT COUNT(*) FROM faces").fetchone()[0] == 2
    calls = len(dav.calls)
    assert facesrun.run_faces(dav, st, tmp_path / "cache", workers=2, engine_factory=FakeEngine).photos == 0 and len(dav.calls) == calls


def test_face_runner_uses_the_cache_and_rereads_a_changed_file(tmp_path):
    st, pics = face_state(tmp_path)
    dav = FakeDav(pics)
    facesrun.run_faces(dav, st, tmp_path / "cache", workers=1, engine_factory=FakeEngine)
    st.db.execute("DELETE FROM faces_done"); st.db.execute("DELETE FROM faces"); st.db.commit()      # a rebuild of the face tables
    before = len(dav.calls)
    r = facesrun.run_faces(dav, st, tmp_path / "cache", workers=1, engine_factory=FakeEngine)
    assert r.photos == 2 and len(dav.calls) == before                       # all from the cache
    st.upsert_listed(1, "P/1.jpg", "e1-new", 10, "image/jpeg", 2)
    st.set_fields(1, {}, level=1)
    from bookahphotos_indexer import content
    st.save_content(1, "e1-new", "ok", "preview1024", content.MODEL_NAME, b"\0" * 1024, 1.0)
    assert [x["file_id"] for x in st.pending_faces(faces.FACE_MODEL)] == [1]
