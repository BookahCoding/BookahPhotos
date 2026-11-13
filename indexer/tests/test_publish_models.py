import json

import pytest

from bookahphotos_indexer import publish_models
from bookahphotos_indexer.dav import DavError
from bookahphotos_indexer.publish import PublishError


class MemDav:
    def __init__(self, corrupt=False):
        self.files, self.dirs, self.log, self.corrupt = {}, set(), [], corrupt

    def mkcol(self, p): self.dirs.add(p.strip("/")); self.log.append(("mkcol", p))
    def put(self, p, data): self.files[p] = data[:-1] if (self.corrupt and "manifest" not in p and p.endswith("onnx")) else data; self.log.append(("put", p))
    def move(self, a, b): self.files[b] = self.files.pop(a); self.log.append(("move", a, b))
    def size_of(self, p): return len(self.files[p]) if p in self.files else None


def make_files(tmp_path):
    a, b = tmp_path / "m.onnx", tmp_path / "t.bin"
    a.write_bytes(b"x" * 1000)
    b.write_bytes(b"y" * 50)
    return {"text_small.onnx": a, "text_tokenizer.bin": b}


def test_files_then_manifest_last_with_hashes_and_the_model_id(tmp_path):
    dav = MemDav()
    m = publish_models.publish_models(dav, make_files(tmp_path), "xlmr-v1")
    puts = [e[1] for e in dav.log if e[0] == "put"]
    assert puts[-1].endswith("upload-manifest.json") and dav.log[-1] == ("move", "BookahPhotos/models/upload-manifest.json", "BookahPhotos/models/manifest.json")
    saved = json.loads(dav.files["BookahPhotos/models/manifest.json"])
    assert saved["model_id"] == "xlmr-vit-b32-laion5b" == publish_models.MODEL_ID
    assert [f["name"] for f in saved["files"]] == ["text_small.onnx", "text_tokenizer.bin"] and saved["files"][0]["size"] == 1000
    assert len(saved["files"][0]["sha256"]) == 64 and saved["text_model"]["seq"] == 77 and saved["text_model"]["output_dim"] == 512
    assert dav.files["BookahPhotos/models/xlmr-v1/text_tokenizer.bin"] == b"y" * 50
    assert m == saved


def test_nothing_is_written_outside_the_models_folder(tmp_path):
    dav = MemDav()
    publish_models.publish_models(dav, make_files(tmp_path), "d")
    assert all(p.startswith("BookahPhotos") for _, *ps in dav.log for p in ps)                                   # (parent folders may be created)
    assert all(p.startswith("BookahPhotos/models") for op, *ps in dav.log if op != "mkcol" for p in ps)         # every file write is inside models/


def test_a_wrong_size_after_upload_stops_before_the_manifest(tmp_path):
    dav = MemDav(corrupt=True)
    with pytest.raises(PublishError):
        publish_models.publish_models(dav, make_files(tmp_path), "d")
    assert "BookahPhotos/models/manifest.json" not in dav.files


def test_the_dav_client_refuses_writes_outside_bookahphotos():
    from bookahphotos_indexer.dav import Dav, WriteRefused
    d = Dav("https://x.invalid", "u", "p")
    with pytest.raises(WriteRefused):
        d.put("Photos/models/evil", b"")
