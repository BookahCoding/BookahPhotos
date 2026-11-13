import os
import time

import pytest

from bookahphotos_indexer import daily


class Resp:
    def __init__(self, data):
        self.data = data

    def json(self):
        if isinstance(self.data, Exception):
            raise self.data
        return self.data


def fake_get(data):
    def get(url, timeout=0):
        if isinstance(data, Exception) and not isinstance(data, ValueError):
            raise data
        return Resp(data)
    return get


@pytest.mark.parametrize("status,expect", [
    ({"installed": True, "maintenance": False, "needsDbUpgrade": False}, None),
    ({"installed": True, "maintenance": True, "needsDbUpgrade": False}, "maintenance"),
    ({"installed": True, "maintenance": False, "needsDbUpgrade": True}, "database upgrade"),
    ({"installed": False}, "not installed"),
    (ConnectionError("down"), "not reachable"),
    (ValueError("not json"), "not reachable"),
])
def test_preflight(status, expect):
    r = daily.preflight("https://nc.example", get=fake_get(status))
    assert (r is None) if expect is None else (expect in r)


def test_steps_are_in_order_and_publishing_is_last():
    steps = daily.steps_for(60)
    assert [s[0] for s in steps] == ["run", "content", "faces", "group", "run"]
    assert "--no-snapshot" in steps[0] and "--skip-previews" in steps[-1]
    assert steps[3] == ["group", "--ops", "--move-ops"]                     # since 13 Nov 2026 (Chris said yes; first batch checked by hand) applied op files are archived


def test_all_steps_run_in_order_when_healthy(tmp_path):
    seen = []
    rc, what = daily.run_daily("u", tmp_path / "l", daily.steps_for(30), lambda argv: seen.append(argv[0]) or 0, lambda u: None)
    assert (rc, what) == (0, "done") and seen == ["run", "content", "faces", "group", "run"]
    assert not (tmp_path / "l").exists()                                   # the lock is gone afterwards


def test_a_failing_step_stops_the_rest_so_nothing_is_published(tmp_path):
    seen = []
    def fake_main(argv):
        seen.append(argv[0])
        return 2 if argv[0] == "run" and len(seen) == 1 else 0              # the first scan fails (a listing error)
    rc, what = daily.run_daily("u", tmp_path / "l", daily.steps_for(30), fake_main, lambda u: None)
    assert rc == 2 and seen == ["run"] and "later steps were not run" in what


def test_unhealthy_nextcloud_means_nothing_runs_and_is_not_an_error(tmp_path):
    seen = []
    rc, what = daily.run_daily("u", tmp_path / "l", daily.steps_for(30), lambda argv: seen.append(argv) or 0, lambda u: "Nextcloud needs its database upgrade")
    assert rc == 0 and seen == [] and what.startswith("skipped")


def test_only_one_daily_run_at_a_time_and_a_stale_lock_is_ignored(tmp_path):
    lock = tmp_path / "l"
    with daily.Lock(lock) as a:
        assert a.held
        rc, what = daily.run_daily("u", lock, daily.steps_for(30), lambda argv: 0, lambda u: None)
        assert rc == 0 and "still going" in what
    assert not lock.exists()
    lock.write_text("123")
    old = time.time() - 7 * 3600
    os.utime(lock, (old, old))
    with daily.Lock(lock) as b:
        assert b.held                                                       # a lock left by a crash 7 hours ago does not block forever
