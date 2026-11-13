"""The daily job: everything that new photos need, in order, polite and safe. Meant to be started once a day by Windows Task Scheduler (NOT installed unless
Chris says yes). It never wakes the PC, does nothing when Nextcloud is not healthy, does not start twice at once, stops each step at its time limit, and
publishes only a snapshot that passed verification (`run` checks that itself).

Order: scan + dates + placeholders -> content vectors (card checked) -> faces -> people and ops (read only) -> verified snapshot, published if it changed."""
from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Callable

import requests

log = logging.getLogger("bookahphotos.daily")
STALE_LOCK_S = 6 * 3600


def preflight(base_url: str, get: Callable = requests.get, timeout: float = 15.0) -> str | None:
    """None when Nextcloud is healthy, otherwise the reason to skip today."""
    try:
        r = get(base_url.rstrip("/") + "/status.php", timeout=timeout)
        s = r.json()
    except Exception as e:
        return f"Nextcloud is not reachable ({type(e).__name__})"
    if not s.get("installed", False):
        return "Nextcloud says it is not installed"
    if s.get("maintenance"):
        return "Nextcloud is in maintenance mode"
    if s.get("needsDbUpgrade"):
        return "Nextcloud needs its database upgrade (nothing can be listed until that is done)"
    return None


class Lock:
    """One daily job at a time. A lock older than six hours is treated as left over from a crash."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self.held = False

    def __enter__(self) -> "Lock":
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists() and time.time() - self.path.stat().st_mtime > STALE_LOCK_S:
            self.path.unlink(missing_ok=True)
        try:
            fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, str(os.getpid()).encode())
            os.close(fd)
            self.held = True
        except FileExistsError:
            self.held = False
        return self

    def __exit__(self, *a) -> None:
        if self.held:
            self.path.unlink(missing_ok=True)


def steps_for(total_minutes: float) -> list[list[str]]:
    """The command lines of one daily run. Time limits are shares of the total; a quiet day finishes in a few minutes."""
    m = lambda share: str(max(1.0, round(total_minutes * share, 1)))
    return [
        ["run", "--no-snapshot", "--minutes", m(0.15)],
        ["content", "--minutes", m(0.4)],
        ["faces", "--minutes", m(0.2)],
        ["group", "--ops", "--move-ops"],
        ["run", "--skip-previews"],            # scans again (cheap: unchanged folders are skipped), builds, verifies and publishes only if something changed
    ]


def run_daily(base_url: str, lock_path: Path, steps: list[list[str]], main_fn: Callable[[list[str]], int], preflight_fn: Callable[[str], str | None] = preflight) -> tuple[int, str]:
    """(exit code, what happened in one line). 0 = done or skipped for a good reason; non-zero = a step failed (later steps are not run)."""
    with Lock(lock_path) as lock:
        if not lock.held:
            return 0, "skipped: another daily run is still going"
        why = preflight_fn(base_url)
        if why:
            return 0, f"skipped: {why}"
        for argv in steps:
            log.info("daily step: %s", argv[0])
            rc = main_fn(argv)
            if rc != 0:
                return rc, f"stopped: step '{argv[0]}' ended with code {rc}; later steps were not run"
        return 0, "done"
