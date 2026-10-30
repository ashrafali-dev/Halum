#!/usr/bin/env python3
"""Export juithealien/manga109-segmentation-bubble (YOLO11n-seg) for Android.

Why this is not a one-liner (see .halum/MODELS.md + BUGS.md):

1. Ultralytics 8.4.x converts TFLite via litert-torch, which fails on this
   model with `duplicate registrations for aten.*` (probe + CI observation).
2. ONNX export works: (1,3,640,640) -> (1,37,8400) + (1,32,160,160).
3. There is no reliable ONNX -> TFLite converter on Python 3.12 that handles
   this graph (onnx2tf needs TF<=3.10; ai-edge-litert needs TF).

So we do the conversion ourselves with onnx2tf >= 2.6 (requires Python
3.12, which is what the workflows use). Since 2.0 onnx2tf talks to LiteRT
directly through `ai-edge-litert`, so no TensorFlow wheel is needed; the
`[tensorflow]` extra only matters for the legacy `tf_converter` backend.

We ask the default `flatbuffer_direct` backend for the float32 and float16
variants. INT8 is deliberately not attempted: it needs a segment-labelled
calibration dataset (BUGS.md), and float16 already lands at ~6 MB.

If conversion fails the script returns non-zero and fetch_models.sh aborts,
because the app cannot run without a bubble model.
"""
import argparse
import os
import shutil
import subprocess
import sys


def sh(cmd, **kw):
    print("[export_bubble]", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run(cmd, check=True, **kw)


def yolo_to_onnx(pt: str, out_dir: str) -> str:
    from ultralytics import YOLO

    model = YOLO(pt)
    if model.task != "segment":
        raise SystemExit(f"unexpected task {model.task!r}; expected 'segment'")
    path = model.export(format="onnx", imgsz=640, opset=18)
    dest = os.path.join(out_dir, "bubble.onnx")
    shutil.copyfile(path, dest)
    print(f"[export_bubble] onnx {os.path.getsize(dest) / 1e6:.2f} MB -> {dest}")
    return dest


ONNX2TF_VERSION = "2.6.9"


def onnx_to_tflite(onnx_path: str, work_dir: str) -> str:
    """onnx2tf in its own venv: it pins exact onnx/numpy/onnxruntime versions
    that would otherwise fight with ultralytics in the main interpreter."""
    venv = os.path.join(work_dir, "venv_tf")
    if not os.path.isdir(venv):
        sh([sys.executable, "-m", "venv", venv])
        sh([os.path.join(venv, "bin", "pip"), "install", "--quiet", "--upgrade", "pip"])
        sh([
            os.path.join(venv, "bin", "pip"), "install", "--quiet",
            f"onnx2tf=={ONNX2TF_VERSION}",
        ])
    tb_dir = os.path.join(work_dir, "tb")
    shutil.rmtree(tb_dir, ignore_errors=True)
    sh([
        os.path.join(venv, "bin", "onnx2tf"),
        "-i", onnx_path, "-o", tb_dir, "-b", "1",
        "--non_verbose",
    ])
    # flatbuffer_direct writes <model>_float32.tflite and <model>_float16.tflite.
    candidates = sorted(
        os.path.join(tb_dir, f) for f in os.listdir(tb_dir)
        if f.endswith(".tflite")
    )
    if not candidates:
        raise SystemExit("onnx2tf produced no tflite")
    # float32, not float16. The float16 flatbuffer_direct output fails to
    # prepare on the device CPU: "conv.cc:361 input_type == kTfLiteFloat32 ||
    # ... was not true. Node number 1 (CONV_2D) failed to prepare." float32 is
    # ~11 MB instead of 5.9 MB but it is the variant known to run.
    chosen = next((c for c in candidates if "float32" in c), candidates[0])
    print(f"[export_bubble] tflite {os.path.getsize(chosen) / 1e6:.2f} MB ({os.path.basename(chosen)})")
    return chosen


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", required=True)
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    onnx_path = os.path.join(args.work, "bubble.onnx")
    if os.path.exists(onnx_path):
        # Cached: CI caches .cache/models across runs.
        print(f"[export_bubble] reusing {onnx_path}")
    else:
        onnx_path = yolo_to_onnx(args.pt, args.work)
    tflite = onnx_to_tflite(onnx_path, args.work)
    dest = os.path.join(args.out, "bubble.tflite")
    shutil.copyfile(tflite, dest)
    print(f"[export_bubble] wrote {dest} {os.path.getsize(dest) / 1e6:.2f} MB")
    # The ONNX stays in --work as the integration suite's reference model
    # (integration_test.py runs onnxruntime; the app never ships the ONNX).
    print(f"[export_bubble] reference onnx kept at {onnx_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
