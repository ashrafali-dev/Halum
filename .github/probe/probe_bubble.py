import os, numpy as np
from ultralytics import YOLO
from PIL import Image, ImageDraw

m = YOLO("probe/best.pt")
print("TASK:", m.task, "NAMES:", m.names)
im = Image.open("probe/frieren.jpg").convert("RGB")
print("image size:", im.size)
r = m.predict(np.asarray(im), imgsz=640, conf=0.25, verbose=False)[0]
print("detections:", len(r.boxes))
if len(r.boxes):
    b = r.boxes.xyxy.cpu().numpy()
    s = r.boxes.conf.cpu().numpy()
    c = r.boxes.cls.cpu().numpy()
    for i in range(len(b)):
        x1,y1,x2,y2 = b[i]
        print("  box%d cls=%d conf=%.3f w=%.0f h=%.0f" % (i, c[i], s[i], x2-x1, y2-y1))
    print("masks shape:", None if r.masks is None else r.masks.data.shape)
    small = sum(1 for i in range(len(b)) if (b[i][2]-b[i][0]) < 60)
    print("small(<60px wide):", small)

# measure FPS-ish cost
import time
t=time.time()
for _ in range(3): m.predict(np.asarray(im), imgsz=640, conf=0.25, verbose=False)
print("avg infer ms (CPU):", round((time.time()-t)/3*1000))

# export int8 tflite
try:
    out = m.export(format="tflite", int8=True, imgsz=640, data="coco8.yaml")
    print("TFLITE:", out, os.path.getsize(out)/1e6, "MB")
    out16 = m.export(format="tflite", half=True, imgsz=640)
    print("TFLITE FP16:", out16, os.path.getsize(out16)/1e6, "MB")
    outo = m.export(format="onnx", imgsz=640)
    print("ONNX:", outo, os.path.getsize(outo)/1e6, "MB")
except Exception as e:
    print("EXPORT ERR:", repr(e)[:400])
