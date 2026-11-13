"""Uploads GENERATED images (no real photos) into BookahPhotos/test-root in Chris's Nextcloud, for end-to-end checks.
Usage: python make_test_root.py add | change | remove | cleanup
Writes only under BookahPhotos/test-root (the client refuses anything else)."""
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from bookahphotos_indexer.config import Config
from bookahphotos_indexer.creds import read_credential
from bookahphotos_indexer.dav import Dav

ROOT = "BookahPhotos/test-root"


def make(color, exif_date=None, offset=None, gps=None, size=(640, 480), fmt="JPEG") -> bytes:
    im = Image.new("RGB", size, color)
    ImageDraw.Draw(im).ellipse((size[0] // 4, size[1] // 4, size[0] * 3 // 4, size[1] * 3 // 4), fill=(255, 255, 255))
    buf = io.BytesIO()
    if fmt == "HEIC":
        import pillow_heif
        pillow_heif.register_heif_opener()
        im.save(buf, "HEIF")
        return buf.getvalue()
    if fmt == "PNG":
        im.save(buf, "PNG")
        return buf.getvalue()
    ex = Image.Exif()
    if exif_date:
        sub = ex.get_ifd(0x8769)
        sub[0x9003] = exif_date
        if offset:
            sub[0x9011] = offset
    ex[0x010F], ex[0x0110] = "Test", "Test Camera"
    if gps:
        g = ex.get_ifd(0x8825)
        g[1], g[2], g[3], g[4] = ("N" if gps[0] >= 0 else "S"), (abs(gps[0]), 0.0, 0.0), ("E" if gps[1] >= 0 else "W"), (abs(gps[1]), 0.0, 0.0)
    im.save(buf, "JPEG", exif=ex)
    return buf.getvalue()


FILES = {
    "2024/with-offset.jpg": lambda: make((200, 60, 60), "2024:08:24 11:46:33", "+03:00", gps=(48.8566, 2.3522)),
    "2024/no-offset.jpg": lambda: make((60, 200, 60), "2018:07:29 00:33:09"),
    "2024/midnight.jpg": lambda: make((60, 60, 200), "2024:01:01 00:00:05", "+02:00"),
    "2025/Γιορτή/φωτογραφία.jpg": lambda: make((200, 200, 60), "2025:06:17 18:30:33", "+03:00", gps=(37.9838, 23.7275)),
    "2025/IMG_20250716_101500.jpg": lambda: make((120, 120, 120)),          # no EXIF: date from the file name
    "2025/screenshot.png": lambda: make((30, 30, 30), fmt="PNG", size=(540, 1200)),
    "2025/phone.heic": lambda: make((200, 120, 40), fmt="HEIC"),
}


def main() -> None:
    cfg = Config.load()
    user, pw = read_credential("BookahPhotos-nextcloud")
    dav = Dav(cfg.base_url, cfg.user, pw)
    cmd = sys.argv[1]
    if cmd == "add":
        for p in ("", "/2024", "/2025", "/2025/Γιορτή"):
            dav.mkcol(ROOT + p)
        for name, fn in FILES.items():
            dav.put(f"{ROOT}/{name}", fn())
        print(f"uploaded {len(FILES)} generated files")
    elif cmd == "change":
        dav.put(f"{ROOT}/2024/no-offset.jpg", make((10, 10, 10), "2018:07:29 00:33:09"))
        print("changed one file")
    elif cmd == "add-one":
        dav.put(f"{ROOT}/2025/new-file.jpg", make((90, 20, 160), "2025:09:09 09:09:09", "+03:00"))
        print("added one file")
    elif cmd == "remove":
        dav.delete(f"{ROOT}/2024/midnight.jpg")
        print("removed one file")
    elif cmd == "remove-many":
        for n in ("2024/no-offset.jpg", "2025/phone.heic", "2025/screenshot.png", "2025/new-file.jpg"):
            dav.delete(f"{ROOT}/{n}")
        print("removed four files (simulated mass deletion)")
    elif cmd == "cleanup":
        dav.delete(ROOT + "/2024"), dav.delete(ROOT + "/2025")
        print("removed the generated folders")


if __name__ == "__main__":
    main()
