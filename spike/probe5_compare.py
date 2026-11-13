"""Compare Memories' date fields with EXIF read locally from the same files (files that are present on disk)."""
import json, subprocess, re, os
from PIL import Image
import pillow_heif
pillow_heif.register_heif_opener()
j = json.load(open(r"C:\BookahPC\bookahphotos\spike\probe5.json", encoding="utf-8-sig"))
print("root propfind has checksums element:", "checksums" in j["rootPropfind"], "| etag present:", "getetag" in j["rootPropfind"])
print("days:", j.get("daysCount"), "total photos in days:", j.get("daysTotalPhotos"), j.get("daysError"))
print("days first:", j.get("daysFirst")); print("day one sample:", (j.get("dayOneSample") or "")[:350])
for it in j["items"]:
    p = it["local"]; loc = ""
    try:
        if it["kind"] in ("heic", "jpg", "png", "tiff"):
            im = Image.open(p); ex = im.getexif(); sub = ex.get_ifd(0x8769)
            loc = f"EXIF DateTimeOriginal={sub.get(0x9003)} OffsetTimeOriginal={sub.get(0x9011)} DateTime={ex.get(0x0132)}"
        else:
            o = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format_tags=creation_time", "-of", "default=nw=1:nk=1", p], capture_output=True, text=True).stdout.strip()
            loc = f"ffprobe creation_time={o}"
    except Exception as e:
        loc = f"local read failed: {type(e).__name__}"
    ex = it["exif"]; exs = ""
    if isinstance(ex, dict): exs = {k: ex[k] for k in ex if k in ("DateTimeOriginal", "OffsetTimeOriginal", "OffsetTime", "GPSLatitude", "Make", "Model", "CreateDate")}
    print(f"{it['kind']:5s} id={it['id']:>6s} checksums='{it['checksums']}' datetaken={it['datetaken']} mtime={it['mtime']} | {loc} | memories exif subset={exs}")
