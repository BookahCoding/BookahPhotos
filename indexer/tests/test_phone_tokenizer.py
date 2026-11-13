"""The phone tokenizer (reference implementation) must give EXACTLY the ids of the PC's tokenizer. Needs the Phase 0 model files; skipped when they are missing."""
import numpy as np
import pytest

from bookahphotos_indexer import phone_fixtures as fx
from bookahphotos_indexer import phone_tokenizer as pt

pytestmark = pytest.mark.skipif(not (fx.TOKENIZER_JSON.exists() and fx.OLD2NEW.exists()), reason="Phase 0 tokenizer files are not on this machine")


@pytest.fixture(scope="module")
def tk(tmp_path_factory):
    out = tmp_path_factory.mktemp("asset") / "text_tokenizer.bin"
    info = pt.build_asset(fx.TOKENIZER_JSON, fx.OLD2NEW, out)
    assert info["pieces"] == 250002
    return pt.load_asset(out)


def check(tk, texts):
    truth = fx.expected_ids(texts)
    bad = [(t, a, b) for t, a, b in zip(texts, truth, (tk.encode(t) for t in texts)) if a != b]
    assert not bad, f"{len(bad)} of {len(texts)} differ, first: {bad[0][0]!r} truth {bad[0][1][:20]} ours {bad[0][2][:20]}"


def test_edge_cases(tk):
    check(tk, fx.EDGE)


def test_fuzzed_greek_latin_combining_spaces_and_odd_characters(tk):
    check(tk, fx.fuzz_texts(12000, seed=5))


def test_vocabulary_words(tk):
    import json
    vocab = json.loads(fx.TOKENIZER_JSON.read_text(encoding="utf-8"))["model"]["vocab"]
    words = [p.replace(pt.SPACE, "") for p, _ in vocab if p.startswith(pt.SPACE) and len(p) > 3]
    rng = np.random.default_rng(3)
    rng.shuffle(words)
    check(tk, words[:6000] + [a + " " + b for a, b in zip(words[6000:9000], words[9000:12000])])


def test_output_shape_padding_and_truncation(tk):
    ids = tk.encode("")
    assert len(ids) == pt.SEQ and ids[:2] == [pt.BOS, pt.EOS] and set(ids[2:]) == {pt.PAD}
    long = tk.encode("παραλία " * 200)
    assert len(long) == pt.SEQ and long[-1] == pt.EOS and long[0] == pt.BOS             # cut, but still closed with </s>
    assert tk.encode("dog")[:3][0] == pt.BOS


def test_unknown_characters_fuse_into_one_unk(tk):
    raw = tk.encode_raw("a\U0010fffe͸￿b")                 # three characters that are not in the vocabulary
    assert raw.count(pt.UNK) == 1
    check(tk, ["a\U0010fffe͸￿b", "\U0010fffe" * 7, "x ͸͹ y"])


def test_a_changed_asset_is_not_loaded(tmp_path):
    p = tmp_path / "x.bin"
    p.write_bytes(b"NOPE" + b"\0" * 20)
    with pytest.raises(AssertionError):
        pt.load_asset(p)
