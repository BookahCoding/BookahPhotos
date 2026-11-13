"""Content vectors: the search model (XLM-RoBERTa ViT-B/32, laion5b) turns a picture or a sentence into 512 numbers. Needs the `content` extra
(onnxruntime-directml, numpy, tokenizers, pillow). Models live in the private state folder and are loaded only while the indexer works."""
from __future__ import annotations

import io
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MODEL_DIR = Path(r"C:\BookahPC\bookahphotos\spike\models\XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k")
MODEL_NAME = "xlmr-vit-b32-laion5b"
DIM = 512
SIZE = 224
_MEAN = np.array((0.48145466, 0.4578275, 0.40821073), np.float32).reshape(3, 1, 1)
_STD = np.array((0.26862954, 0.26130258, 0.27577711), np.float32).reshape(3, 1, 1)


def preprocess(im, mode: str = "crop") -> np.ndarray:
    """A PIL image -> float32 [3,224,224]. 'crop' = shorter side to 224 then centre crop; 'squash' = whole picture squeezed to a square."""
    from PIL import Image, ImageOps
    im = ImageOps.exif_transpose(im).convert("RGB")
    if mode == "squash":
        im = im.resize((SIZE, SIZE), Image.BICUBIC)
    else:
        w, h = im.size
        s = SIZE / min(w, h)
        im = im.resize((max(SIZE, round(w * s)), max(SIZE, round(h * s))), Image.BICUBIC)
        w, h = im.size
        left, top = (w - SIZE) // 2, (h - SIZE) // 2
        im = im.crop((left, top, left + SIZE, top + SIZE))
    return ((np.asarray(im, np.float32) / 255.0).transpose(2, 0, 1) - _MEAN) / _STD


def decode(data: bytes, mode: str = "crop") -> np.ndarray | None:
    """Bytes of a JPEG/PNG/HEIC -> model input, or None when the file cannot be decoded."""
    try:
        from PIL import Image
        try:
            import pillow_heif
            pillow_heif.register_heif_opener()
        except Exception:
            pass
        im = Image.open(io.BytesIO(data))
        im.draft("RGB", (SIZE * 2, SIZE * 2))
        return preprocess(im, mode)
    except Exception:
        return None


def embedded_jpeg(data: bytes, max_candidates: int = 8):
    """Camera RAW files (ORF, CR2, NEF, ARW, DNG...) carry a full-size JPEG inside. Returns the largest one that opens as a PIL image, or None."""
    from PIL import Image
    best, best_px, pos, tried = None, 0, 0, 0
    while tried < max_candidates:
        pos = data.find(b"\xff\xd8\xff", pos)
        if pos < 0:
            break
        tried += 1
        try:
            im = Image.open(io.BytesIO(data[pos:]))
            im.load()
            if im.size[0] * im.size[1] > best_px:
                best, best_px = im, im.size[0] * im.size[1]
        except Exception:
            pass
        pos += 3
    return best


def normalise(v: np.ndarray) -> np.ndarray:
    return v / np.maximum(np.linalg.norm(v, axis=-1, keepdims=True), 1e-12)


def _session(path: Path, adapter: int | None):
    import onnxruntime as ort
    providers = [("DmlExecutionProvider", {"device_id": adapter})] if adapter is not None else ["CPUExecutionProvider"]
    return ort.InferenceSession(str(path), providers=providers)


class ImageEncoder:
    def __init__(self, adapter: int | None, model_dir: Path = MODEL_DIR, mode: str = "crop") -> None:
        self.mode = mode
        self._sess = _session(model_dir / "visual" / "model.onnx", adapter)
        inp = self._sess.get_inputs()[0]
        self._in = inp.name
        self._fixed = inp.shape[0] if isinstance(inp.shape[0], int) else None   # Immich-exported models take one picture at a time

    def encode(self, batch: list[np.ndarray], batch_size: int = 16) -> np.ndarray:
        """[N,3,224,224] arrays -> normalised float32 [N,512]."""
        batch_size = self._fixed or batch_size
        out = []
        for i in range(0, len(batch), batch_size):
            r = self._sess.run(None, {self._in: np.stack(batch[i:i + batch_size])})
            out.append(r[-1] if len(r) > 1 and r[-1].ndim == 2 else r[0])
        return normalise(np.concatenate(out)) if out else np.zeros((0, DIM), np.float32)

    def close(self) -> None:
        self._sess = None


class TextEncoder:
    def __init__(self, adapter: int | None = None, model_dir: Path = MODEL_DIR) -> None:
        from tokenizers import Tokenizer
        self._tok = Tokenizer.from_file(str(model_dir / "textual" / "tokenizer.json"))
        self._tok.enable_truncation(77)
        pad = self._tok.token_to_id("<pad>")
        self._tok.enable_padding(length=77, pad_id=pad, pad_token="<pad>")
        self._sess = _session(model_dir / "textual" / "model.onnx", adapter)
        inp = self._sess.get_inputs()[0]
        self._name = inp.name
        self._dtype = np.int32 if "int32" in inp.type else np.int64
        self._fixed = inp.shape[0] if isinstance(inp.shape[0], int) else None

    def encode(self, texts: list[str]) -> np.ndarray:
        enc = self._tok.encode_batch(texts)
        ids = np.array([e.ids for e in enc], self._dtype)
        step = self._fixed or len(ids) or 1
        out = [self._sess.run(None, {self._name: ids[i:i + step]})[0] for i in range(0, len(ids), step)]
        return normalise(np.concatenate(out).astype(np.float32)) if out else np.zeros((0, DIM), np.float32)

    def close(self) -> None:
        self._sess = None


def to_f16(v: np.ndarray) -> bytes:
    return v.astype(np.float16).tobytes()


def from_f16(b: bytes) -> np.ndarray:
    return np.frombuffer(b, np.float16).astype(np.float32)
