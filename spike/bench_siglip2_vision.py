"""Encode the sample with the SigLIP 2 image model on the RX 7600 (DirectML, adapter chosen by name). Saves vectors and timings."""
import sys, time, glob, os, json
import numpy as np
from PIL import Image, ImageOps
import pillow_heif
import onnxruntime as ort
sys.path.insert(0, os.path.dirname(__file__))
from gpu_adapters import find_by_name

pillow_heif.register_heif_opener()
M = r"C:\BookahPC\bookahphotos\spike\models\siglip2-base-patch16-224-ONNX\onnx\vision_model.onnx"
OUT = r"C:\BookahPC\bookahphotos\spike\out"
os.makedirs(OUT, exist_ok=True)
limit = int(sys.argv[1]) if len(sys.argv) > 1 else 0
batch = int(sys.argv[2]) if len(sys.argv) > 2 else 16
dev = find_by_name("RX 7600")
print("using DXGI adapter", dev, "(RX 7600, found by name)")
sess = ort.InferenceSession(M, providers=[("DmlExecutionProvider", {"device_id": dev})])
print("providers in use:", sess.get_providers(), "inputs:", [(i.name, i.shape) for i in sess.get_inputs()], "outputs:", [o.name for o in sess.get_outputs()])

files = sorted(p for p in glob.glob(r"D:\Projects\BookahPhotos\sample\**\*", recursive=True) if p.lower().endswith((".jpg", ".jpeg", ".heic", ".png")))
if limit: files = files[:limit]

def load(p):
    im = Image.open(p); im.draft("RGB", (448, 448)); im = ImageOps.exif_transpose(im).convert("RGB").resize((224, 224), Image.BICUBIC)
    return ((np.asarray(im, dtype=np.float32) / 255.0 - 0.5) / 0.5).transpose(2, 0, 1)

t_load = t_inf = 0.0; vecs = []; names = []
for i in range(0, len(files), batch):
    t = time.time(); chunk = []
    for p in files[i:i + batch]:
        try: chunk.append(load(p)); names.append(p)
        except Exception as e: print("skip", os.path.basename(p), type(e).__name__)
    t_load += time.time() - t
    if not chunk: continue
    x = np.stack(chunk); t = time.time()
    out = sess.run(None, {sess.get_inputs()[0].name: x})
    t_inf += time.time() - t
    v = out[1] if len(out) > 1 else out[0]
    vecs.append(v)
V = np.concatenate(vecs); V = V / np.linalg.norm(V, axis=1, keepdims=True)
np.save(os.path.join(OUT, "siglip2_vecs.npy"), V.astype(np.float16))
json.dump(names, open(os.path.join(OUT, "siglip2_names.json"), "w"))
n = len(names)
print(f"images {n}; decode+resize {t_load:.1f}s ({n/t_load:.1f}/s); GPU inference {t_inf:.1f}s ({n/t_inf:.1f}/s); vector dim {V.shape[1]}")
