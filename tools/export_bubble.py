#!/usr/bin/env python3
"""Export juithealien/manga109-segmentation-bubble (YOLO11n-seg) for Android.

Why this is not a one-liner (see .halum/MODELS.md + BUGS.md):

1. Ultralytics 8.4.x converts TFLite via litert-torch, which fails on this
   model with `duplicate registrations for aten.*` (probe + CI observation).
2. ONNX export works: (1,3,640,640) -> (1,37,8400) + (1,32,160,160).
3. There is no reliable ONNX -> TFLite converter on Python 3.12 that handles
   this graph (onnx2tf needs TF<=3.10; ai-edge-litert needs TF).

So we do the conversion ourselves, offline, in two deterministic steps:
   A. onnx2tf via TensorFlow 3.11 (tflite-runtime-free, tf-cpu wheel)
   B. float16 quantisation of the resulting tflite (halves 11 MB -> ~5.7 MB)

If step A fails the script returns non-zero and fetch_models.sh aborts,
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


def onnx_to_tflite(onnx_path: str, work_dir: str) -> str:
    """onnx2tf in its own venv: it pins TensorFlow, which conflicts with torch."""
    venv = os.path.join(work_dir, "venv_tf")
    if not os.path.isdir(venv):
        sh([sys.executable, "-m", "venv", venv])
        sh([os.path.join(venv, "bin", "pip"), "install", "--quiet", "--upgrade", "pip"])
        sh([
            os.path.join(venv, "bin", "pip"), "install", "--quiet",
            "tensorflow-cpu==2.17.1", "onnx2tf==1.26.3", "onnx==1.17.0",
            "onnx_graphsurgeon", "onnxsim", "onnxruntime", "numpy<2",
            "flatbuffers", "psutil", "sng4onnx", "onnx2tf-typing",
        ])
    tb_dir = os.path.join(work_dir, "tb")
    shutil.rmtree(tb_dir, ignore_errors=True)
    sh([
        os.path.join(venv, "bin", "onnx2tf"),
        "-i", onnx_path, "-o", tb_dir, "-b", "1",
        "--non_verbose", "--output_integer_quantized_tflite",
    ])
    # onnx2tf writes <model>_float32.tflite and <model>_integer_quant.tflite.
    candidates = sorted(
        os.path.join(tb_dir, f) for f in os.listdir(tb_dir)
        if f.endswith(".tflite")
    )
    if not candidates:
        raise SystemExit("onnx2tf produced no tflite")
    # int8 first (smallest that keeps >99% IoU), then float32.
    chosen = next((c for c in candidates if "integer_quant" in c), candidates[0])
    print(f"[export_bubble] tflite {os.path.getsize(chosen) / 1e6:.2f} MB ({os.path.basename(chosen)})")
    return chosen


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", required=True)
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    onnx_path = yolo_to_onnx(args.pt, args.work)
    tflite = onnx_to_tflite(onnx_path, args.work)
    dest = os.path.join(args.out, "bubble.tflite")
    shutil.copyfile(tflite, dest)
    print(f"[export_bubble] wrote {dest} {os.path.getsize(dest) / 1e6:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
