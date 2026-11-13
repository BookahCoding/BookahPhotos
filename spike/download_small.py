"""Download the first batch of spike models (each file under 1 GB) into C:\\BookahPC\\bookahphotos\\spike\\models."""
from huggingface_hub import hf_hub_download
import os

dest = r"C:\BookahPC\bookahphotos\spike\models"
jobs = [
    ("onnx-community/siglip2-base-patch16-224-ONNX", ["onnx/vision_model.onnx", "onnx/text_model_int8.onnx", "onnx/text_model_fp16.onnx", "tokenizer.json", "tokenizer.model", "config.json", "preprocessor_config.json"]),
    ("immich-app/XLM-Roberta-Base-ViT-B-32__laion5b_s13b_b90k", ["visual/model.onnx", "textual/tokenizer.json", "textual/sentencepiece.bpe.model"]),
    ("immich-app/buffalo_l", ["detection/model.onnx", "recognition/model.onnx"]),
    ("immich-app/buffalo_s", ["detection/model.onnx", "recognition/model.onnx"]),
]
for repo, files in jobs:
    local = os.path.join(dest, repo.split("/")[1])
    for f in files:
        try:
            p = hf_hub_download(repo, f, local_dir=local)
            print("ok", repo, f, round(os.path.getsize(p) / 1e6, 1), "MB", flush=True)
        except Exception as e:
            print("FAILED", repo, f, type(e).__name__, str(e)[:120], flush=True)
