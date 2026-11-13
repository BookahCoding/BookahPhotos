"""A fake Nextcloud for tests: an in-memory tree with Nextcloud-like folder etags (a folder's etag changes when anything below it changes)."""
from __future__ import annotations

import hashlib
from dataclasses import dataclass

from bookahphotos_indexer.dav import Entry, ListingError


@dataclass
class F:
    file_id: int
    etag: str
    mime: str = "image/jpeg"
    size: int = 1000


class FakeDav:
    def __init__(self):
        self.files: dict[str, F] = {}        # path -> file
        self.dirs: set[str] = set()
        self.list_calls: list[str] = []
        self.fail_on: set[str] = set()       # folder paths whose listing raises ListingError
        self._next_id = 100

    def add(self, path: str, etag: str | None = None, mime: str = "image/jpeg", file_id: int | None = None) -> int:
        fid = file_id or self._next_id
        self._next_id = max(self._next_id, fid) + 1
        self.files[path] = F(fid, etag or f"e{fid}", mime)
        parts = path.split("/")
        for i in range(1, len(parts)):
            self.dirs.add("/".join(parts[:i]))
        return fid

    def remove(self, path: str) -> None:
        del self.files[path]

    def touch(self, path: str, etag: str) -> None:
        self.files[path].etag = etag

    def rename(self, old: str, new: str) -> None:
        f = self.files.pop(old)
        self.files[new] = f
        parts = new.split("/")
        for i in range(1, len(parts)):
            self.dirs.add("/".join(parts[:i]))

    def _folder_etag(self, path: str) -> str:
        h = hashlib.md5()
        for p in sorted(self.files):
            if p.startswith(path + "/"):
                h.update(f"{p}|{self.files[p].etag}|{self.files[p].file_id}".encode())
        return h.hexdigest()

    def list_folder(self, path: str):
        self.list_calls.append(path)
        if path in self.fail_on:
            raise ListingError("injected failure")
        me = Entry(path, True, self._folder_etag(path), 1, 0, "", "")
        kids: list[Entry] = []
        for d in sorted(self.dirs):
            if d.startswith(path + "/") and "/" not in d[len(path) + 1:]:
                kids.append(Entry(d, True, self._folder_etag(d), 2, 0, "", ""))
        for p, f in sorted(self.files.items()):
            if p.startswith(path + "/") and "/" not in p[len(path) + 1:]:
                kids.append(Entry(p, False, f.etag, f.file_id, f.size, f.mime, ""))
        return me, kids
