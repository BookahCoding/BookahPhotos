"""Walking the photo roots in Nextcloud and finding new, changed and removed files."""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

from .config import Config
from .dav import Dav, ListingError
from .state import State

log = logging.getLogger("bookahphotos.scan")
MEDIA_PREFIXES = ("image/", "video/")


class SafeguardTripped(Exception):
    def __init__(self, missing: int, known: int):
        super().__init__(f"{missing} of {known} known photos disappeared; not publishing. Check that Nextcloud and the sync are healthy, then run again with --accept-deletions.")
        self.missing, self.known = missing, known


@dataclass
class ScanResult:
    folders_listed: int = 0
    folders_skipped: int = 0
    new: int = 0
    changed: int = 0
    same: int = 0
    deleted: int = 0
    nothing_changed: bool = False
    notes: list[str] = field(default_factory=list)


def _is_media(mime: str) -> bool:
    return mime.startswith(MEDIA_PREFIXES)


def scan(dav: Dav, state: State, cfg: Config, run_id: int, accept_deletions: bool = False) -> ScanResult:
    """List every root. Any listing failure raises ListingError and nothing is marked deleted."""
    cfg.all_roots_checked()
    res = ScanResult()
    roots = [r.strip("/") for r in cfg.roots]
    known_before = state.known_under(roots)
    folder_etags: list[tuple[str, str]] = []

    def walk(path: str, listed: tuple | None = None) -> None:
        me, kids = listed if listed else dav.list_folder(path)
        if not listed:
            res.folders_listed += 1
        for k in kids:
            if k.is_dir:
                if k.path.rsplit("/", 1)[-1] in cfg.never_index:
                    continue
                if state.folder_etag(k.path) == k.etag:
                    n = state.mark_subtree_seen(k.path, run_id)
                    res.folders_skipped += 1
                    log.debug("skipped an unchanged folder holding %d known files", n)
                    continue
                walk(k.path)
            elif _is_media(k.mime):
                verdict = state.upsert_listed(k.file_id, k.path, k.etag, k.size, k.mime, run_id)
                if verdict == "new":
                    res.new += 1
                elif verdict == "changed":
                    res.changed += 1
                else:
                    res.same += 1
        state.db.commit()
        # Folder etags are saved only when the WHOLE scan, including the deletion decision, has succeeded. Saving them earlier would
        # let a failed run or a tripped safeguard hide the very changes it found: the next run would see "unchanged" and skip them.
        folder_etags.append((path, me.etag))

    changed_roots = 0
    for root in roots:
        # a quick look at the root: if its etag is unchanged nothing below it changed
        listed = dav.list_folder(root)
        res.folders_listed += 1
        if state.folder_etag(root) == listed[0].etag:
            state.mark_subtree_seen(root, run_id)
            res.folders_skipped += 1
            continue
        changed_roots += 1
        walk(root, listed)
    res.nothing_changed = changed_roots == 0

    # every listing succeeded: now (and only now) work out what disappeared
    missing = state.missing_under(roots, run_id)
    if missing and known_before >= cfg.safeguard_min_known and len(missing) / known_before > cfg.safeguard_ratio and not accept_deletions:
        raise SafeguardTripped(len(missing), known_before)
    state.mark_deleted(missing)
    res.deleted = len(missing)
    for p, e in folder_etags:
        state.commit_folder(p, e, run_id)
    return res
