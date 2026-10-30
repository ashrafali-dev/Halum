# Halum — Bugs & known issues

Format: `[OPEN|FIXING|FIXED] short title` — symptom, root cause, fix, run id.

## Fixed

_(none yet)_

### [FIXED] integration-test red: `tools/integration_test.py` missing
- Run 40988332235: `python3: can't open file tools/integration_test.py`.
  The workflow referenced a file nobody wrote.
- Fix (session 2): harness added. Two follow-up bugs in it, both fixed:
  reference ONNX path (run 40989289071 looked in `app/.cache`; now it walks
  up to the dir containing `.cache`), and **no NMS**: ONNX exports carry no
  NMS, so frieren reported 70 boxes for 7 bubbles. Sigmoid was applied on
  already-probability scores and is now conditional.
- Verified: run 40989791158 → frieren 7 (matches probe), motl 7, wha 12,
  ajin 5.

## Open

### [OPEN] AC3 references are self-generated, not hand-annotated
- `fixtures/pages.json` `expected` boxes come from the same model. That
  locks the detector against regressions but is not an independent recall
  ground truth. To strengthen AC3, hand-annotate one page (wha is the
  densest) and note it in CHANGELOG.

### [FIXED] build/integration red: bubble export pip install fails
- Runs 40985772807 (build) and 40985772949 (integration-test), 2026-10-30.
- Symptom: `ERROR: No matching distribution found for onnx2tf-typing`.
- Cause: `tools/export_bubble.py` pinned `onnx2tf==1.26.3` + a package that
  does not exist on PyPI, and assumed TF ≤ 3.10 / `onnx_graphsurgeon`.
- Fix: onnx2tf 2.6.9 (Python ≥ 3.12, no TensorFlow needed — it uses
  `ai-edge-litert`), float16 output from the default `flatbuffer_direct`
  backend. INT8 is not attempted (see entry below).
- Pipeline caches keyed on `hashFiles('tools/export_bubble.py', …)`, so the
  fix invalidates `.cache/models` automatically.

### [OPEN] bubble INT8 not attempted
- Symptom (probe): Ultralytics INT8 calibration needs a segment dataset
  (`len(segments) = 0`).
- Decision: ship float16 (~6 MB target per D3) instead of int8 for now.
  Revisit only if APK size gate (60 MB) or recall (AC3) forces it.

### [OPEN] probe: ultralytics deprecations
- `format='tflite'` → `'litert'`; `int8=True` → `quantize=int8`
  (ultralytics 8.4.193). Fix: support both keys; pin ultralytics version
  in `tools/fetch_models.sh` requirements.

### [OPEN] MI-GAN mask polarity unverified
- The `lens+mask` 4-channel input may treat mask=1 as "hole" or "keep".
- Fix: verify on fixture in Phase 2; add `InpaintEngineTest`
  that asserts the masked pixel mean changes by > 5 and the output stays
  inside the bubble (D14.4).

### [FIXING] lint red: ktlint style in MainActivity/HalumApp/LogSinkTest
- Run 40985772775: 38 `function-signature`, 5 `no-empty-first-line-in-class-body`,
  multiline wrapping, chain continuation, 1 unused import.
- Fix: long lines split (main + test sources), blank first class-body lines
  removed, fully-qualified names replaced with imports. Guard: new
  `format.yml` runs `ktlint --format` on main and commits `[skip ci]`.

### [OPEN] probe run 40979677999 failed
- `FileNotFoundError: probe/frieren.jpg` — workflow fetched
  `frieren_raw.jpg` then renamed inconsistently. Irrelevant now (files
  removed); noted so the fetch script is written carefully.

## Watch list (not bugs)

- Tiny bubbles (< 60 px) absent from the Frieren sample; AC9 unproven until
  a dense-dialogue fixture exists.
- `armeabi-v7a` TFLite delegate coverage must be checked at build time.
