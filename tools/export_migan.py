#!/usr/bin/env python3
"""Quantize MI-GAN's ONNX and convert it to TFLite for Android.

Evidence this is built on (.halum/MODELS.md, probe run 40935861783):
  * input  1x4x512x512 float32 = RGB(0..1) concatenated with mask(0..1)
  * output 1x3x512x512 float32, unclipped range ≈ -0.8 .. +1.8
  * dynamic QUInt8 quantisation -> 11.75 MB and still runs

Conversion ONNX -> TFLite is attempted with onnx2tf, then with plain
onnx-tf; if neither works we keep the quantized ONNX only as a build-time
record and let the app fall back to Telea/solid fill. The app *requires* a
.tflite, so a failure here is non-fatal by design.
"""
import argparse
import os
import shutil
import sys
import subprocess


def run(cmd):
    print("[export_migan]", " ".join(cmd))
    subprocess.run(cmd, check=True)


def pick_tflite(dir_path, cap_bytes):
    """Smallest *.tflite under cap_bytes, preferring quantised, then float16."""
    import glob

    files = [f for f in glob.glob(os.path.join(dir_path, "*.tflite")) if os.path.getsize(f) <= cap_bytes]
    if not files:
        return None

    def rank(path):
        name = os.path.basename(path).lower()
        if "int8" in name or "dynamic_range_quant" in name or "integer_quant" in name:
            pref = 0
        elif "float16" in name:
            pref = 1
        else:
            pref = 2
        return (pref, os.path.getsize(path))

    return sorted(files, key=rank)[0]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--onnx", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    work = os.path.dirname(os.path.abspath(args.onnx))
    quant = os.path.join(work, "migan_quant.onnx")

    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
        quantize_dynamic(args.onnx, quant, weight_type=QuantType.QUInt8)
        print(f"[export_migan] quantized: {os.path.getsize(quant) / 1e6:.2f} MB")
    except Exception as exc:  # noqa: BLE001
        print(f"[export_migan] quantisation failed ({exc}); using fp32 onnx", file=sys.stderr)
        quant = args.onnx

    dest = os.path.join(args.out, "migan.tflite")
    tb_dir = os.path.join(work, "migan_tb")
    try:
        run([sys.executable, "-m", "pip", "install", "--quiet", "onnx2tf", "onnx_graphsurgeon",
             "sng4onnx", "onnxsim", "onnxruntime"])
        run([sys.executable, "-m", "onnx2tf", "-i", quant, "-o", tb_dir, "-b", "1",
             "--non_verbose"])
        # onnx2tf always emits float32 and float16; float32 was 28.8 MB and
        # busts the 25 MB per-model cap (D8), so pick the smallest variant that
        # fits, preferring a quantised one over float16 over float32.
        picked = pick_tflite(tb_dir, cap_bytes=25 * 1024 * 1024)
        if picked is None:
            print("[export_migan] no tflite variant fits the 25 MB cap", file=sys.stderr)
            return 1
        shutil.copyfile(picked, dest)
        print(f"[export_migan] wrote {dest} {os.path.getsize(dest) / 1e6:.2f} MB (from {os.path.basename(picked)})")
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"[export_migan] onnx2tf failed ({exc})", file=sys.stderr)

    try:
        run([sys.executable, "-m", "pip", "install", "--quiet", "onnx-tf"])
        saved = os.path.join(work, "migan_saved_model")
        run([sys.executable, "-m", "onnx_tf", "-i", quant, "-o", saved])
        run([sys.executable, "-c",
             "import tensorflow as tf, sys;"
             "c=tf.lite.TFLiteConverter.from_saved_model(sys.argv[1]);"
             f"open(sys.argv[2],'wb').write(c.convert())",
             saved, dest])
        print(f"[export_migan] wrote {dest} {os.path.getsize(dest) / 1e6:.2f} MB")
        return 0
    except Exception as exc:  # noqa: BLE001
        print(f"[export_migan] onnx-tf failed ({exc}); no inpaint model", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
