#!/usr/bin/env python3
"""CI integration checks that need a real model and real images.

Scope of this file (deliberately narrow):
  * bubble recall on fixtures/pages.json pages (AC3, recall >= 80%)
  * the bubble ONNX detections, saved as fixtures/bubble_ref.json so the
    Kotlin Side can be checked against the same numbers without a model

The Lens decoder replay (AC2), placement/overlay (AC8, AC9) and the bridge
state machine (AC5) are Kotlin-side tests with injected fakes; they run in
`build.yml` as JVM unit tests, not here. MI-GAN (AC4) is skipped until
`MIGAN_ONNX_URL` exists (see .halum/PROGRESS.md).

runs on: ubuntu-latest in integration-test.yml. Never on Termux.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import socket
import sys
import urllib.request
import urllib.error

SAMPLE_BASE = "https://raw.githubusercontent.com/zbu1/manga-translator-cli/main/docs"
RECALL_MIN = 0.80
IOU_MIN = 0.50      # a detection matches a reference box at >= this IoU
# D11 isSmall, kept in sync with BubbleClassifier.kt
SMALL_WIDTH_PX = 60.0
SMALL_AREA_PX = 4000.0
ORDER_KEY = (   # stable detection order shared with the Kotlin side
    "score", "x1", "y1", "x2", "y2",
)


def find_reference_onnx(start: str) -> str:
    """Walk up until the repo root (identified by .cache/) and return the
    reference ONNX path. Not a fixed number of `dirname`s: that silently
    pointed at app/.cache the first time (run 40989289071)."""
    cur = start
    while cur and cur != os.path.dirname(cur):
        candidate = os.path.join(cur, ".cache", "models", "work", "bubble.onnx")
        if os.path.exists(candidate):
            return candidate
        if os.path.exists(os.path.join(cur, ".cache")):
            return candidate  # right root, model missing
        cur = os.path.dirname(cur)
    return os.path.join(start, "bubble.onnx")


def log(msg: str) -> None:
    print(f"[integration] {msg}", flush=True)


def fetch(url: str, dest: str) -> bool:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    try:
        with urllib.request.urlopen(url, timeout=30) as r, open(dest, "wb") as f:
            f.write(r.read())
        return True
    except (urllib.error.URLError, socket.timeout, OSError) as exc:
        log(f"WARN could not fetch {url}: {exc}")
        return False


def letterbox(w: int, h: int, size: int = 640):
    """(scale, pad_x, pad_y, resized_w, resized_h).

    Must stay identical to SegDecoder.Letterbox.of in Kotlin: the recall check
    and the app's boxes have to describe the same rectangles.
    """
    scale = min(size / w, size / h)
    rw, rh = round(w * scale), round(h * scale)
    return scale, (size - rw) // 2, (size - rh) // 2, rw, rh


def iou(a, b) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)


def load_detector(onnx_path: str, imgsz: int = 640):
    """Segment-task ONNX; returns a function (path -> boxes[])."""
    import numpy as np
    import onnxruntime as ort
    from PIL import Image

    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]

    def boxes(path: str):
        img = Image.open(path).convert("RGB")
        w, h = img.size
        scale, pad_x, pad_y, rw, rh = letterbox(w, h, imgsz)
        canvas = np.full((imgsz, imgsz, 3), 114, dtype=np.uint8)
        canvas[pad_y:pad_y + rh, pad_x:pad_x + rw] = np.asarray(
            img.resize((rw, rh)), dtype=np.uint8)
        x = canvas[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0
        out = sess.run(None, {inp.name: x})[0]   # (1, 4+nc, 8400) or like
        preds = out[0] if out.ndim == 3 else out
        if preds.shape[0] < preds.shape[1]:      # channels-first
            preds = preds.T
        # YOLO11-seg: [cx, cy, w, h, cls..., seg coeffs...]; one class here.
        # Class scores go through a sigmoid: [[0,1]] is already probabilities.
        def prob(v: float) -> float:
            return float(v) if 0.0 <= v <= 1.0 else 1.0 / (1.0 + math.exp(-v))

        raw = []
        for row in preds:
            score = prob(float(row[4]))
            if score < 0.25:
                continue
            cx, cy, bw, bh = (float(v) for v in row[:4])
            x1 = (cx - bw / 2 - pad_x) / scale
            y1 = (cy - bh / 2 - pad_y) / scale
            x2 = (cx + bw / 2 - pad_x) / scale
            y2 = (cy + bh / 2 - pad_y) / scale
            raw.append([x1, y1, x2, y2, score])
        # NMS: ultralytics exports do not include it, and without it every
        # visible bubble appears 3-4 times (run 40989519314: 70 on frieren).
        raw.sort(key=lambda r: r[4], reverse=True)
        res = []
        for box in raw:
            if all(iou(box, kept) < 0.45 for kept in res):
                res.append([round(v, 1) for v in box[:4]] + [round(box[4], 3)])
        return res

    return boxes


def run(fixtures: str, models: str, out: str) -> int:
    os.makedirs(out, exist_ok=True)
    # The ONNX is the untranslated reference and is staged out of the APK;
    # the TFLite is what the app actually loads, so its presence is gated too.
    # layout: <root>/app/src/main/assets/models (tflite) and
    #         <root>/.cache/models/work (reference onnx)
    onnx_path = find_reference_onnx(os.path.abspath(models))
    tflite_path = os.path.join(models, "bubble.tflite")
    if not os.path.exists(onnx_path):
        log(f"FAIL no reference ONNX at {onnx_path} (fetch_models.sh should leave it there)")
        return 1
    if not os.path.exists(tflite_path):
        log("FAIL no bubble.tflite — build.yml needs it in the APK")
        return 1

    index_path = os.path.join(fixtures, "pages.json")
    with open(index_path) as f:
        index = json.load(f)
    pages = index["pages"]

    boxes_of = load_detector(onnx_path)
    ref_out = {"_comment": "generated by tools/integration_test.py; boxes are "
                           "[x1,y1,x2,y2,score] in page pixels, score desc",
               "pages": {}}
    matched_total = expected_total = 0
    failures = []

    for page in pages:
        name = page["file"]
        path = os.path.join(fixtures, name)
        if not fetch(f"{SAMPLE_BASE}/{page['source_name']}", path):
            log(f"SKIP {name}: download unavailable")
            continue
        dets = boxes_of(path)
        ref_out["pages"][name] = dets
        small = [
            [round(b[0], 1), round(b[1], 1), round(b[2], 1), round(b[3], 1)]
            for b in dets
            if (b[2] - b[0]) < SMALL_WIDTH_PX or (b[2] - b[0]) * (b[3] - b[1]) < SMALL_AREA_PX
        ]
        log(f"{name}: {len(dets)} bubbles, {len(small)} small (< {SMALL_WIDTH_PX:g} px)")
        if small:
            ref_out.setdefault("small", {})[name] = small
        expected = page.get("expected") or []
        if not expected:
            log(f"{name}: detected {len(dets)} bubbles (no reference count; AC3 not asserted)")
            continue
        hit = sum(1 for e in expected if any(iou(d, e) >= IOU_MIN for d in dets))
        expected_total += len(expected)
        matched_total += hit
        recall = hit / len(expected)
        log(f"{name}: recall {recall:.2f} ({hit}/{len(expected)})")
        if recall < RECALL_MIN:
            failures.append(f"{name}: recall {recall:.2f} < {RECALL_MIN}")

    with open(os.path.join(out, "bubble_ref.json"), "w") as f:
        json.dump(ref_out, f, indent=2)

    if expected_total == 0:
        # No reference annotations => AC3 unprovable; do not fake a pass.
        log("FAIL AC3 not assertable: no page in fixtures/pages.json has 'expected' boxes")
        return 1
    overall = matched_total / expected_total
    log(f"AC3 overall recall {overall:.3f} over {expected_total} reference bubbles")
    if overall < RECALL_MIN:
        failures.append(f"overall recall {overall:.3f} < {RECALL_MIN}")
    for msg in failures:
        log(f"FAIL {msg}")
    log("OK" if not failures else "FAILED")
    return 0 if not failures else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures", required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    return run(args.fixtures, args.models, args.out)


if __name__ == "__main__":
    sys.exit(main())
