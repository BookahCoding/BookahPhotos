import pytest

from bookahphotos_indexer.config import Config
from bookahphotos_indexer.dav import ListingError
from bookahphotos_indexer.scan import SafeguardTripped, scan
from bookahphotos_indexer.state import State
from fakes import FakeDav


@pytest.fixture
def env(tmp_path):
    cfg = Config(roots=["Photos"], safeguard_min_known=10)
    dav = FakeDav()
    for i in range(30):
        dav.add(f"Photos/2024/p{i}.jpg")
    dav.add("Photos/2025/Γιορτή/φωτο.heic", mime="image/heic")
    dav.add("Photos/notes.txt", mime="text/plain")
    st = State(tmp_path / "state.db")
    return cfg, dav, st


def run(cfg, dav, st, **kw):
    rid = st.start_run()
    res = scan(dav, st, cfg, rid, **kw)
    st.finish_run(rid, "ok")
    return res


def test_first_run_lists_media_only(env):
    cfg, dav, st = env
    r = run(cfg, dav, st)
    assert r.new == 31 and r.deleted == 0                      # the .txt file is not media
    assert st.known_under(["Photos"]) == 31


def test_second_run_without_changes_lists_only_the_root(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    dav.list_calls.clear()
    r = run(cfg, dav, st)
    assert dav.list_calls == ["Photos"] and r.nothing_changed and r.new == r.changed == r.deleted == 0


def test_added_file_relists_only_its_branch(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    dav.add("Photos/2025/Γιορτή/new.jpg")
    dav.list_calls.clear()
    r = run(cfg, dav, st)
    assert r.new == 1 and "Photos/2024" not in dav.list_calls and "Photos/2025/Γιορτή" in dav.list_calls
    assert st.known_under(["Photos"]) == 32


def test_removed_file_is_marked_deleted_and_returns(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    dav.remove("Photos/2024/p3.jpg")
    r = run(cfg, dav, st)
    assert r.deleted == 1 and st.known_under(["Photos"]) == 30
    fid = dav.add("Photos/2024/p3.jpg", etag="back", file_id=999)
    run(cfg, dav, st)
    assert st.get_file(fid)["deleted"] == 0


def test_changed_etag_resets_derived_data(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    fid = dav.files["Photos/2024/p1.jpg"].file_id
    st.set_fields(fid, {"taken_utc": 5}, level=1)
    st.set_thumbhash(fid, b"x")
    dav.touch("Photos/2024/p1.jpg", "newetag")
    r = run(cfg, dav, st)
    row = st.get_file(fid)
    assert r.changed == 1 and row["level"] == 0 and row["thumbhash"] is None


def test_rename_keeps_the_same_file_id(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    fid = dav.files["Photos/2024/p2.jpg"].file_id
    dav.rename("Photos/2024/p2.jpg", "Photos/2025/moved.jpg")
    r = run(cfg, dav, st)
    assert r.deleted == 0 and st.get_file(fid)["path"] == "Photos/2025/moved.jpg"


def test_listing_failure_aborts_and_deletes_nothing(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    dav.add("Photos/2024/extra.jpg")                 # make the root etag change so a walk happens
    dav.fail_on.add("Photos/2024")                   # a timeout on one folder
    rid = st.start_run()
    with pytest.raises(ListingError):
        scan(dav, st, cfg, rid)
    assert st.known_under(["Photos"]) == 31          # nothing was treated as deleted
    assert st.folder_etag("Photos") != dav.list_folder("Photos")[0].etag   # the root etag was not committed, so the next run walks again


def test_safeguard_blocks_mass_disappearance_unless_accepted(env):
    cfg, dav, st = env
    run(cfg, dav, st)
    for i in range(10):
        dav.remove(f"Photos/2024/p{i}.jpg")          # 10 of 31 gone
    rid = st.start_run()
    with pytest.raises(SafeguardTripped):
        scan(dav, st, cfg, rid)
    assert st.known_under(["Photos"]) == 31
    r = run(cfg, dav, st, accept_deletions=True)
    assert r.deleted == 10


def test_never_index_folder_is_skipped(env):
    cfg, dav, st = env
    cfg.never_index = ["Family"]
    dav.add("Photos/Family/secret.jpg")
    r = run(cfg, dav, st)
    assert st.known_under(["Photos"]) == 31 and r.new == 31


def test_config_refuses_writing_roots():
    with pytest.raises(ValueError):
        Config(roots=["Photos/Family"]).all_roots_checked()
    with pytest.raises(ValueError):
        Config(roots=["BookahPhotos/index"]).all_roots_checked()
    Config(roots=["BookahPhotos/test-root"]).all_roots_checked()
