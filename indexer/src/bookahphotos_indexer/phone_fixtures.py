"""Test texts and the expected token ids for the phone tokenizer. The expected ids come from the Hugging Face `tokenizers` library (the PC's tokenizer, mapped by
old2new), NOT from our own port, so they are the truth the Kotlin port must reproduce. Texts are generic words and fuzz; no personal data."""
from __future__ import annotations

import json
import random
from pathlib import Path

import numpy as np

from .phone_tokenizer import PAD, SEQ

SPIKE = Path(r"C:\BookahPC\bookahphotos\spike")
TOKENIZER_JSON = SPIKE / r"models\XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k\textual\tokenizer.json"
OLD2NEW = SPIKE / r"shrunk\old2new.npy"

GREEK = "αβγδεζηθικλμνξοπρστυφχψωςΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩάέήίόύώΐΰϊϋΆΈΉΊΌΎΏΪΫ"
LATIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZéèêëüöäßñçøåÆŒÀÉÖ"
COMBINING = [chr(c) for c in range(0x300, 0x370)]
SPACES = [" ", " ", "  ", "\t", "\n", "\u00a0", "\u2003", "\u3000", "\u200b", "\u00ad", "\r", "\x0b", "\ufeff", "\x1c", "\x85"]
ODD = ["ﬁ", "①", "²", "ｱ", "Ａ", "㎏", "…", "—", "’", "“", "🙂", "👨‍👩‍👧", "\u200d", "\ufe0f", "한", "中", "ж", "ا", "ǅ", "ẞ", "ς", "µ", "▁", "\ue000", "\u0378", "\U0010fffe", "ᚠ", "𐍈"]
PUNCT = list("0123456789.,;:!?-_/()[]'\"")

EDGE = ["", " ", "   ", "a", "dog", "beach", "dog on the beach", "  leading and trailing  ", "a  b", "tab\there", "Σκύλος", "σκύλος", "ΣΚΥΛΟΣ", "παραλία", "ταξίδι",
        "σκαναρισμένα", "Ελλάδα το καλοκαίρι 2019", "ο\u0301", "ι\u0308\u0301", "α\u0301\u0301", "e\u0301", "A\u030a", "ς", "Σ", "ΐ", "ϊ\u0301", "naïve café", "München",
        "🙂🙂🙂", "\ue000\ue001\ue002", "a\ue000\ue000b", "1234567890", "Ταξίδι στη Ζάκυνθος το 2024 με φίλους και οικογένεια στην παραλία το ηλιοβασίλεμα",
        "word " * 100, "α" * 400, " ".join(["παραλία"] * 60)]


def fuzz_texts(n: int, seed: int = 1) -> list[str]:
    rng = random.Random(seed)
    pools = [(list(GREEK), 7), (list(LATIN), 5), (COMBINING, 3), (SPACES, 3), (ODD, 3), (PUNCT, 2)]
    out = []
    for _ in range(n):
        parts = []
        for _ in range(rng.randint(1, 9)):
            pool = rng.choices([p for p, _ in pools], weights=[w for _, w in pools])[0]
            parts.append("".join(rng.choice(pool) for _ in range(rng.randint(1, 9))))
        out.append(rng.choice(["", " ", "  "]).join(parts))
    return out


def expected_ids(texts: list[str], tokenizer_json: Path = TOKENIZER_JSON, old2new: Path = OLD2NEW) -> list[list[int]]:
    """The truth: tokenizers (truncation 77, padding with <pad>) then old2new."""
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file(str(tokenizer_json))
    tok.enable_truncation(SEQ)
    tok.enable_padding(length=SEQ, pad_id=PAD, pad_token="<pad>")
    o2n = np.load(old2new)
    return [[int(o2n[i]) for i in tok.encode(t).ids] for t in texts]


ENCODER_PHRASES = ["dog", "beach", "snow", "a photo of a cat", "birthday cake", "sunset over the sea", "σκύλος", "παραλία", "χιόνι", "ηλιοβασίλεμα στη θάλασσα",
                   "παιδιά στην παιδική χαρά", "ταξίδι", "mountain", "ορεινό χωριό το χειμώνα", "food", "φαγητό"]


def write_encoder_expected(path: Path, tokenizer_asset: Path, small_model: Path = SPIKE / r"shrunk\text_small.onnx", phrases: list[str] | None = None) -> int:
    """For the on-device test: the unit-length vector that text_small.onnx (ONNX Runtime on the PC, CPU) gives for each phrase, using OUR tokenizer's ids
    (which the tests prove equal to the PC tokenizer's). The phone must reproduce these (the same model run by another build of ONNX Runtime)."""
    import onnxruntime as ort
    from .phone_tokenizer import load_asset
    tk = load_asset(tokenizer_asset)
    sess = ort.InferenceSession(str(small_model), providers=["CPUExecutionProvider"])
    out = []
    for text in phrases or ENCODER_PHRASES:
        ids = np.array([tk.encode(text)], dtype=np.int32)
        v = sess.run(None, {"text": ids})[0][0].astype(np.float32)
        v = v / np.linalg.norm(v)
        out.append({"text": text, "ids": [int(i) for i in ids[0]], "vec": [round(float(x), 6) for x in v]})
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps({"dim": int(len(out[0]["vec"])), "cases": out}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return len(out)


def write_fixtures(path: Path, n_fuzz: int = 3000, seed: int = 1, extra_texts: list[str] | None = None, compact: bool = True) -> int:
    """JSON: {"seq": 77, "cases": [{"text": ..., "ids": [unpadded ids]}]}. Ids are stored without the trailing <pad>s to keep the file small."""
    texts = list(EDGE) + (extra_texts or []) + fuzz_texts(n_fuzz, seed)
    ids = expected_ids(texts)
    cases = []
    for t, row in zip(texts, ids):
        k = len(row)
        while k > 0 and row[k - 1] == PAD:
            k -= 1
        cases.append({"text": t, "ids": row[:k]})
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps({"seq": SEQ, "pad": PAD, "cases": cases}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return len(cases)
