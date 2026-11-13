import json, numpy as np
from sklearn.cluster import DBSCAN
D = r"C:\BookahPC\bookahphotos\spike"
for kind, eps_list in (("yunet", [0.3, 0.35, 0.4, 0.45, 0.5]), ("bl", [0.4, 0.45, 0.5, 0.55])):
    E = np.load(f"{D}\\out\\faces_{kind}.npz")["E"]; meta = json.load(open(f"{D}\\out\\faces_{kind}_meta.json"))["meta"]
    idx = np.array([i for i, m in enumerate(meta) if m["score"] >= 0.6 and m["w"] >= 40])
    for eps in eps_list:
        lab = DBSCAN(eps=eps, min_samples=3, metric="cosine").fit_predict(E[idx]); ids, c = np.unique(lab[lab >= 0], return_counts=True); c = np.sort(c)[::-1]
        print("RES", kind, eps, "groups", len(ids), "clustered", int((lab >= 0).sum()), "of", len(idx), "top", list(c[:8]))
