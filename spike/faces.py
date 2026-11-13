"""Face spike. Usage: faces.py <bl|bs|yunet>. Detect + embed every face in the sample, cluster, save results in C:\\BookahPC\\bookahphotos\\spike\\out.
bl/bs = Immich-exported insightface buffalo_l / buffalo_s (SCRFD + ArcFace) on the RX 7600 via DirectML (adapter by name).
yunet = OpenCV Zoo YuNet + SFace on the CPU (permissive licence)."""
import sys, os, time, glob, json
import numpy as np, cv2
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageOps
import pillow_heif
import onnxruntime as ort
sys.path.insert(0, os.path.dirname(__file__))
from gpu_adapters import find_by_name
pillow_heif.register_heif_opener()

kind = sys.argv[1]
D = r"C:\BookahPC\bookahphotos\spike"; M = D + r"\models"
files = sorted(p for p in glob.glob(r"D:\Projects\BookahPhotos\sample\**\*", recursive=True) if p.lower().endswith((".jpg", ".jpeg", ".heic", ".png")))
TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]], np.float32)

def load(p):
    try:
        im = Image.open(p); im.draft("RGB", (2560, 2560)); im = ImageOps.exif_transpose(im).convert("RGB")
        w, h = im.size; s = 1280 / max(w, h)
        if s < 1: im = im.resize((round(w * s), round(h * s)), Image.BILINEAR)
        return np.asarray(im)  # RGB, long side <= 1280
    except Exception:
        return None

if kind in ("bl", "bs"):
    mdl = "buffalo_l" if kind == "bl" else "buffalo_s"
    dev = find_by_name("RX 7600"); prov = [("DmlExecutionProvider", {"device_id": dev})]
    det = ort.InferenceSession(f"{M}\\{mdl}\\detection\\model.onnx", providers=prov)
    rec = ort.InferenceSession(f"{M}\\{mdl}\\recognition\\model.onnx", providers=prov)
    print("adapter", dev, "(RX 7600 by name)", det.get_providers())
    dname = det.get_inputs()[0].name; rname = rec.get_inputs()[0].name
    def detect(img):
        h, w = img.shape[:2]; s = 640 / max(h, w); small = cv2.resize(img, (round(w * s), round(h * s)))
        canvas = np.zeros((640, 640, 3), np.uint8); canvas[:small.shape[0], :small.shape[1]] = small
        blob = ((canvas.astype(np.float32) - 127.5) / 128.0).transpose(2, 0, 1)[None]
        out = det.run(None, {dname: blob}); boxes = []; scores = []; kps = []
        for i, st in enumerate((8, 16, 32)):
            n = 640 // st; c = np.stack(np.mgrid[:n, :n][::-1], -1).reshape(-1, 2).astype(np.float32) * st; c = np.repeat(c, 2, axis=0)
            sc = out[i][:, 0]; b = out[i + 3] * st; k = out[i + 6] * st; m = sc > 0.5
            bb = np.stack([c[:, 0] - b[:, 0], c[:, 1] - b[:, 1], c[:, 0] + b[:, 2], c[:, 1] + b[:, 3]], 1)
            kk = np.stack([c[:, 0:1] + k[:, 0::2], c[:, 1:2] + k[:, 1::2]], 2)
            boxes.append(bb[m]); scores.append(sc[m]); kps.append(kk[m])
        if not sum(len(x) for x in scores): return []
        boxes = np.concatenate(boxes) / s; scores = np.concatenate(scores); kps = np.concatenate(kps) / s
        keep = cv2.dnn.NMSBoxes([[b[0], b[1], b[2] - b[0], b[3] - b[1]] for b in boxes.tolist()], scores.tolist(), 0.5, 0.4)
        return [(boxes[i], float(scores[i]), kps[i]) for i in np.array(keep).reshape(-1)]
    def embed(img, kp):
        M_, _ = cv2.estimateAffinePartial2D(kp.astype(np.float32), TEMPLATE, method=cv2.LMEDS)
        crop = cv2.warpAffine(img, M_, (112, 112), borderValue=0)
        blob = ((crop.astype(np.float32) - 127.5) / 127.5).transpose(2, 0, 1)[None]
        e = rec.run(None, {rname: blob})[0][0]; return e / np.linalg.norm(e), crop
else:
    ydet = cv2.FaceDetectorYN.create(M + r"\opencv_zoo\yunet.onnx", "", (640, 640), 0.6, 0.3, 5000)
    srec = cv2.FaceRecognizerSF.create(M + r"\opencv_zoo\sface.onnx", "")
    def detect(img):
        h, w = img.shape[:2]; s = 640 / max(h, w); small = cv2.cvtColor(cv2.resize(img, (round(w * s), round(h * s))), cv2.COLOR_RGB2BGR)
        ydet.setInputSize((small.shape[1], small.shape[0])); _, f = ydet.detect(small)
        if f is None: return []
        res = []
        for r in f:
            b = np.array([r[0], r[1], r[0] + r[2], r[1] + r[3]]) / s; kp = r[4:14].reshape(5, 2) / s
            res.append((b, float(r[14]), kp, (r.copy(), s)))
        return res
    def embed(img, kp, extra=None):
        bgr = cv2.cvtColor(img, cv2.COLOR_RGB2BGR); row, s = extra; row = row.copy(); row[:14] = row[:14] / s
        al = srec.alignCrop(bgr, row); e = srec.feature(al)[0]; return e / np.linalg.norm(e), cv2.cvtColor(al, cv2.COLOR_BGR2RGB)

t0 = time.time()
with ThreadPoolExecutor(8) as ex: imgs = list(ex.map(load, files))
print(f"decoded {sum(i is not None for i in imgs)} images in {time.time()-t0:.0f}s"); t_det = t_rec = 0.0
E = []; meta = []; crops = []
for idx, img in enumerate(imgs):
    if img is None: continue
    t = time.time(); faces = detect(img); t_det += time.time() - t
    h, w = img.shape[:2]
    for f in faces:
        b, sc, kp = f[0], f[1], f[2]
        t = time.time()
        e, crop = embed(img, kp, f[3]) if kind == "yunet" else embed(img, kp)
        t_rec += time.time() - t
        E.append(e); crops.append(cv2.resize(crop, (96, 96)))
        meta.append(dict(img=idx, score=sc, w=float(b[2] - b[0]), h=float(b[3] - b[1]), imw=w, imh=h, x1=float(b[0]) / w, y1=float(b[1]) / h, x2=float(b[2]) / w, y2=float(b[3]) / h))
n_img = sum(i is not None for i in imgs); nf = len(E)
print(f"{kind}: {nf} faces in {len({m['img'] for m in meta})} photos of {n_img}; detection {t_det:.1f}s ({n_img/t_det:.1f} photos/s); embedding {t_rec:.1f}s ({nf/max(t_rec,1e-9):.1f} faces/s)")
np.savez_compressed(f"{D}\\out\\faces_{kind}.npz", E=np.array(E, np.float32), crops=np.array(crops, np.uint8))
json.dump(dict(files=files, meta=meta), open(f"{D}\\out\\faces_{kind}_meta.json", "w"))
