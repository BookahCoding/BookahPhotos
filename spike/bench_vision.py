"""Generic image-encoder benchmark on the RX 7600 (DirectML, adapter by name). Usage:
python -I -X utf8 bench_vision.py <name> <model.onnx> <size> <mean|clip|half> <crop|squash>
Decoding runs on a thread pool because HEIC decode was the bottleneck."""
import sys, time, glob, os, json
import numpy as np
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageOps
import pillow_heif
import onnxruntime as ort
sys.path.insert(0, os.path.dirname(__file__))
from gpu_adapters import find_by_name
pillow_heif.register_heif_opener()

name, model, size, norm, mode = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
mean, std = ((0.48145466, 0.4578275, 0.40821073), (0.26862954, 0.26130258, 0.27577711)) if norm == "clip" else ((0.5,) * 3, (0.5,) * 3)
mean = np.array(mean, np.float32).reshape(3, 1, 1); std = np.array(std, np.float32).reshape(3, 1, 1)
OUT = r"C:\BookahPC\bookahphotos\spike\out"
dev = find_by_name("RX 7600")
sess = ort.InferenceSession(model, providers=[("DmlExecutionProvider", {"device_id": dev})])
inp = sess.get_inputs()[0]; print("adapter", dev, "(RX 7600 by name) providers", sess.get_providers(), "input", inp.name, inp.shape, "outputs", [(o.name, o.shape) for o in sess.get_outputs()])
files = sorted(p for p in glob.glob(r"D:\Projects\BookahPhotos\sample\**\*", recursive=True) if p.lower().endswith((".jpg", ".jpeg", ".heic", ".png")))

def load(p):
    try:
        im = Image.open(p); im.draft("RGB", (size * 2, size * 2)); im = ImageOps.exif_transpose(im).convert("RGB")
        if mode == "squash": im = im.resize((size, size), Image.BICUBIC)
        else:
            w, h = im.size; s = size / min(w, h); im = im.resize((max(size, round(w * s)), max(size, round(h * s))), Image.BICUBIC)
            w, h = im.size; l, t = (w - size) // 2, (h - size) // 2; im = im.crop((l, t, l + size, t + size))
        return p, ((np.asarray(im, np.float32) / 255.0).transpose(2, 0, 1) - mean) / std
    except Exception as e:
        return p, None

t0 = time.time(); names = []; vecs = []; t_inf = 0.0
with ThreadPoolExecutor(8) as ex:
    loaded = list(ex.map(load, files))
t_dec = time.time() - t0
loaded = [x for x in loaded if x[1] is not None]; names = [p for p, _ in loaded]
B = 1 if inp.shape[0] == 1 else 16  # the Immich-exported models have a fixed batch of 1
for i in range(0, len(loaded), B):
    x = np.stack([a for _, a in loaded[i:i + B]]); t = time.time()
    out = sess.run(None, {inp.name: x}); t_inf += time.time() - t
    vecs.append(out[-1] if len(out) > 1 and out[-1].ndim == 2 else out[0])
V = np.concatenate(vecs); V = V / np.linalg.norm(V, axis=1, keepdims=True)
np.save(f"{OUT}\\{name}_vecs.npy", V.astype(np.float16)); json.dump(names, open(f"{OUT}\\{name}_names.json", "w"))
n = len(names); print(f"{name}: images {n}; decode (8 threads) {t_dec:.0f}s = {n/t_dec:.1f}/s; GPU inference {t_inf:.1f}s = {n/t_inf:.1f}/s; dim {V.shape[1]}")
