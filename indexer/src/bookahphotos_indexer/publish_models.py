"""Publishing the phone's search model into Nextcloud (BookahPhotos/models/ only), the same careful way as a snapshot: every file is uploaded under a temporary name,
moved onto its final name and size-checked, and `manifest.json` is written LAST, so the app never sees a manifest that points at a missing or partial file.

Layout: BookahPhotos/models/manifest.json  and  BookahPhotos/models/<dir>/<file>  for the files the manifest lists (text model, tokenizer file)."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from .dav import Dav
from .publish import TEMP_PREFIX, PublishError, ensure_folder

MODELS_FOLDER = "BookahPhotos/models"
MODEL_ID = "xlmr-vit-b32-laion5b"        # must equal asset_vectors.model_id in the snapshot: the app refuses to search when they differ
VERSION = 1


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def make_manifest(files: dict[str, Path], directory: str, model_id: str = MODEL_ID, text_input: str = "text", seq: int = 77, dim: int = 512) -> dict:
    return {
        "version": VERSION, "model_id": model_id, "dir": directory, "created_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "text_model": {"input": text_input, "input_type": "int32", "seq": seq, "output_dim": dim},
        "files": [{"name": n, "size": p.stat().st_size, "sha256": sha256_of(p)} for n, p in sorted(files.items())],
    }


def publish_models(dav: Dav, files: dict[str, Path], directory: str, folder: str = MODELS_FOLDER, **manifest_kw) -> dict:
    """Upload `files` ({final name: local path}) to folder/directory/ and then the manifest. Returns the manifest."""
    manifest = make_manifest(files, directory, **manifest_kw)
    target = f"{folder}/{directory}"
    ensure_folder(dav, target)
    if dav.size_of(f"{folder}/.nomedia") is None:
        dav.put(f"{folder}/.nomedia", b"")
    for name, path in sorted(files.items()):
        final, part = f"{target}/{name}", f"{target}/{TEMP_PREFIX}{name}"
        dav.put(part, Path(path).read_bytes())
        dav.move(part, final)
        remote = dav.size_of(final)
        if remote != Path(path).stat().st_size:
            raise PublishError(f"uploaded {name} has size {remote}, expected {Path(path).stat().st_size}; manifest not written")
    mpart = f"{folder}/{TEMP_PREFIX}manifest.json"
    dav.put(mpart, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"))
    dav.move(mpart, f"{folder}/manifest.json")        # last
    return manifest
