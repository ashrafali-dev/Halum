#!/usr/bin/env python3
"""Export juithealien/manga109-segmentation-bubble (YOLO11n-seg) for Android.

Two problems solved here, both observed in probe CI (see .halum/MODELS.md):

1. INT8 export of a *segment* model needs a **segment** dataset for
   calibration. `coco8.yaml` is a detect dataset and fails with
   "Segment dataset requires equal numbers of boxes and segments".
   We therefore pseudo-label the sample pages with the model itself and
   write a minimal YOLO-seg dataset.

2. Ultralytics >= 8.4 renames format 'tflite' -> 'litert' and the kwarg
   int8=True -> quantize=int8. Support both.

Output: models/bubble.tflite (int8 if possible, else fp16, else fp32).
The file is later packed into assets and gated by tools/report_sizes.py.
"""
import argparse
import os
import shutil
import sys


def build_segment_dataset(model, work_dir):
    """Pseudo-label sample pages into a YOLO segmentation dataset."""
    from PIL import Image
    import numpy as np

    images = sorted(
        f for f in os.listdir(os.path.join(work_dir, "samples"))
        if f.lower().endswith((".jpg", ".png", ".webp"))
    )
    if not images:
        raise SystemExit("no sample images found for calibration")

    ds = os.path.join(work_dir, "segds")
    shutil.rmtree(ds, ignore_errors=True)
    for split in ("train", "val"):
        os.makedirs(os.path.join(ds, "images", split), exist_ok=True)
        os.makedirs(os.path.join(ds, "labels", split), exist_ok=True)

    instances = 0
    for name in images:
        src = os.path.join(work_dir, "samples", name)
        img = Image.open(src).convert("RGB")
        W, H = img.size
        r = model.predict(np.asarray(img), imgsz=640, conf=0.25, retina_masks=True, verbose=False)[0]
        lines = []
        if r.masks is not None and len(r.boxes):
            boxes = r.boxes.xyxy.cpu().numpy()
            for box, poly in zip(boxes, r.masks.xy):
                if len(poly) < 3:
                    continue
                cx, cy = (box[0] + box[2]) / 2 / W, (box[1] + box[3]) / 2 / H
                bw, bh = (box[2] - box[0]) / W, (box[3] - box[1]) / H
                pts = " ".join(f"{x / W:.6f} {y / H:.6f}" for x, y in poly)
                lines.append(f"0 {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f} {pts}")
        if not lines:
            continue  # unlabelled pages make bad calibration set
        for split in ("train", "val"):
            img.save(os.path.join(ds, "images", split, name))
            with open(os.path.join(ds, "labels", split, os.path.splitext(name)[0] + ".txt"), "w") as f:
                f.write("\n".join(lines))
        instances += len(lines)

    if instances == 0:
        raise SystemExit("pseudo-labelling produced zero instances; cannot calibrate")

    with open(os.path.join(ds, "data.yaml"), "w") as f:
        f.write(
            f"path: {os.path.abspath(ds)}\n"
            "train: images/train\n"
            "val: images/val\n"
            "names:\n  0: balloon\n"
        )
    print(f"[export_bubble] calibration dataset: {instances} instances")
    return os.path.join(ds, "data.yaml")


def try_export(model, data_yaml, fmt, extra, dest):
    """Export with kwargs the installed ultralytics understands; may raise."""
    import inspect
    sig = inspect.signature(model.export)
    kwargs = {k: v for k, v in extra.items() if k in sig.parameters}
    if "data" in sig.parameters:
        kwargs["data"] = data_yaml
    kwargs["imgsz"] = 640
    kwargs["format"] = fmt
    print(f"[export_bubble] export format={fmt} kwargs={sorted(kwargs)}")
    path = model.export(**kwargs)
    size = os.path.getsize(path)
    shutil.copyfile(path, dest)
    print(f"[export_bubble] wrote {dest} {size / 1e6:.2f} MB")
    return size


def litert_quantize_kwarg(model):
    """Ultralytics >= 8.4.83: 'quantize' replaced 'int8'."""
    import inspect
    params = inspect.signature(model.export).parameters
    return "quantize" in params


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pt", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--work", required=True)
    args = ap.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.pt)
    if model.task != "segment":
        raise SystemExit(f"unexpected task {model.task!r}; expected 'segment'")

    data_yaml = build_segment_dataset(model, args.work)

    fmt = "litert" if "litert" in getattr(YOLO, "supported_formats", lambda: [])() else "tflite"
    int8_kw = {"quantize": "int8"} if litert_quantize_kwarg(model) else {"int8": True}

    dest = os.path.join(args.out, "bubble.tflite")
    attempts = [
        (fmt, int8_kw),
        (fmt, {"half": True}),
        ("tflite", {}),
        ("onnx", {}),
    ]
    for fmt_try, extra in attempts:
        try:
            try_export(model, data_yaml, fmt_try, extra, dest)
            if fmt_try == "onnx":
                print("[export_bubble] WARNING: only ONNX export succeeded; "
                      "bubble.tflite is NOT usable by the app", file=sys.stderr)
                os.remove(dest)
                return 1
            return 0
        except Exception as exc:  # noqa: BLE001 - we are probing formats
            print(f"[export_bubble] {fmt_try} {extra} failed: {exc}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
