#!/usr/bin/env python3
"""Does the shipped bubble.tflite actually allocate, and if not, why?

Every device failure so far happened at Interpreter *creation*, never at
inference:

  conv.cc:361   input_type == kTfLiteFloat32 || kTfLiteUInt8 || ... was not true
  pad.cc:123    SizeOfDimension(op_context->paddings, 0) != op_context->dims (4 != 1)

onnxruntime — what integration_test.py and engine_verify.py use — cannot see
any of this, so CI stayed green while the APK could not load the model. This
runs the real artifact through the real interpreter.

On failure it dumps the offending node and its tensors, because "node 0 (PAD)
failed to prepare" alone does not say which of the 100+ nodes that is or what
shape its paddings tensor has.

Usage: check_tflite.py <bubble.tflite>
Exit 0 only if the model allocates.
"""
import sys


def _load(path):
    # ai-edge-litert is the maintained package; tflite_runtime stopped at
    # Python 3.11 and TensorFlow's lite is the last resort.
    for mod, name in (
        ("ai_edge_litert.interpreter", "Interpreter"),
        ("tflite_runtime.interpreter", "Interpreter"),
        ("tensorflow.lite", "Interpreter"),
    ):
        try:
            module = __import__(mod, fromlist=[name])
            return getattr(module, name)(model_path=path)
        except ImportError:
            continue
    raise ImportError("no LiteRT interpreter (ai-edge-litert / tflite-runtime / tensorflow)")


def dump_node(interp, index: int) -> None:
    """Print one node with the shapes of the tensors it consumes."""
    details = interp.get_tensor_details()
    by_index = {d["index"]: d for d in details}
    try:
        ops = interp._get_ops_details()  # noqa: SLF001 - the only introspection API
    except Exception:  # noqa: BLE001
        print(f"CHECK_TFLITE: node {index}: (no introspection available)")
        return
    if index >= len(ops):
        print(f"CHECK_TFLITE: node {index}: out of range ({len(ops)} ops)")
        return
    op = ops[index]
    print(f"CHECK_TFLITE: node {index}: {op['op_name']}")
    for role, idx in (("inputs", op["inputs"]), ("outputs", op["outputs"])):
        for i, t in enumerate(idx):
            d = by_index.get(int(t))
            if d is None:
                continue
            print(
                f"CHECK_TFLITE:   {role}[{i}] tensor={int(t)} "
                f"shape={d['shape'].tolist()} dtype={d['dtype'].__name__}"
            )
    # PAD/PADV2 take the paddings tensor as input 1; print its values.
    if op["op_name"] in ("PAD", "PADV2", "MIRROR_PAD") and len(op["inputs"]) > 1:
        try:
            interp.allocate_tensors()
            arr = interp.get_tensor(int(op["inputs"][1]))
            print(f"CHECK_TFLITE:   paddings value={arr.tolist()}")
        except Exception:  # noqa: BLE001 - allocation already failed
            pass


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    path = sys.argv[1]

    try:
        interp = _load(path)
    except Exception as e:  # noqa: BLE001 - any failure is the finding
        print(f"CHECK_TFLITE: path={path} allocate=false error={e}")
        return 1

    try:
        interp.allocate_tensors()
    except Exception as e:  # noqa: BLE001
        print(f"CHECK_TFLITE: path={path} allocate_tensors=false error={e}")
        # The message names a node number; show what that node actually is.
        import re

        m = re.search(r"Node number (\d+)", str(e))
        dump_node(interp, int(m.group(1)) if m else 0)
        return 1

    inp = interp.get_input_details()[0]
    outs = interp.get_output_details()
    dtype = inp["dtype"].__name__
    if dtype != "float32":
        print(f"CHECK_TFLITE: path={path} input_dtype={dtype} expected=float32")
        return 1
    if len(outs) != 2:
        print(f"CHECK_TFLITE: path={path} outputs={len(outs)} expected=2")
        return 1

    print(
        f"CHECK_TFLITE: path={path} allocate=true input_shape={inp['shape'].tolist()} "
        f"input_dtype={dtype} outputs={[o['shape'].tolist() for o in outs]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
