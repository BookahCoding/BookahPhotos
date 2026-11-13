"""The search model's tokenizer for the PHONE: a reference implementation in Python (the executable specification that the Kotlin port in
android/core-search mirrors line by line), a builder for the small file the phone downloads, and the test fixtures.

It reproduces the Hugging Face `tokenizers` pipeline of XLM-R CLIP exactly (checked on about 100,000 fuzzed strings):
  1. Normalizer "Precompiled" (the sentencepiece charsmap): split into extended grapheme clusters; a cluster shorter than 6 bytes is looked up as a whole in the
     double-array trie and the FIRST (shortest) match is used; otherwise (no match, or a longer cluster) every code point is looked up on its own.
  2. WhitespaceSplit, then Metaspace (prepend the character U+2581 to every word).
  3. Unigram per word: a lattice of every vocabulary piece (up to 16 characters) plus one <unk> node (score min - 10) where no single character matches;
     Viterbi in the order nodes were made, a strictly better score wins (the first of equal ones stays); consecutive <unk> are fused into one.
  4. Template: <s> ... </s>, truncated to 77, padded with <pad> (id 1). Ids are then mapped by `old2new` (the small text model keeps only Greek and Latin rows).
File format (little endian) of text_tokenizer.bin: magic 'BKTK', u32 version(1), u32 charsmap length, charsmap bytes (sentencepiece precompiled charsmap, as in
tokenizer.json), u32 piece count, then per piece in id order: u8 byte length, UTF-8 bytes, f32 score, i32 mapped id (old2new already applied)."""
from __future__ import annotations

import base64
import hashlib
import json
import struct
from pathlib import Path

import numpy as np

MAGIC = b"BKTK"
VERSION = 1
SEQ = 77
PAD, BOS, EOS, UNK = 1, 0, 2, 3
SPACE = "▁"
UNK_PENALTY = 10.0

# Unicode White_Space, which is what WhitespaceSplit (Rust char::is_whitespace) splits on. Not Python's isspace(): that also counts U+001C to U+001F.
_WHITESPACE = frozenset("\t\n\x0b\x0c\r \x85\xa0                　")


def _is_ws(ch: str) -> bool:
    return ch in _WHITESPACE


