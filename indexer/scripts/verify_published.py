"""Downloads manifest.json and the snapshot it names from a published folder and checks size, SHA-256 and content. Read only."""
import hashlib
import json
import sqlite3
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.creds import read_credential
from bookahphotos_indexer.dav import Dav

folder = sys.argv[1]
cfg = Config.load()
_, pw = read_credential(sys.argv[2] if len(sys.argv) > 2 else "BookahPhotos-nextcloud")
dav = Dav(cfg.base_url, cfg.user, pw)
m = json.loads(dav.get(f"{folder}/manifest.json"))
data = dav.get(f"{folder}/{m['file']}")
ok = len(data) == m["size"] and hashlib.sha256(data).hexdigest() == m["sha256"]
tmp = Path(tempfile.mkdtemp()) / "s.db"
tmp.write_bytes(data)
c = sqlite3.connect(f"file:{tmp}?mode=ro", uri=True)
print("manifest generation", m["generation"], "| counts", m["counts"], "| size", m["size"], "| sha256 matches:", ok)
print("assets in downloaded snapshot:", c.execute("SELECT COUNT(*) FROM assets").fetchone()[0], "| schema", c.execute("PRAGMA user_version").fetchone()[0],
      "| thumbhashes:", c.execute("SELECT COUNT(*) FROM assets WHERE thumbhash IS NOT NULL").fetchone()[0])
for r in c.execute("SELECT substr(path, instr(path,'test-root/')+10), taken_source, day_key, taken_zone_assumed, place_id, flags FROM assets ORDER BY path"):
    print("  ", r)
print("places:", c.execute("SELECT name, name_el, country FROM places").fetchall())
names = sorted(k.path.rsplit("/", 1)[-1] for k in dav.list_folder(folder)[1])
print("folder contents:", names)
