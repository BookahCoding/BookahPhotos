"""Nextcloud WebDAV client. Reads anywhere in the user's files; WRITES ONLY under the BookahPhotos/ folder.

Every failure of a listing raises ListingError: a timeout or error must never look like "these files disappeared".
"""
from __future__ import annotations

import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from urllib.parse import quote, unquote, urlparse

import requests

NS = {"d": "DAV:", "oc": "http://owncloud.org/ns", "nc": "http://nextcloud.org/ns"}
WRITE_PREFIX = "BookahPhotos/"

_PROPFIND_BODY = (
    '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop>'
    "<oc:fileid/><d:getetag/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><d:resourcetype/><nc:has-preview/>"
    "</d:prop></d:propfind>"
)


class DavError(Exception):
    pass


class ListingError(DavError):
    """A folder could not be listed. The run must stop; nothing may be treated as deleted."""


class WriteRefused(DavError):
    """An attempt to write outside BookahPhotos/."""


@dataclass(frozen=True)
class Entry:
    path: str          # path relative to the user's root, no leading or trailing slash
    is_dir: bool
    etag: str
    file_id: int
    size: int
    mime: str
    modified: str


class Dav:
    def __init__(self, base_url: str, user: str, password: str, timeout: float = 60.0, min_interval: float = 0.05,
                 session: requests.Session | None = None, retries: int = 3, backoff: float = 2.0):
        self.base = base_url.rstrip("/")
        self.user = user
        self.timeout, self.min_interval, self.retries, self.backoff = timeout, min_interval, retries, backoff
        self.s = session or requests.Session()
        self.s.auth = (user, password)
        self.s.headers["OCS-APIRequest"] = "true"
        self._last = 0.0
        self._lock = threading.Lock()
        self.root_url = f"{self.base}/remote.php/dav/files/{quote(user)}"

    # ---- plumbing -------------------------------------------------------------------------
    def _url(self, path: str) -> str:
        return self.root_url + "/" + "/".join(quote(p) for p in path.strip("/").split("/") if p) if path.strip("/") else self.root_url

    def _pace(self) -> None:
        with self._lock:                      # preview workers share one pacing clock
            wait = self.min_interval - (time.monotonic() - self._last)
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()

    def _request(self, method: str, url: str, ok: tuple[int, ...], **kw) -> requests.Response:
        last: Exception | None = None
        for attempt in range(self.retries):
            self._pace()
            try:
                r = self.s.request(method, url, timeout=self.timeout, **kw)
                if r.status_code in ok:
                    return r
                if r.status_code in (429, 500, 502, 503, 504):
                    last = DavError(f"HTTP {r.status_code}")
                else:
                    raise DavError(f"{method} HTTP {r.status_code}")
            except requests.RequestException as e:
                last = e
            time.sleep(self.backoff * (attempt + 1))
        raise DavError(f"{method} failed after {self.retries} tries: {type(last).__name__}") from last

    def _check_write(self, path: str) -> None:
        p = path.strip("/")
        if not (p + "/").startswith(WRITE_PREFIX) or ".." in p.split("/"):
            raise WriteRefused("writes are only allowed under BookahPhotos/")

    # ---- reading --------------------------------------------------------------------------
    def list_folder(self, path: str) -> tuple[Entry, list[Entry]]:
        """PROPFIND Depth:1. Returns (the folder itself, its children). Raises ListingError on any problem."""
        try:
            r = self._request("PROPFIND", self._url(path), (207,), headers={"Depth": "1", "Content-Type": "application/xml"}, data=_PROPFIND_BODY)
            entries = parse_multistatus(r.content, self.root_url)
        except (DavError, ET.ParseError) as e:
            raise ListingError(f"could not list a folder: {type(e).__name__}") from e
        if not entries:
            raise ListingError("empty listing")
        me, kids = entries[0], entries[1:]
        if not me.is_dir:
            raise ListingError("path is not a folder")
        return me, kids

    def get(self, path: str, byte_range: tuple[int, int] | None = None) -> bytes:
        headers = {"Range": f"bytes={byte_range[0]}-{byte_range[1]}"} if byte_range else {}
        return self._request("GET", self._url(path), (200, 206), headers=headers).content

    def preview(self, file_id: int, size: int = 256) -> bytes:
        url = f"{self.base}/core/preview?fileId={int(file_id)}&x={size}&y={size}&a=true&mode=cover"
        return self._request("GET", url, (200,)).content

    def preview_fit(self, file_id: int, size: int = 1024) -> bytes:
        """The whole picture fitted inside size x size (not cropped to a square): what the content models read."""
        url = f"{self.base}/core/preview?fileId={int(file_id)}&x={size}&y={size}&a=true"
        return self._request("GET", url, (200,)).content

    def memories_info(self, file_id: int) -> dict | None:
        """The Memories app's EXIF summary for one file, or None when Memories has not indexed it."""
        url = f"{self.base}/index.php/apps/memories/api/image/info/{int(file_id)}"
        self._pace()
        try:
            r = self.s.get(url, timeout=self.timeout)
        except requests.RequestException:
            raise DavError("memories info request failed")
        if r.status_code in (404, 400):
            return None
        if r.status_code != 200:
            raise DavError(f"memories info HTTP {r.status_code}")
        try:
            return r.json()
        except ValueError:
            return None

    # ---- writing (BookahPhotos/ only) ------------------------------------------------------
    def mkcol(self, path: str) -> None:
        self._check_write(path)
        self._request("MKCOL", self._url(path), (201, 405))

    def put(self, path: str, data: bytes) -> None:
        self._check_write(path)
        self._request("PUT", self._url(path), (200, 201, 204), data=data)

    def move(self, src: str, dst: str) -> None:
        self._check_write(src)
        self._check_write(dst)
        self._request("MOVE", self._url(src), (201, 204), headers={"Destination": self._url(dst), "Overwrite": "T"})

    def delete(self, path: str) -> None:
        self._check_write(path)
        self._request("DELETE", self._url(path), (200, 204, 404))

    def size_of(self, path: str) -> int | None:
        r = self._request("PROPFIND", self._url(path), (207, 404), headers={"Depth": "0", "Content-Type": "application/xml"}, data=_PROPFIND_BODY)
        if r.status_code == 404:
            return None
        entries = parse_multistatus(r.content, self.root_url)
        return entries[0].size if entries else None


def parse_multistatus(xml_bytes: bytes, root_url: str) -> list[Entry]:
    root_path = urlparse(root_url).path.rstrip("/")
    out: list[Entry] = []
    for resp in ET.fromstring(xml_bytes).findall("d:response", NS):
        href = unquote(resp.findtext("d:href", default="", namespaces=NS))
        rel = href[len(root_path):] if href.startswith(root_path) else href
        rel = rel.strip("/")
        ok = next((ps for ps in resp.findall("d:propstat", NS) if "200" in (ps.findtext("d:status", default="", namespaces=NS))), None)
        if ok is None:
            continue
        p = ok.find("d:prop", NS)
        is_dir = p.find("d:resourcetype/d:collection", NS) is not None
        fid = p.findtext("oc:fileid", default="0", namespaces=NS)
        out.append(Entry(path=rel, is_dir=is_dir, etag=(p.findtext("d:getetag", default="", namespaces=NS) or "").strip('"'),
                         file_id=int(fid or 0), size=int(p.findtext("d:getcontentlength", default="0", namespaces=NS) or 0),
                         mime=p.findtext("d:getcontenttype", default="", namespaces=NS) or "", modified=p.findtext("d:getlastmodified", default="", namespaces=NS) or ""))
    return out