class PhoneTokenizer:
    def __init__(self, charsmap: bytes, pieces: list[str], scores: list[float], mapped: list[int]):
        (trie_size,) = struct.unpack_from("<I", charsmap, 0)
        self.units = struct.unpack_from(f"<{trie_size // 4}I", charsmap, 4)
        self.normalized = charsmap[4 + trie_size:]
        self.vocab = {p: i for i, p in enumerate(pieces)}
        self.scores, self.mapped = scores, mapped
        self.max_len = max(len(p) for p in pieces)
        self.unk_score = min(scores) - UNK_PENALTY

    # ---- 1. normalizer ----
    @staticmethod
    def _offset(u: int) -> int:
        return (u >> 10) << ((u & (1 << 9)) >> 6)

    def _first_match(self, key: bytes) -> int | None:
        """The value of the FIRST (shortest) vocabulary-of-the-charsmap match among the prefixes of `key`, or None."""
        u = self.units
        node = 0
        node ^= self._offset(u[node])
        for c in key:
            if c == 0:
                break
            node ^= c
            unit = u[node]
            if (unit & ((1 << 31) | 0xFF)) != c:
                return None
            node ^= self._offset(unit)
            if (unit >> 8) & 1:
                return u[node] & ((1 << 31) - 1)
        return None

    def _entry(self, idx: int) -> str:
        return self.normalized[idx:self.normalized.index(b"\0", idx)].decode("utf-8")

    def _transform(self, chunk: str) -> str | None:
        v = self._first_match(chunk.encode("utf-8"))
        return None if v is None else self._entry(v)

    def normalize(self, text: str) -> str:
        import regex
        out = []
        for g in regex.findall(r"\X", text):
            if len(g.encode("utf-8")) < 6:
                t = self._transform(g)
                if t is not None:
                    out.append(t)
                    continue
            for ch in g:
                t = self._transform(ch)
                out.append(t if t is not None else ch)
        return "".join(out)

    # ---- 3. unigram ----
    def _viterbi(self, word: str) -> list[int]:
        n = len(word)
        node_begin, node_len, node_id, node_score = [-1], [0], [BOS], [0.0]          # node 0 = BOS
        begin_nodes: list[list[int]] = [[] for _ in range(n + 1)]
        end_nodes: list[list[int]] = [[] for _ in range(n + 1)]
        end_nodes[0].append(0)
        for b in range(n):
            has_single = False
            for L in range(1, min(self.max_len, n - b) + 1):
                pid = self.vocab.get(word[b:b + L])
                if pid is not None:
                    has_single = has_single or L == 1
                    k = len(node_id); node_begin.append(b); node_len.append(L); node_id.append(pid); node_score.append(self.scores[pid])
                    begin_nodes[b].append(k); end_nodes[b + L].append(k)
            if not has_single:
                k = len(node_id); node_begin.append(b); node_len.append(1); node_id.append(UNK); node_score.append(self.unk_score)
                begin_nodes[b].append(k); end_nodes[b + 1].append(k)
        eos = len(node_id); node_begin.append(n); node_len.append(0); node_id.append(-1); node_score.append(0.0)
        begin_nodes[n].append(eos)
        back: dict[int, int] = {}
        best_score: dict[int, float] = {0: 0.0}
        for pos in range(n + 1):
            for k in begin_nodes[pos]:
                best, bs = None, 0.0
                for e in end_nodes[pos]:
                    s = best_score[e] + node_score[k]
                    if best is None or s > bs:                       # strictly better wins: on a tie the first (earliest start) stays
                        best, bs = e, s
                best_score[k] = bs
                back[k] = best
        path, k = [], back[eos]
        while k != 0:
            path.append(k)
            k = back[k]
        path.reverse()
        ids, last_unk = [], False
        for k in path:
            is_unk = node_id[k] == UNK
            if not (is_unk and last_unk):                            # fuse consecutive <unk> into one
                ids.append(node_id[k])
            last_unk = is_unk
        return ids

    # ---- the whole pipeline ----
    def encode_raw(self, text: str) -> list[int]:
        """Piece ids (mapped by old2new) without <s>, </s>, truncation or padding."""
        words, cur = [], []
        for ch in self.normalize(text):
            if _is_ws(ch):
                if cur:
                    words.append("".join(cur)); cur = []
            else:
                cur.append(ch)
        if cur:
            words.append("".join(cur))
        ids: list[int] = []
        for w in words:
            ids += self._viterbi(SPACE + w)
        return [self.mapped[i] for i in ids]

    def encode(self, text: str) -> list[int]:
        """The model input: [<s>, pieces..., </s>] cut to 77 (the </s> is kept) and padded with <pad> to 77."""
        ids = [BOS] + self.encode_raw(text) + [EOS]
        if len(ids) > SEQ:
            ids = ids[:SEQ - 1] + [EOS]
        return ids + [PAD] * (SEQ - len(ids))


# ---- the phone file ----
def build_asset(tokenizer_json: Path, old2new_npy: Path, out: Path) -> dict:
    d = json.loads(Path(tokenizer_json).read_text(encoding="utf-8"))
    charsmap = base64.b64decode(d["normalizer"]["precompiled_charsmap"])
    vocab = d["model"]["vocab"]
    o2n = np.load(old2new_npy)
    assert len(o2n) == len(vocab), "old2new must cover every vocabulary id"
    parts = [MAGIC, struct.pack("<II", VERSION, len(charsmap)), charsmap, struct.pack("<I", len(vocab))]
    for i, (piece, score) in enumerate(vocab):
        b = piece.encode("utf-8")
        assert len(b) < 256
        parts += [struct.pack("<B", len(b)), b, struct.pack("<fi", float(score), int(o2n[i]))]
    data = b"".join(parts)
    Path(out).write_bytes(data)
    return {"file": Path(out).name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "pieces": len(vocab)}


def load_asset(path: Path) -> PhoneTokenizer:
    b = Path(path).read_bytes()
    assert b[:4] == MAGIC
    version, cm_len = struct.unpack_from("<II", b, 4)
    assert version == VERSION
    pos = 12
    charsmap = b[pos:pos + cm_len]
    pos += cm_len
    (n,) = struct.unpack_from("<I", b, pos)
    pos += 4
    pieces, scores, mapped = [], [], []
    for _ in range(n):
        ln = b[pos]
        pos += 1
        pieces.append(b[pos:pos + ln].decode("utf-8"))
        pos += ln
        s, m = struct.unpack_from("<fi", b, pos)
        pos += 8
        scores.append(s)
        mapped.append(m)
    return PhoneTokenizer(charsmap, pieces, scores, mapped)
