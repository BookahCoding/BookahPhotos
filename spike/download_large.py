"""Second batch, approved by Chris on 2026-11-11: XLM-R B/32 text (1.1 GB) and nllb-clip-base-siglip (text 1.66 GB, visual 373 MB)."""
from huggingface_hub import hf_hub_download
import os
dest = r"C:\BookahPC\bookahphotos\spike\models"
jobs = [
    ("immich-app/XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k", ["textual/model.onnx"]),
    ("immich-app/nllb-clip-base-siglip__v1", ["visual/model.onnx", "textual/model.onnx", "textual/tokenizer.json", "textual/sentencepiece.bpe.model"]),
]
for repo, files in jobs:
    local = os.path.join(dest, repo.split("/")[1])
    for f in files:
        try:
            p = hf_hub_download(repo, f, local_dir=local)
            print("ok", repo, f, round(os.path.getsize(p) / 1e6, 1), "MB", flush=True)
        except Exception as e:
            print("FAILED", repo, f, type(e).__name__, str(e)[:120], flush=True)
