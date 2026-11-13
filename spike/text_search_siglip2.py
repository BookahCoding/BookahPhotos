"""Text side of SigLIP 2: encode questions (int8 and fp16 text models), rank the sample, time it, save top-10 per question."""
import sys, os, json, time
import numpy as np
from tokenizers import Tokenizer
import onnxruntime as ort

BASE = r"C:\BookahPC\bookahphotos\spike"
MD = BASE + r"\models\siglip2-base-patch16-224-ONNX"
V = np.load(BASE + r"\out\siglip2_vecs.npy").astype(np.float32)
names = json.load(open(BASE + r"\out\siglip2_names.json"))
tok = Tokenizer.from_file(MD + r"\tokenizer.json")
tok.no_padding(); tok.no_truncation()
qs = [l.strip() for l in open(r"D:\Projects\BookahPhotos\sample\questions.txt", encoding="utf-8") if l.strip()]
extra = ["dog on a boat", "σκύλος σε βάρκα", "παραλία το ηλιοβασίλεμα", "children playing", "παιδιά που παίζουν", "a birthday cake", "τούρτα γενεθλίων", "snow", "χιόνι"]
qs_all = qs + extra

def ids_for(q, maxlen=64):
    ids = tok.encode(q.lower()).ids[: maxlen]
    return ids + [0] * (maxlen - len(ids))  # pad id 0 for the Gemma tokenizer; checked below

print("tokenizer sample:", tok.encode("σκύλος").tokens, tok.encode("dog").ids)
results = {}
for label, fn in [("int8", "text_model_int8.onnx")]:  # fp16 file fails to load on ORT CPU (graph fusion error), skipped
    t0 = time.time()
    s = ort.InferenceSession(MD + r"\onnx\\" + fn, providers=["CPUExecutionProvider"])
    load_s = time.time() - t0
    inp = [i.name for i in s.get_inputs()]
    if label == "int8": print("text inputs:", inp, "outputs:", [o.name for o in s.get_outputs()])
    x = np.array([ids_for(q) for q in qs_all], dtype=np.int64)
    feed = {inp[0]: x}
    s.run(None, feed)  # warm-up
    t0 = time.time(); out = s.run(None, {inp[0]: x[:1]}); one = time.time() - t0
    out = s.run(None, feed)
    T = out[1] if len(out) > 1 else out[0]
    T = T.astype(np.float32); T /= np.linalg.norm(T, axis=1, keepdims=True)
    print(f"{label}: load {load_s:.2f}s, single query {one*1000:.0f} ms (CPU), dim {T.shape[1]}")
    S = T @ V.T
    results[label] = {q: [names[j] for j in np.argsort(-S[i])[:10]] for i, q in enumerate(qs_all)}
    # time the vector comparison at 50,000 photos
    big = np.random.randn(50000, V.shape[1]).astype(np.float16)
    t0 = time.time(); _ = (big.astype(np.float32) @ T[0]); print(f"  50,000-vector comparison on this PC: {(time.time()-t0)*1000:.0f} ms")
json.dump({"queries": qs_all, "n_original": len(qs), "results": results}, open(BASE + r"\out\siglip2_top10.json", "w"), ensure_ascii=False)
print("saved top-10 lists")
