"""Finishing and describing a snapshot file."""
from __future__ import annotations

import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from . import schema


def finalize_snapshot(conn: sqlite3.Connection) -> None:
    """Compact the file so it is complete and self-contained (no -wal/-journal side files)."""
    conn.commit()
    conn.execute("PRAGMA journal_mode = DELETE")
    conn.execute("VACUUM")
    conn.commit()


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def read_counts(path: Path) -> dict[str, int]:
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        c = conn.execute("SELECT COUNT(*), SUM(flags & ?) FROM assets", (schema.FLAG_VIDEO,)).fetchone()
        videos = conn.execute("SELECT COUNT(*) FROM assets WHERE flags & ? != 0", (schema.FLAG_VIDEO,)).fetchone()[0]
        return {"assets": c[0], "videos": videos, "photos": c[0] - videos,
                "faces": conn.execute("SELECT COUNT(*) FROM faces").fetchone()[0],
                "people": conn.execute("SELECT COUNT(*) FROM people").fetchone()[0]}
    finally:
        conn.close()


def build_manifest(snapshot_path: Path, generation: int, models: dict[str, str] | None = None) -> dict:
    counts = read_counts(snapshot_path)
    return {
        "schema_major": schema.SCHEMA_MAJOR,
        "schema_minor": schema.SCHEMA_MINOR,
        "generation": generation,
        "created_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "file": snapshot_path.name,
        "size": snapshot_path.stat().st_size,
        "sha256": sha256_of(snapshot_path),
        "counts": counts,
        "models": models or {},
    }


def manifest_bytes(manifest: dict) -> bytes:
    return (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
