# Halum — Models

All models are downloaded/exported **in CI only** by `tools/fetch_models.sh`.
None are committed. The shipped APK carries the *exported* artifacts as
`assets/models/*.tflite`.

## Budget

| Item | Budget | Current expectation | Notes |
|---|---|---|---|
| Total model assets in APK | <= 35 MB | ~15 MB | gate in `build.yml` |
| Individual model | <= 25 MB | ~12 MB (MI-GAN) | gate in `build.yml` |
| APK total | <= 70 MB | ~63 MB standard | raised from 60 MB in session 5 |
| ABIs | `armeabi-v7a` required | `armeabi-v7a` + `arm64-v8a` | |

## 1. Bubble segmentation — PRIMARY

| field | value |
|---|---|
| Repo | `https://huggingface.co/juithealien/manga109-segmentation-bubble` |
| Artifact | `best.pt` |
| Size (.pt) | 12,003,405 bytes (11.4 MB) |
| Architecture | YOLO11n-seg — 113 layers, **2,834,763 params**, 9.6 GFLOPs |
| Task | `segment`, `names = {0: 'balloon'}` (single class) |
| License | Apache-2.0 |
| Claimed quality | mAP@50 99.1% (Manga109) |
| Input | RGB, 640×640, letterboxed |
| Outputs (.pt analysis) | `(1, 37, 8400)` boxes+masks coeffs, `(1, 32, 160, 160)` mask proto |
| Export target | **TFLite float32, 11.07 MB** (session 5 — see below) |

> **Settled dtype (session 5).** The shipped model is onnx2tf
> `bubble_float32.tflite`. The float16 variant (5.86 MB) that shipped before
> **does not run on the device CPU**:
> `conv.cc:361 input_type == kTfLiteFloat32 || kTfLiteUInt8 || kTfLiteInt8 ||
> kTfLiteInt16 was not true. Node number 1 (CONV_2D) failed to prepare.`
> The app also fed uint8 bytes to a float32 graph; `Letterboxer` now emits
> NHWC float32 in 0..1, matching `integration_test.py`'s `/255.0`.

### Evidence (probe run 40979428209, 2026-10-30)

- Frieren page 836×1200 → **7 detections**, conf 0.966–0.981, all `cls=0`.
- Box widths 169–263 px — all well above the 60 px `isSmall` threshold.
- Masks shape `[7, 640, 448]` (proto resolution after letterbox).
- **CPU inference 81 ms/image** (AMD EPYC runner, PyTorch). Expect
  200–600 ms on A136B with NNAPI/XNNPACK delegates; fits the realtime budget
  only if TFLite+threads behaves; measure in `memory-test.yml`.
- **No small bubbles** in this sample → the small-bubble path needs a
  dedicated fixture from a dense dialogue page (add to `fixtures/bubbles/`).

### Export pitfalls (must handle)

1. `export(format="tflite", int8=True, data="coco8.yaml")` **fails**:
   `Segment dataset requires equal numbers of boxes and segments, but got
   len(segments) = 0, len(boxes) = 17`. INT8 needs a **segment** dataset.
   → resolve with pseudo-labelled sample pages (DECISIONS D14.1).
2. Ultralytics 8.4.193 deprecates `format='tflite'` → `format='litert'`,
   and `int8=True` → `quantize=int8`. Support both.
3. TFLite export without `half=True` for tflite requires
   `data=` even for FP32 — always pass the generated dataset.

## 2. Bubble fallback (only if #1 disappears) — NOT USED YET

| field | value |
|---|---|
| Repo | `mayocream/comic-text-detector-onnx` |
| License | Apache-2.0 |
| Path to Android | ONNX → ONNX Runtime Mobile (armeabi-v7a ~? MB) or onnx2tf |

Not integrated. ONNX Runtime Mobile on 32-bit adds ~8–10 MB of native
libs; prefer re-exporting to TFLite if the primary is lost.

## 3. Inpainting — MI-GAN (large masks)

| field | value |
|---|---|
| Model | MI-GAN (Picsart), ONNX variant in probe |
| Input | `1×4×512×512` float32 = RGB + mask, both 0..1 |
| Output | `1×3×512×512` float32, range observed **−0.80 .. +1.82** (sigmoid-ish, not clipped) |
| fp32 size | probe `probe/migan.onnx` (size not printed; ~25 MB class) |
| **Shipped form** | dynamic **QUInt8** → **11,745,493 bytes (11.75 MB)** |
| Quantized output diff | mean abs diff vs fp32 = ~0.3/255 on probe |
| License | check upstream before shipping; probe fetched from HF/Picsart release |
| Threshold | used when mask area **>= 15,000 px** (D4) |

### Evidence (probe runs 40935861783, 40936027189)

- QUInt8 ONNX loads and runs: output `(1, 3, 512, 512)`; out range
  `-0.804 .. 1.815`.
- **Residual vs original inside mask × 255: 47.85 fp32 / 48.02 int8.**
  This is the *desired* change magnitude, not an error (DECISIONS D14.3).
- **Mask polarity unverified** (D14.4). Must be visually verified once a
  fixture render is available in `integration-test.yml`.

### Conversion path to Android

`migan_quant.onnx` → `onnx2tf` or `onnx-tf` → TFLite. If conversion of
512×512 conv-heavy graph fails, fallback order:
1. TFLite float32 (~16 MB, still within 25 MB limit) — acceptable.
2. TFLite INT8 full-integer with converter defaults.
3. **No model**: solid fill from ring + Telea — still produces a usable
   overlay; logged as `method=solidfill`.

## 4. Inpainting — OpenCV Telea (small masks)

No model. `org.opencv:opencv:4.11.0` (`Photo.inpaint`, radius 3).
Used when bubble mask area **< 15,000 px**.

## 5. OCR — Google Lens (no model)

Unofficial `v1/crupload`. No local weights. MIT reference:
`rxliuli/chrome-lens-ocr`. Rate limit 60/min, session token handling.

## 6. Translation — none

Chatbot WebView bridge (D6/D10). Zero model bytes.

## Sample / fixture sources

| fixture | source | use |
|---|---|---|
| `frieren.jpg` (836×1200) | `zbu1/manga-translator-cli/docs/frieren_raw.jpg` | bubble recall, inpaint |
| `motl_raw.png`, `wha_raw.jpg`, `ajin_raw.jpg` | same `docs/` dir | recall variety |
| dense dialogue page (to add) | must include **< 60 px** bubbles | AC9 small bubble |
| `fixtures/lens_ja.bin`, `lens_ko.bin` | synthetic protobuf initially; replace with real capture (D14.8) | Lens replay test |

Fixture images are committed (small, license: sample pages from an MIT
repo — record attribution in `NOTICE.md`).
