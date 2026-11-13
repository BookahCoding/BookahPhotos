"""Faces: YuNet finds them, SFace describes each as 128 numbers (both from the OpenCV Zoo, permissive licence). Runs on the CPU with OpenCV, exactly as in
the Phase 0 test, so results are repeatable and no graphics card is needed. Boxes are returned normalised (0..1) on the UPRIGHT picture."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np

MODELS_DIR = Path(r"C:\BookahPC\bookahphotos\spike\models\opencv_zoo")
FACE_MODEL = "yunet+sface"
DETECT_SIDE = 640            # YuNet looks at the picture shrunk to 640 px on the long side
DETECT_SCORE = 0.6
MIN_FACE_AT_1280 = 40        # a face counts as usable when it is at least this wide on a 1280 px long side (Phase 0 rule)


@dataclass
class Face:
    x1: float
    y1: float
    x2: float
    y2: float
    score: float
    width_1280: float        # width in pixels if the picture's long side were 1280 (size rule independent of the cache size)
    emb: np.ndarray          # float32 [128], unit length

    @property
    def usable(self) -> bool:
        return self.score >= DETECT_SCORE and self.width_1280 >= MIN_FACE_AT_1280

    def key(self) -> tuple[float, float, float, float]:
        return (round(self.x1, 3), round(self.y1, 3), round(self.x2, 3), round(self.y2, 3))


class FaceEngine:
    def __init__(self, models_dir: Path = MODELS_DIR) -> None:
        import cv2
        self._cv2 = cv2
        self._det = cv2.FaceDetectorYN.create(str(models_dir / "yunet.onnx"), "", (DETECT_SIDE, DETECT_SIDE), DETECT_SCORE, 0.3, 5000)
        self._rec = cv2.FaceRecognizerSF.create(str(models_dir / "sface.onnx"), "")

    def detect_and_embed(self, rgb: np.ndarray) -> list[Face]:
        """rgb: uint8 [H,W,3], upright. Returns every face with detector score >= 0.6 (usable or not: the caller filters with .usable)."""
        cv2 = self._cv2
        h, w = rgb.shape[:2]
        s = DETECT_SIDE / max(h, w)
        small = cv2.cvtColor(cv2.resize(rgb, (round(w * s), round(h * s))), cv2.COLOR_RGB2BGR)
        self._det.setInputSize((small.shape[1], small.shape[0]))
        _, found = self._det.detect(small)
        if found is None:
            return []
        bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
        out: list[Face] = []
        for r in found:
            row = r.copy()
            row[:14] = row[:14] / s                                   # back to the full picture's pixels
            x1, y1, bw, bh = (float(row[0]), float(row[1]), float(row[2]), float(row[3]))
            try:
                feat = self._rec.feature(self._rec.alignCrop(bgr, row))[0]
            except Exception:
                continue
            n = float(np.linalg.norm(feat))
            if n == 0:
                continue
            out.append(Face(max(0.0, x1 / w), max(0.0, y1 / h), min(1.0, (x1 + bw) / w), min(1.0, (y1 + bh) / h), float(row[14]),
                            bw * 1280.0 / max(h, w), (feat / n).astype(np.float32)))
        return out
