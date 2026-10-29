import json, sys
import numpy as np
import onnxruntime as ort
from PIL import Image

MODEL = "probe/detector.onnx"
sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
print("INPUTS:", [(i.name, i.type, i.shape) for i in sess.get_inputs()])
print("OUTPUTS:", [(o.name, o.type, o.shape) for o in sess.get_outputs()])

im = Image.open("probe/frieren.jpg").convert("RGB").resize((640, 640))
x = (np.asarray(im, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]

feeds = {}
for i in sess.get_inputs():
    if i.type == "tensor(int64)":
        feeds[i.name] = np.array([[640, 640]], dtype=np.int64)
    else:
        feeds[i.name] = x.astype(np.float32)

outs = sess.run(None, feeds)
for o, v in zip(sess.get_outputs(), outs):
    print("OUT", o.name, v.shape, v.dtype, "min=%.4f max=%.4f" % (float(v.min()), float(v.max())))
    if v.ndim == 3:
        a = v[0]
        print("  low var dims:", [float(a[..., i].std()) for i in range(a.shape[-1])])
        print("  row0:", np.round(a[0], 4).tolist())
        np.save("probe/out_%s.npy" % o.name, v)
