"""Text side for the Immich-exported CLIP models (fixed input 'text' int32 [1,77]). Usage: text_search_clip.py <name> <textual dir>"""
import sys, os, json, time
import numpy as np
from tokenizers import Tokenizer
import onnxruntime as ort

name, tdir = sys.argv[1], sys.argv[2]
BASE = r"C:\BookahPC\bookahphotos\spike"
V = np.load(f"{BASE}\\out\\{name}_vecs.npy").astype(np.float32); names = json.load(open(f"{BASE}\\out\\{name}_names.json"))
base = json.load(open(BASE + r"\out\siglip2_top10.json", encoding="utf-8")); qs_all = base["queries"]
tok = Tokenizer.from_file(tdir + r"\tokenizer.json")
pad_id = tok.token_to_id("<pad>"); print("pad id", pad_id, "sample", tok.encode("Σκύλος").tokens, tok.encode("dog").tokens)
tok.enable_truncation(77); tok.enable_padding(length=77, pad_id=pad_id, pad_token="<pad>")
t0 = time.time(); s = ort.InferenceSession(tdir + r"\model.onnx", providers=["CPUExecutionProvider"]); load_s = time.time() - t0
T = []; times = []
for q in qs_all:
    enc = tok.encode(q).ids
    if enc[0] == tok.token_to_id("eng_Latn") and any("Ͱ" <= c <= "Ͽ" or "ἀ" <= c <= "῿" for c in q):
        enc[0] = tok.token_to_id("ell_Grek")  # NLLB language tag for Greek queries
    ids = np.array([enc], dtype=np.int32); t = time.time(); T.append(s.run(None, {"text": ids})[0][0]); times.append(time.time() - t)
T = np.array(T, np.float32); T /= np.linalg.norm(T, axis=1, keepdims=True)
print(f"{name} text: load {load_s:.1f}s, median query {np.median(times)*1000:.0f} ms (CPU, fp32), dim {T.shape[1]}, file {os.path.getsize(tdir + chr(92) + 'model.onnx')/1e6:.0f} MB")
S = T @ V.T
res = {q: [names[j] for j in np.argsort(-S[i])[:10]] for i, q in enumerate(qs_all)}
json.dump({"queries": qs_all, "n_original": base["n_original"], "results": {"fp32": res}}, open(f"{BASE}\\out\\{name}_top10.json", "w", encoding="utf-8"), ensure_ascii=False)
print("saved")
