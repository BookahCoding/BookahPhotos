"""Shrink the XLM-R CLIP text model for the phone: (1) keep only Greek/Latin/digit/punctuation vocabulary rows,
(2) store the embedding table as per-row int8, (3) dynamic int8 for the MatMul weights. Then compare with fp32."""
import json, os, sys, time, unicodedata
import numpy as np, onnx
from onnx import numpy_helper, helper, TensorProto
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnxruntime as ort
from tokenizers import Tokenizer

D = r"C:\BookahPC\bookahphotos\spike"
SRC = D + r"\models\XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k\textual"
OUT = D + r"\shrunk"; os.makedirs(OUT, exist_ok=True)
tok = Tokenizer.from_file(SRC + r"\tokenizer.json")
vocab = tok.get_vocab(); n_vocab = 250002

def ok(piece):
    s = piece.replace("\u2581", "")
    if not s: return True
    for c in s:
        if c.isdigit() or unicodedata.category(c).startswith(("P", "S")): continue
        name = unicodedata.name(c, "")
        if name.startswith("GREEK") or (name.startswith("LATIN") and (c.isascii() or os.environ.get("KEEP_ALL_LATIN"))): continue
        return False
    return True

keep = sorted(i for p, i in vocab.items() if i < 4 or ok(p))
old2new = {o: n for n, o in enumerate(keep)}
print("kept vocab rows:", len(keep), "of", n_vocab)
np.save(OUT + r"\old2new.npy", np.array([old2new.get(i, old2new[3]) for i in range(n_vocab)], dtype=np.int32))  # unknown tokens -> <unk> (id 3)

m = onnx.load(SRC + r"\model.onnx")
emb_name = "text.transformer.embeddings.word_embeddings.weight"
W = next(numpy_helper.to_array(i) for i in m.graph.initializer if i.name == emb_name)
Wk = W[keep]
scale = np.maximum(np.abs(Wk).max(axis=1, keepdims=True), 1e-8) / 127.0
Wq = np.round(Wk / scale).astype(np.int8)
gather = next(n for n in m.graph.node if n.op_type == "Gather" and emb_name in n.input)
ids_in, out_name = gather.input[1], gather.output[0]
m.graph.initializer.remove(next(i for i in m.graph.initializer if i.name == emb_name))
m.graph.initializer.extend([numpy_helper.from_array(Wq, "emb_q"), numpy_helper.from_array(scale.astype(np.float32), "emb_s")])
idx = list(m.graph.node).index(gather); m.graph.node.remove(gather)
new = [helper.make_node("Gather", ["emb_q", ids_in], ["emb_rows_q"]),
       helper.make_node("Cast", ["emb_rows_q"], ["emb_rows_f"], to=TensorProto.FLOAT),
       helper.make_node("Gather", ["emb_s", ids_in], ["emb_rows_s"]),
       helper.make_node("Mul", ["emb_rows_f", "emb_rows_s"], [out_name])]
for k, n in enumerate(new): m.graph.node.insert(idx + k, n)
onnx.save(m, OUT + r"\step1_vocab_int8emb.onnx")
quantize_dynamic(OUT + r"\step1_vocab_int8emb.onnx", OUT + r"\text_small.onnx", weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
print("sizes MB: step1", round(os.path.getsize(OUT + r"\step1_vocab_int8emb.onnx") / 1e6), "final", round(os.path.getsize(OUT + r"\text_small.onnx") / 1e6))

# compare with fp32 on all 38 queries
top = json.load(open(D + r"\out\xlmr_top10.json", encoding="utf-8")); qs = top["queries"]
V = np.load(D + r"\out\xlmr_vecs.npy").astype(np.float32); names = json.load(open(D + r"\out\xlmr_names.json"))
tok.enable_truncation(77); tok.enable_padding(length=77, pad_id=1, pad_token="<pad>")
mp = np.load(OUT + r"\old2new.npy")
s32 = ort.InferenceSession(SRC + r"\model.onnx", providers=["CPUExecutionProvider"]); t0 = time.time()
ss = ort.InferenceSession(OUT + r"\text_small.onnx", providers=["CPUExecutionProvider"]); print("small load", round(time.time() - t0, 2), "s")
cos = []; ov = []; times = []
for q in qs:
    ids = np.array([tok.encode(q).ids], dtype=np.int32)
    a = s32.run(None, {"text": ids})[0][0]; t = time.time(); b = ss.run(None, {"text": mp[ids]})[0][0]; times.append(time.time() - t)
    a /= np.linalg.norm(a); b /= np.linalg.norm(b); cos.append(float(a @ b))
    ta = set(np.argsort(-(V @ a))[:10]); tb = set(np.argsort(-(V @ b))[:10]); ov.append(len(ta & tb))
print(f"cosine small vs fp32: mean {np.mean(cos):.4f} min {np.min(cos):.4f}; top-10 overlap mean {np.mean(ov):.1f}/10 min {np.min(ov)}; median query {np.median(times)*1000:.0f} ms")
