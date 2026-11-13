"""Checks a finished snapshot file from the outside (read only). Returns a list of problems; an empty list means it passed.
Used by `bookahphotos-indexer verify-snapshot` and by the tests."""
from __future__ import annotations

import json
import math
import sqlite3
import struct
from pathlib import Path

from . import schema

MAX_TAGS = 5
MIN_FACES_FOR_PERSON = 5


def verify(path: Path, manifest: dict | None = None, expect_dim: int = 512) -> tuple[list[str], dict[str, int]]:
    problems: list[str] = []
    facts: dict[str, int] = {}
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    q = lambda sql, *a: conn.execute(sql, a).fetchall()
    one = lambda sql, *a: conn.execute(sql, a).fetchone()[0]
    try:
        if one("PRAGMA integrity_check") != "ok":
            problems.append("SQLite integrity check failed")
        if one("PRAGMA user_version") != schema.USER_VERSION:
            problems.append(f"user_version {one('PRAGMA user_version')} is not {schema.USER_VERSION}")
        tables = {r[0] for r in q("SELECT name FROM sqlite_master WHERE type='table'")}
        missing = schema.EXPECTED_TABLES - tables
        if missing:
            problems.append(f"missing tables: {sorted(missing)}")
            return problems, facts
        meta = dict(q("SELECT key, value FROM meta"))
        if meta.get("schema_major") != str(schema.SCHEMA_MAJOR) or meta.get("schema_minor") != str(schema.SCHEMA_MINOR):
            problems.append("meta schema version does not match")
        for t in ("assets", "asset_vectors", "tags", "labels", "people", "faces", "memories", "memory_assets", "places", "applied_ops", "hidden_assets"):
            facts[t] = one(f"SELECT COUNT(*) FROM {t}")
        if manifest:
            c = manifest.get("counts", {})
            if c.get("assets") != facts["assets"] or c.get("faces") != facts["faces"] or c.get("people") != facts["people"]:
                problems.append("manifest counts do not match the file")

        # references
        bad = lambda sql: one(sql)
        checks = {
            "hidden photos that are also in assets": "SELECT COUNT(*) FROM hidden_assets WHERE id IN (SELECT id FROM assets)",
            "vectors of hidden photos": "SELECT COUNT(*) FROM asset_vectors WHERE asset_id IN (SELECT id FROM hidden_assets)",
            "tags of hidden photos": "SELECT COUNT(*) FROM tags WHERE asset_id IN (SELECT id FROM hidden_assets)",
            "faces in hidden photos": "SELECT COUNT(*) FROM faces WHERE asset_id IN (SELECT id FROM hidden_assets)",
            "hidden photos in memories": "SELECT COUNT(*) FROM memory_assets WHERE asset_id IN (SELECT id FROM hidden_assets)",
            "hidden photos as memory covers": "SELECT COUNT(*) FROM memories WHERE cover_asset IN (SELECT id FROM hidden_assets)",
            "vectors for unknown assets": "SELECT COUNT(*) FROM asset_vectors WHERE asset_id NOT IN (SELECT id FROM assets)",
            "tags for unknown assets": "SELECT COUNT(*) FROM tags WHERE asset_id NOT IN (SELECT id FROM assets)",
            "tags with unknown label": "SELECT COUNT(*) FROM tags WHERE label_key NOT IN (SELECT key FROM labels)",
            "faces for unknown assets": "SELECT COUNT(*) FROM faces WHERE asset_id NOT IN (SELECT id FROM assets)",
            "faces of unknown people": "SELECT COUNT(*) FROM faces WHERE person_id IS NOT NULL AND person_id NOT IN (SELECT id FROM people)",
            "people with an unknown cover face": "SELECT COUNT(*) FROM people WHERE cover_face_id IS NOT NULL AND cover_face_id NOT IN (SELECT id FROM faces)",
            "people whose cover face is somebody else's": "SELECT COUNT(*) FROM people p JOIN faces f ON f.id=p.cover_face_id WHERE f.person_id IS NOT p.id",
            "memory pictures that are not assets": "SELECT COUNT(*) FROM memory_assets WHERE asset_id NOT IN (SELECT id FROM assets)",
            "memories of unknown id": "SELECT COUNT(*) FROM memory_assets WHERE memory_id NOT IN (SELECT id FROM memories)",
            "memory covers that are not assets": "SELECT COUNT(*) FROM memories WHERE cover_asset NOT IN (SELECT id FROM assets)",
            "memories without a key or title": "SELECT COUNT(*) FROM memories WHERE key IS NULL OR key='' OR title_en IS NULL OR title_el IS NULL",
            "memories without pictures": "SELECT COUNT(*) FROM memories WHERE id NOT IN (SELECT memory_id FROM memory_assets)",
            "duplicate memory keys": "SELECT COUNT(*) FROM (SELECT key FROM memories GROUP BY key HAVING COUNT(*) > 1)",
            "people with too few faces": f"SELECT COUNT(*) FROM people WHERE face_count < {MIN_FACES_FOR_PERSON}",
            "people whose face_count is wrong": "SELECT COUNT(*) FROM people p WHERE face_count <> (SELECT COUNT(*) FROM faces f WHERE f.person_id=p.id)",
            "face boxes outside the picture": "SELECT COUNT(*) FROM faces WHERE x<0 OR y<0 OR w<=0 OR h<=0 OR x+w>1.0001 OR y+h>1.0001",
            "assets with a place that does not exist": "SELECT COUNT(*) FROM assets WHERE place_id IS NOT NULL AND place_id NOT IN (SELECT id FROM places)",
            "memories using screenshots": "SELECT COUNT(*) FROM memory_assets m JOIN assets a ON a.id=m.asset_id WHERE a.flags & 2",
            "assets claiming a vector they do not have": "SELECT COUNT(*) FROM assets WHERE indexed_level>=2 AND id NOT IN (SELECT asset_id FROM asset_vectors)",
            "assets with a vector but a low level": "SELECT COUNT(*) FROM assets WHERE indexed_level<2 AND id IN (SELECT asset_id FROM asset_vectors)",
        }
        for what, sql in checks.items():
            n = bad(sql)
            if n:
                problems.append(f"{what}: {n}")
        over = one(f"SELECT COUNT(*) FROM (SELECT asset_id FROM tags GROUP BY asset_id HAVING COUNT(*) > {MAX_TAGS})")
        if over:
            problems.append(f"assets with more than {MAX_TAGS} tags: {over}")

        # the vectors themselves: right size, unit length (float16 rounding allows a little), no NaN
        n_bad = 0
        for dim, model, blob in q("SELECT dim, model_id, vec FROM asset_vectors LIMIT 2000"):
            if dim != expect_dim or len(blob) != 2 * dim:
                n_bad += 1
                continue
            v = struct.unpack(f"<{dim}e", blob)
            norm = math.sqrt(sum(x * x for x in v))
            if not (0.98 < norm < 1.02) or any(math.isnan(x) for x in v):
                n_bad += 1
        if n_bad:
            problems.append(f"vectors with the wrong size or length (of the first 2000): {n_bad}")
        if len(q("SELECT DISTINCT model_id FROM asset_vectors")) > 1:
            problems.append("vectors from more than one model")

        # privacy: no table that could hold face descriptions, and nothing but boxes and quality in faces
        cols = {r[1] for r in q("PRAGMA table_info(faces)")}
        if cols != {"id", "asset_id", "x", "y", "w", "h", "person_id", "quality"}:
            problems.append(f"faces has unexpected columns: {sorted(cols)}")
        extra = tables - schema.EXPECTED_TABLES - {"sqlite_sequence"}
        if extra:
            problems.append(f"unexpected tables: {sorted(extra)}")
        # no blob in the whole file may look like a face description (128 float32 = 512 bytes)
        if one("SELECT COUNT(*) FROM people WHERE typeof(name)='blob'"):
            problems.append("a people name is a blob")
    finally:
        conn.close()
    return problems, facts


def verify_manifest_file(path: Path, manifest_path: Path | None = None) -> tuple[list[str], dict[str, int]]:
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8")) if manifest_path else None
    return verify(Path(path), manifest)
