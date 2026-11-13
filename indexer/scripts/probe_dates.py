"""Read-only date check on recent Xiaomi photos and small MOV videos: Memories `datetaken` and EXIF against ffprobe / filename. Prints times only."""
import json
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.creds import read_credential
from bookahphotos_indexer.dav import Dav

LOCAL = Path(r"D:\Nextcloud\Photos")
cfg = Config.load(); _, pw = read_credential("BookahPhotos-nextcloud"); dav = Dav(cfg.base_url, cfg.user, pw)


def ts(v):
    return datetime.fromtimestamp(v, tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S") if isinstance(v, (int, float)) else v


def ids_in_folder(folder: Path):
    rel = "Photos/" + "/".join(folder.relative_to(LOCAL).parts) if folder != LOCAL else "Photos"
    _, kids = dav.list_folder(rel)
    return {k.path.rsplit("/", 1)[-1]: k for k in kids if not k.is_dir}


print("== Xiaomi photos (IMG_2026*.jpg)")
shown = 0
for p in sorted(LOCAL.rglob("IMG_2026*.jpg"), reverse=True):
    if shown >= 3:
        break
    k = ids_in_folder(p.parent).get(p.name)
    info = dav.memories_info(k.file_id) if k else None
    if not info:
        continue
    ex = info.get("exif") or {}
    m = re.search(r"IMG_(\d{8})_(\d{6})", p.name)
    print("name time", f"{m.group(1)} {m.group(2)}", "| datetaken", ts(info.get("datetaken")), "| exif", {x: ex.get(x) for x in ("Make", "Model", "DateTimeOriginal", "CreateDate", "OffsetTime", "OffsetTimeOriginal", "TimeZone") if ex.get(x) is not None})
    shown += 1

print("== small MOV videos (original downloaded, read only)")
done = 0
for p in sorted(LOCAL.rglob("*.mov"), key=lambda x: x.stat().st_size):
    if done >= 3:
        break
    if p.stat().st_size > 8_000_000 or p.stat().st_size < 500_000:
        continue
    k = ids_in_folder(p.parent).get(p.name)
    if not k:
        continue
    info = dav.memories_info(k.file_id) or {}
    data = dav.get("Photos/" + "/".join(p.relative_to(LOCAL).parts))
    tmp = Path(tempfile.mkdtemp()) / "v.mov"; tmp.write_bytes(data)
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "json", str(tmp)], capture_output=True, text=True).stdout
    tags = json.loads(out or "{}").get("format", {}).get("tags", {})
    ex = info.get("exif") or {}
    print("size", k.size, "| datetaken", ts(info.get("datetaken")), "| memories exif", {x: ex.get(x) for x in ex if "ate" in x or "ime" in x or "Offset" in x}, "| ffprobe", {x: tags[x] for x in tags if "creat" in x.lower() or "date" in x.lower()})
    done += 1
