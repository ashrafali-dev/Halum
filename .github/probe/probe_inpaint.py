import os, numpy as np, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from PIL import Image

M = "probe/migan.onnx"
s = ort.InferenceSession(M, providers=["CPUExecutionProvider"])
print("INPUTS:", [(i.name, i.type, i.shape) for i in s.get_inputs()])
print("OUTPUTS:", [(o.name, o.type, o.shape) for o in s.get_outputs()])

img = Image.open("probe/frieren.jpg").convert("RGB").resize((512, 512))
arr = (np.asarray(img, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]
mask = np.zeros((1, 1, 512, 512), dtype=np.float32)
mask[0, 0, 200:300, 200:300] = 1.0
# lens+inpaint concat is standard MIGAN web input (4 ch)
x = np.concatenate([arr, mask], axis=1)
try:
    out = s.run(None, {s.get_inputs()[0].name: x.astype(np.float32)})
    print("FP32 run OK:", [o.shape for o in out])
except Exception as e:
    print("FP32 run ERR:", repr(e)[:200])

try:
    quantize_dynamic(M, "probe/migan_int8.onnx", weight_type=QuantType.QUInt8)
    print("INT8 size MB:", os.path.getsize("probe/migan_int8.onnx") / 1e6)
    s2 = ort.InferenceSession("probe/migan_int8.onnx", providers=["CPUExecutionProvider"])
    out2 = s2.run(None, {s2.get_inputs()[0].name: x.astype(np.float32)})
    print("INT8 run OK:", [o.shape for o in out2])
except Exception as e:
    print("INT8 ERR:", repr(e)[:300])
