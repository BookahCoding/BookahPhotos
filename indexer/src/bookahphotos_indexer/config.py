"""Configuration. A JSON file outside the repo (default C:\\BookahPC\\bookahphotos\\indexer-config.json). No secrets in it."""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_CONFIG_PATH = Path(r"C:\BookahPC\bookahphotos\indexer-config.json")


@dataclass
class Config:
    base_url: str = "https://cloud.example.ts.net"
    user: str = ""
    credential_target: str = "BookahPhotos-indexer"   # Windows Credential Manager entry holding the app password
    roots: list[str] = field(default_factory=lambda: ["Photos", "InstantUpload"])
    never_index: list[str] = field(default_factory=lambda: ["Family"])      # folder names that are never listed
    publish_dir: str = "BookahPhotos/index"            # where the snapshot and manifest are published (in the user's Nextcloud)
    state_dir: str = r"C:\BookahPC\bookahphotos"        # private state, logs, temporary files (never in Nextcloud or Git)
    home_zone: str = "Europe/Athens"                    # assumed when a photo has no recorded UTC offset
    safeguard_ratio: float = 0.05                       # refuse to publish if more than this share of known photos disappeared
    safeguard_min_known: int = 50                       # below this many known photos the ratio check is not applied
    request_timeout_s: float = 60.0
    min_request_interval_s: float = 0.05                # pause between requests to keep the OptiPlex calm
    preview_workers: int = 2                            # at most 1 to 2 concurrent preview requests
    preview_size: int = 256
    tiff_last: bool = True                              # large TIFF scans get their previews after everything else
    keep_snapshots: int = 2

    @classmethod
    def load(cls, path: Path | None = None) -> "Config":
        p = Path(path) if path else DEFAULT_CONFIG_PATH
        cfg = cls()
        if p.exists():
            data = json.loads(p.read_text(encoding="utf-8"))
            for k, v in data.items():
                if not hasattr(cfg, k):
                    raise ValueError(f"unknown config key: {k}")
                setattr(cfg, k, v)
        return cfg

    def all_roots_checked(self) -> None:
        for r in self.roots:
            parts = [x for x in r.strip("/").split("/") if x]
            if any(x in self.never_index for x in parts):
                raise ValueError(f"root {r!r} is on the never-index list")
            if parts and parts[0] == "BookahPhotos" and parts[1:2] != ["test-root"]:
                raise ValueError("the only BookahPhotos folder that may be indexed is BookahPhotos/test-root")
