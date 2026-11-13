"""Publishing a snapshot into Nextcloud (under BookahPhotos/ only).

The snapshot is uploaded under a temporary name and then moved onto its final name (an atomic step on the server), its size is
checked, and the manifest is written LAST, so the app never sees a manifest pointing at a missing or partial file.
"""
from __future__ import annotations

import re
from pathlib import Path

from .dav import Dav, DavError
from .snapshot import manifest_bytes

SNAPSHOT_RE = re.compile(r"^bookahphotos-g(\d+)\.db$")
TEMP_PREFIX = "upload-"


class PublishError(Exception):
    pass


def ensure_folder(dav: Dav, folder: str) -> None:
    parts = folder.strip("/").split("/")
    for i in range(1, len(parts) + 1):
        dav.mkcol("/".join(parts[:i]))      # 405 (already exists) is accepted by the client


def snapshot_name(generation: int) -> str:
    return f"bookahphotos-g{generation:06d}.db"


def publish(dav: Dav, folder: str, snapshot_path: Path, manifest: dict, keep: int = 2) -> None:
    ensure_folder(dav, folder)
    if dav.size_of(f"{folder}/.nomedia") is None:
        dav.put(f"{folder}/.nomedia", b"")      # so nothing in this folder shows up as a photo
    final = f"{folder}/{manifest['file']}"
    part = f"{folder}/{TEMP_PREFIX}{manifest['file']}"     # Nextcloud refuses names ending in .part, so the temporary name uses a prefix
    dav.put(part, snapshot_path.read_bytes())
    dav.move(part, final)
    remote = dav.size_of(final)
    if remote != snapshot_path.stat().st_size:
        raise PublishError(f"uploaded snapshot has size {remote}, expected {snapshot_path.stat().st_size}; manifest not written")
    mpart = f"{folder}/{TEMP_PREFIX}manifest.json"
    dav.put(mpart, manifest_bytes(manifest))
    dav.move(mpart, f"{folder}/manifest.json")       # last
    prune(dav, folder, keep)


def prune(dav: Dav, folder: str, keep: int) -> list[str]:
    """Keep the newest `keep` snapshots; remove the older ones and any leftover .part files. Only ever touches this folder."""
    _, kids = dav.list_folder(folder)
    snaps = sorted(((int(m.group(1)), k.path) for k in kids if not k.is_dir and (m := SNAPSHOT_RE.match(k.path.rsplit("/", 1)[-1]))), reverse=True)
    removed: list[str] = []
    for _, path in snaps[keep:]:
        dav.delete(path)
        removed.append(path)
    for k in kids:
        if not k.is_dir and k.path.rsplit("/", 1)[-1].startswith(TEMP_PREFIX):
            try:
                dav.delete(k.path)
            except DavError:
                pass
    return removed
