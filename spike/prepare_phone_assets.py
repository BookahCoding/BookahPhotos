"""Prepare assets for the phone spike app: the shrunk text model, pre-tokenised test queries (already mapped to the shrunk vocabulary),
the expected vectors from the PC, and the 1,537 real image vectors (no file names, no photos)."""
import json, os, shutil
import numpy as np, onnxruntime as ort
from tokenizers import Tokenizer

D = r"C:\BookahPC\bookahphotos\spike"; A = r"D:\Projects\BookahPhotos\android-spike\app\src\main\assets"; os.makedirs(A, exist_ok=True)
shutil.copy(D + r"\shrunk\text_small.onnx", A + r"\text_small.onnx")
tok = Tokenizer.from_file(D + r"\models\XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k\textual\tokenizer.json")
tok.enable_truncation(77); tok.enable_padding(length=77, pad_id=1, pad_token="<pad>")
mp = np.load(D + r"\shrunk\old2new.npy")
qs = json.load(open(D + r"\out\xlmr_top10.json", encoding="utf-8"))["queries"]
V = np.load(D + r"\out\xlmr_vecs.npy").astype(np.float32)
s = ort.InferenceSession(D + r"\shrunk\text_small.onnx", providers=["CPUExecutionProvider"])
items = []
for q in qs:
    ids = mp[np.array(tok.encode(q).ids)].astype(int)
    e = s.run(None, {"text": ids[None].astype(np.int32)})[0][0]; e = e / np.linalg.norm(e)
    items.append(dict(q=q, ids=ids.tolist(), expect=e.round(5).tolist(), top10=np.argsort(-(V @ e))[:10].tolist()))
json.dump(items, open(A + r"\spike_data.json", "w", encoding="utf-8"), ensure_ascii=False)
V.astype("<f2").tofile(A + r"\image_vecs_f16.bin")
print("assets:", {f: round(os.path.getsize(A + "\\" + f) / 1e6, 1) for f in os.listdir(A)}, "queries", len(items), "vectors", V.shape)
