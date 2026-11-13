"""Fully synthetic snapshot for app development and benchmarks. No real photos, names or coordinates."""
from __future__ import annotations

import json
import random
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import schema
from .snapshot import finalize_snapshot

# invented places on a grid around a made-up centre; they do not describe real locations
_PLACE_NAMES = [f"Testville {i}" for i in range(1, 41)]


def make_fake_snapshot(path: Path, n_assets: int = 50_000, seed: int = 1) -> Path:
    rng = random.Random(seed)
    path = Path(path)
    if path.exists():
        path.unlink()
    conn = sqlite3.connect(path)
    schema.create(conn)
    schema.set_meta(conn, schema_major=schema.SCHEMA_MAJOR, schema_minor=schema.SCHEMA_MINOR, fake=1,
                    generation=1, created_utc=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                    roots="Fake", models="none (Phase 1 snapshot has no vectors or faces)", home_zone="Europe/Athens")
    places = [(i + 1, name, f"Δοκιμή {i + 1}", "Testregion", "XX", 30.0 + (i % 8) * 0.5, 20.0 + (i // 8) * 0.5) for i, name in enumerate(_PLACE_NAMES)]
    conn.executemany("INSERT INTO places(id, name, name_el, region, country, latitude, longitude) VALUES (?,?,?,?,?,?,?)", places)
    start = datetime(2006, 1, 1, tzinfo=timezone.utc)
    span = int((datetime(2026, 11, 1, tzinfo=timezone.utc) - start).total_seconds())
    rows = []
    for i in range(n_assets):
        taken = start + timedelta(seconds=rng.randrange(span))
        offset = rng.choice([120, 180, 60, 0])
        local = taken + timedelta(minutes=offset)
        is_video = rng.random() < 0.08
        flags = schema.FLAG_VIDEO if is_video else 0
        if not is_video and rng.random() < 0.05:
            flags |= schema.FLAG_SCREENSHOT
        has_place = rng.random() < 0.7
        place = rng.randint(1, len(places)) if has_place else None
        lat = lon = None
        if place:
            p = places[place - 1]
            lat, lon = p[5] + rng.uniform(-0.01, 0.01), p[6] + rng.uniform(-0.01, 0.01)
        w, h = rng.choice([(4032, 3024), (3024, 4032), (1920, 1080), (1080, 1920), (3072, 4096)])
        rows.append((
            10_000 + i, f"Fake/{taken.year}/IMG_{i:06d}.{'mov' if is_video else 'jpg'}", f"etag{i:08x}", rng.randint(200_000, 9_000_000),
            "video/quicktime" if is_video else "image/jpeg", int(taken.timestamp()), offset, 0, "exif", local.strftime("%Y-%m-%d"),
            w, h, rng.randint(2_000, 90_000) if is_video else None, lat, lon, place,
            rng.choice(["Test Phone A", "Test Phone B", "Test Camera C"]), None,
            json.dumps({"iso": rng.choice([50, 100, 200, 400]), "f": 1.8}), flags, None, bytes(rng.getrandbits(8) for _ in range(25)), None, schema.LEVEL_METADATA,
        ))
    conn.executemany(
        "INSERT INTO assets(id, path, etag, size, mime_type, taken_utc, taken_offset_min, taken_zone_assumed, taken_source, day_key, width, height, "
        "duration_ms, latitude, longitude, place_id, camera, lens, exposure, flags, content_hash, thumbhash, duplicate_group, indexed_level) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    conn.commit()
    finalize_snapshot(conn)
    conn.close()
    return path
