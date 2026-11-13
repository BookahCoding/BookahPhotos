"""Read-only: is the WebDAV last-modified time a usable date for videos Memories does not know? Compare it with the capture date for videos where both exist."""
import sqlite3
import sys
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.creds import read_credential
from bookahphotos_indexer.dav import Dav

cfg = Config.load(); _, pw = read_credential("BookahPhotos-indexer"); dav = Dav(cfg.base_url, cfg.user, pw)
db = sqlite3.connect(r"C:\BookahPC\bookahphotos\indexer.db"); db.row_factory = sqlite3.Row
rows = db.execute("SELECT file_id, path, mime, taken_utc FROM files WHERE deleted=0 AND mime LIKE 'video/%'").fetchall()
folders = {}
for r in rows:
    folders.setdefault(r["path"].rsplit("/", 1)[0], []).append(r)
dated_diffs, undated_years = [], {}
for i, (folder, rs) in enumerate(sorted(folders.items(), key=lambda kv: -len(kv[1]))[:60]):
    _, kids = dav.list_folder(folder)
    mt = {k.file_id: parsedate_to_datetime(k.modified) for k in kids if k.modified}
    for r in rs:
        m = mt.get(r["file_id"])
        if not m:
            continue
        if r["taken_utc"]:
            dated_diffs.append(abs((m - datetime.fromtimestamp(r["taken_utc"], tz=timezone.utc)).total_seconds()) / 86400)
        else:
            undated_years[m.year] = undated_years.get(m.year, 0) + 1
n = len(dated_diffs); dated_diffs.sort()
print(f"dated videos compared: {n}")
if n:
    print("mtime within 1 day of capture:", sum(d <= 1 for d in dated_diffs), "| within 30 days:", sum(d <= 30 for d in dated_diffs), "| median diff days:", round(dated_diffs[n // 2], 1))
print("undated videos by mtime year (in the 60 biggest video folders):", dict(sorted(undated_years.items())))
