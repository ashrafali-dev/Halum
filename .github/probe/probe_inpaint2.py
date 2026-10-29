import numpy as np, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType
from PIL import Image

src = Image.open("probe/frieren.jpg").convert("RGB")
W, H = 512, 512
img = src.resize((W, H))
arr = (np.asarray(img, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]

# synthetic text mask: a few strokes, like a speech bubble with text
mask = np.zeros((1, 1, H, W), dtype=np.float32)
mask[0, 0, 180:200, 160:352] = 1.0
mask[0, 0, 220:240, 160:352] = 1.0
mask[0, 0, 260:280, 160:352] = 1.0
x = np.concatenate([arr, mask], axis=1).astype(np.float32)

def run(path):
    s = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    return s.run(None, {s.get_inputs()[0].name: x})[0]

quantize_dynamic("probe/migan.onnx", "probe/migan_int8.onnx", weight_type=QuantType.QUInt8)
o32, o8 = run("probe/migan.onnx"), run("probe/migan_int8.onnx")
print("out range fp32:", float(o32.min()), float(o32.max()))
print("out range int8:", float(o8.min()), float(o8.max()))

m = mask[0, 0] > 0
def residual(o):
    # "ghost" = how much the original text still shows through, in masked region
    gen = o[0].transpose(1, 2, 0)
    orig = arr[0].transpose(1, 2, 0)
    return float(np.abs(gen[m] - orig[m]).mean() * 255.0)
print("residual fp32 (0-255):", round(residual(o32), 2))
print("residual int8 (0-255):", round(residual(o8), 2))
print("fp32 vs int8 mean abs diff (0-255):", round(float(np.abs(o32 - o8).mean() * 255), 2))
Image.fromarray((np.clip(o8[0].transpose(1,2,0),0,1)*255).astype(np.uint8)).save("probe/migan_int8_out.png")
