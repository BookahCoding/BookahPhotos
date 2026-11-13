"""Snapshot schema (the contract with the Android app). Documented in docs/snapshot-schema.md.

Version 1.3 (1.2 plus the table `hidden_assets`). Version 1.2 (1.1 plus the table `applied_ops`). Version 1.1 (1.0 plus: the content tables are filled, a `labels` table, and `memories.key`). The major version changes when the app can no longer
read the file ("update the app"); the minor version changes when columns or tables are added (older apps ignore them).
"""
from __future__ import annotations

import sqlite3

SCHEMA_MAJOR = 1
SCHEMA_MINOR = 3
USER_VERSION = SCHEMA_MAJOR * 1000 + SCHEMA_MINOR  # PRAGMA user_version

# assets.flags bit mask
FLAG_VIDEO = 1
FLAG_SCREENSHOT = 2
FLAG_SELFIE = 4
FLAG_PANORAMA = 8
FLAG_MOTION_PHOTO = 16

# assets.indexed_level
LEVEL_LISTED = 0      # known to Nextcloud, nothing read yet
LEVEL_METADATA = 1    # date, size, camera, place, placeholder
LEVEL_VECTORS = 2     # + content vector (Phase 4)
LEVEL_FACES = 3       # + faces (Phase 4)

DDL = """
CREATE TABLE meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE places (
    id        INTEGER PRIMARY KEY,
    name      TEXT NOT NULL,          -- English or ASCII name
    name_el   TEXT,                   -- Greek name where known
    region    TEXT,
    country   TEXT,                   -- ISO 3166 code
    latitude  REAL NOT NULL,
    longitude REAL NOT NULL
);

CREATE TABLE assets (
    id                 INTEGER PRIMARY KEY,   -- the Nextcloud file id (stable across renames)
    path               TEXT NOT NULL,         -- path in the user's Nextcloud, e.g. Photos/2024/x.jpg
    etag               TEXT NOT NULL,
    size               INTEGER NOT NULL,
    mime_type          TEXT NOT NULL,
    taken_utc          INTEGER,               -- capture time, seconds since 1970 UTC (NULL if unknown)
    taken_offset_min   INTEGER,               -- local UTC offset in minutes at capture
    taken_zone_assumed INTEGER NOT NULL DEFAULT 0, -- 1 when no offset was recorded and the home zone was assumed
    taken_source       TEXT,                  -- exif | memories | file_time | none
    day_key            TEXT,                  -- local day, YYYY-MM-DD
    width              INTEGER,
    height             INTEGER,
    duration_ms        INTEGER,
    latitude           REAL,
    longitude          REAL,
    place_id           INTEGER REFERENCES places(id),
    camera             TEXT,
    lens               TEXT,
    exposure           TEXT,                  -- JSON: exposure time, f-number, ISO, focal length
    flags              INTEGER NOT NULL DEFAULT 0,
    content_hash       TEXT,                  -- NULL in version 1 (Nextcloud stores no checksums; filled lazily later)
    thumbhash          BLOB,                  -- tiny placeholder for the grid
    duplicate_group    INTEGER,
    indexed_level      INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX assets_taken ON assets(taken_utc);
CREATE INDEX assets_day ON assets(day_key);
CREATE INDEX assets_place ON assets(place_id);

CREATE TABLE asset_vectors (               -- empty until Phase 4
    asset_id INTEGER PRIMARY KEY REFERENCES assets(id),
    model_id TEXT NOT NULL,
    dim      INTEGER NOT NULL,
    vec      BLOB NOT NULL                 -- little-endian float16, length dim
);

CREATE TABLE people (                      -- empty until Phase 4
    id            INTEGER PRIMARY KEY,
    name          TEXT,
    cover_face_id INTEGER,
    hidden        INTEGER NOT NULL DEFAULT 0,
    face_count    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE faces (                       -- empty until Phase 4; face vectors stay in the indexer's private database
    id        INTEGER PRIMARY KEY,
    asset_id  INTEGER NOT NULL REFERENCES assets(id),
    x         REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL,   -- fractions of the image
    person_id INTEGER REFERENCES people(id),
    quality   REAL
);
CREATE INDEX faces_asset ON faces(asset_id);
CREATE INDEX faces_person ON faces(person_id);

CREATE TABLE memories (                    -- filled in Phase 4
    id          INTEGER PRIMARY KEY,
    key         TEXT,                      -- stable key (schema 1.1): what the op 'unwanted_memory' refers to
    type        TEXT NOT NULL,             -- trip | month | on_this_day | person
    title_en    TEXT,
    title_el    TEXT,
    start_day   TEXT,
    end_day     TEXT,
    cover_asset INTEGER REFERENCES assets(id)
);
CREATE TABLE memory_assets (
    memory_id INTEGER NOT NULL REFERENCES memories(id),
    asset_id  INTEGER NOT NULL REFERENCES assets(id),
    position  INTEGER NOT NULL,
    PRIMARY KEY (memory_id, asset_id)
) WITHOUT ROWID;

CREATE TABLE hidden_assets (               -- 1.3: photos the reader hid (op hide_photo). They are NOT in assets, vectors, tags, faces or memories; the app shows them only behind an unlock
    id         INTEGER PRIMARY KEY,        -- the Nextcloud file id
    path       TEXT NOT NULL,
    etag       TEXT NOT NULL,
    size       INTEGER NOT NULL,
    mime_type  TEXT NOT NULL,
    taken_utc  INTEGER,
    day_key    TEXT,
    duration_ms INTEGER,
    flags      INTEGER NOT NULL DEFAULT 0,
    thumbhash  BLOB
);

CREATE TABLE applied_ops (                -- 1.2: ids of the corrections (ops) from the app that this snapshot already contains; the app drops its own pending copy of them
    op_id TEXT PRIMARY KEY
) WITHOUT ROWID;

CREATE TABLE tags (                        -- filled in Phase 4
    asset_id  INTEGER NOT NULL REFERENCES assets(id),
    label_key TEXT NOT NULL REFERENCES labels(key),
    score     REAL NOT NULL,
    PRIMARY KEY (asset_id, label_key)
) WITHOUT ROWID;

CREATE TABLE labels (                      -- schema 1.1: the tag labels with their names, for the category chips
    key     TEXT PRIMARY KEY,
    name_en TEXT NOT NULL,
    name_el TEXT NOT NULL
) WITHOUT ROWID;
"""

EXPECTED_TABLES = {"meta", "places", "assets", "asset_vectors", "people", "faces", "memories", "memory_assets", "tags", "labels", "applied_ops", "hidden_assets"}


def create(conn: sqlite3.Connection) -> None:
    conn.executescript(DDL)
    conn.execute(f"PRAGMA user_version = {USER_VERSION}")


def set_meta(conn: sqlite3.Connection, **values: object) -> None:
    conn.executemany("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", [(k, str(v)) for k, v in values.items()])
