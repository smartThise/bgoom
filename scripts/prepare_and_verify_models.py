import os
import sys
import numpy as np
import onnx
import onnxruntime as ort
from onnxconverter_common import float16
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS_DIR = os.path.join(ROOT, "app/src/main/assets/models")
MODNET_FP32 = os.path.join(ROOT, "scripts/hivision_modnet_fp32.onnx")
MODNET_FP16 = os.path.join(MODELS_DIR, "hivision_modnet_fp16.onnx")
DET_PATH = os.path.join(MODELS_DIR, "ppocr_v4_det.onnx")
REC_PATH = os.path.join(MODELS_DIR, "ppocr_v4_rec.onnx")
KEYS_PATH = os.path.join(MODELS_DIR, "ppocr_keys_v1.txt")

def inspect_onnx(path: str, label: str):
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    print(f"=== {label} ({os.path.getsize(path)/1024/1024:.2f} MB) ===")
    for inp in sess.get_inputs():
        print(f"  IN : name={inp.name!r} shape={inp.shape} type={inp.type}")
    for out in sess.get_outputs():
        print(f"  OUT: name={out.name!r} shape={out.shape} type={out.type}")
    meta = sess.get_modelmeta().custom_metadata_map
    if meta:
        print(f"  META keys: {list(meta.keys())}")
    return sess, meta

det_sess, _ = inspect_onnx(DET_PATH, "PP-OCRv4 Det")
rec_sess, rec_meta = inspect_onnx(REC_PATH, "PP-OCRv4 Rec")

if "character" in rec_meta:
    chars = rec_meta["character"].splitlines()
    with open(KEYS_PATH, "w", encoding="utf-8") as f:
        for ch in chars:
            f.write(ch + "\n")
    print(f"Extracted {len(chars)} characters from rec ONNX metadata -> {KEYS_PATH}")
else:
    print("WARNING: No 'character' metadata in rec model!")

mod_fp32_sess, _ = inspect_onnx(MODNET_FP32, "Hivision MODNet FP32")

# Convert MODNet FP32 -> FP16 keeping float32 I/O so Android ORT mobile stays fast and simple
model = onnx.load(MODNET_FP32)
model_fp16 = float16.convert_float_to_float16(model, keep_io_types=True)
onnx.save(model_fp16, MODNET_FP16)
mod_fp16_sess, _ = inspect_onnx(MODNET_FP16, "Hivision MODNet FP16")

# Compare FP32 vs FP16 output on deterministic test input
rng = np.random.default_rng(42)
dummy_mod = rng.uniform(-1.0, 1.0, size=(1, 3, 512, 512)).astype(np.float32)
in_name = mod_fp32_sess.get_inputs()[0].name
out32 = mod_fp32_sess.run(None, {in_name: dummy_mod})[0]
out16 = mod_fp16_sess.run(None, {in_name: dummy_mod})[0]
max_diff = float(np.max(np.abs(out32 - out16)))
mean_diff = float(np.mean(np.abs(out32 - out16)))
print(f"MODNet FP32 vs FP16 diff: max={max_diff:.6f}, mean={mean_diff:.6f}, shape={out16.shape}, range=[{out16.min():.4f}, {out16.max():.4f}]")

# Quick OCR sanity test on rendered English/Chinese text
img = Image.new("RGB", (640, 128), (255, 255, 255))
draw = ImageDraw.Draw(img)
font_candidates = [
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/STHeiti Light.ttc",
    "/System/Library/Fonts/Helvetica.ttc",
]
font = None
for fc in font_candidates:
    if os.path.exists(fc):
        try:
            font = ImageFont.truetype(fc, 40)
            break
        except Exception:
            pass
if font is None:
    font = ImageFont.load_default()
draw.text((24, 36), "BGOOM 2026 大爆测试", fill=(0, 0, 0), font=font)

# Test REC directly on cropped line (H=48, W proportional)
rec_h = 48
rec_w = max(48, int(round(img.width * (rec_h / img.height))))
rec_img = img.resize((rec_w, rec_h), Image.BILINEAR)
arr = np.asarray(rec_img, dtype=np.float32)
# RapidOCR / PaddleOCR uses BGR normalized (x/255 - 0.5)/0.5
arr_bgr = arr[:, :, ::-1]
arr_norm = (arr_bgr / 255.0 - 0.5) / 0.5
rec_input = np.transpose(arr_norm, (2, 0, 1))[None, :, :, :].astype(np.float32)
rec_out = rec_sess.run(None, {rec_sess.get_inputs()[0].name: rec_input})[0]
print("REC output shape:", rec_out.shape)

if os.path.exists(KEYS_PATH):
    with open(KEYS_PATH, "r", encoding="utf-8") as f:
        keys = [line.rstrip("\r\n") for line in f]
    # RapidOCR/PaddleOCR CTC: index 0 is blank, indices 1..len(keys) are keys, last is space
    dict_list = ["<blank>"] + keys + [" "]
    preds = np.argmax(rec_out[0], axis=1)
    decoded = []
    prev = -1
    for p in preds:
        p = int(p)
        if p != 0 and p != prev:
            if p < len(dict_list):
                decoded.append(dict_list[p])
        prev = p
    print("REC decoded text:", "".join(decoded))
