import os, shutil, numpy as np
from ultralytics import YOLO
from PIL import Image

OUT = "probe/ds"
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT + "/images/val", exist_ok=True)
os.makedirs(OUT + "/labels/val", exist_ok=True)

m = YOLO("probe/best.pt")
srcs = ["probe/frieren.jpg", "probe/motl_raw.png", "probe/wha_raw.jpg", "probe/ajin_raw.jpg"]
n = 0
for sp in srcs:
    im = Image.open(sp).convert("RGB")
    r = m.predict(np.asarray(im), imgsz=640, conf=0.25, retina_masks=True, verbose=False)[0]
    if r.masks is None or len(r.boxes) == 0:
        continue
    W, H = im.size
    lines = []
    boxes = r.boxes.xyxy.cpu().numpy()
    polys = r.masks.xy
    for b, p in zip(boxes, polys):
        if len(p) < 3:
            continue
        cx, cy, bw, bh = (b[0]+b[2])/2/W, (b[1]+b[3])/2/H, (b[2]-b[0])/W, (b[3]-b[1])/H
        norm = p / np.array([W, H], dtype=np.float32)
        pts = " ".join("%.6f %.6f" % (x, y) for x, y in norm)
        lines.append("0 %.6f %.6f %.6f %.6f %s" % (cx, cy, bw, bh, pts))
    name = os.path.splitext(os.path.basename(sp))[0]
    im.save(OUT + "/images/val/%s.jpg" % name)
    open(OUT + "/labels/val/%s.txt" % name, "w").write("\n".join(lines))
    n += len(lines)
    print(name, len(lines), "instances")

os.makedirs(OUT + "/images/train", exist_ok=True)
shutil.copytree(OUT + "/labels/val", OUT + "/labels/train", dirs_exist_ok=True)
shutil.copytree(OUT + "/images/val", OUT + "/images/train", dirs_exist_ok=True)
open(OUT + "/data.yaml", "w").write("path: %s\nval: images/val\ntrain: images/train\nnames:\n  0: balloon\n" % os.path.abspath(OUT))
print("TOTAL INSTANCES:", n)

for kw in [dict(int8=True), dict(half=True), dict(int8=False, half=False)]:
    try:
        p = m.export(format="onnx", imgsz=640, **kw)
        print("ONNX", kw, p, round(os.path.getsize(p)/1e6, 2), "MB")
    except Exception as e:
        print("ONNX ERR", kw, repr(e)[:200])

for kw in [dict(int8=False, half=True), dict(int8=False, half=False)]:
    try:
        p = m.export(format="tflite", imgsz=640, data=OUT + "/data.yaml", **kw)
        print("TFLITE", kw, p, round(os.path.getsize(p)/1e6, 2), "MB")
    except Exception as e:
        print("TFLITE ERR", kw, repr(e)[:300])

try:
    p = m.export(format="tflite", imgsz=640, int8=True, data=OUT + "/data.yaml")
    print("TFLITE INT8", p, round(os.path.getsize(p)/1e6, 2), "MB")
except Exception as e:
    print("TFLITE INT8 ERR", repr(e)[:300])
