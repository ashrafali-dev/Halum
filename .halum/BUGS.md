# Halum — Bugs & known issues

Format: `[OPEN|FIXING|FIXED] short title` — symptom, root cause, fix, run id.

## Fixed

_(none yet)_

## Open

### [OPEN] probe: `export(format="tflite", int8=True)` fails on detect dataset
- Symptom: `Segment dataset requires equal numbers of boxes and segments,
  but got len(segments) = 0, len(boxes) = 17`.
- Cause: Ultralytics INT8 calibration needs a **segment** dataset.
- Fix planned: pseudo-labelled segment dataset in `tools/fetch_models.sh`
  (DECISIONS D14.1). Not yet verified.

### [OPEN] probe: ultralytics deprecations
- `format='tflite'` → `'litert'`; `int8=True` → `quantize=int8`
  (ultralytics 8.4.193). Fix: support both keys; pin ultralytics version
  in `tools/fetch_models.sh` requirements.

### [OPEN] MI-GAN mask polarity unverified
- The `lens+mask` 4-channel input may treat mask=1 as "hole" or "keep".
- Fix: verify on fixture in Phase 2; add `InpaintEngineTest`
  that asserts the masked pixel mean changes by > 5 and the output stays
  inside the bubble (D14.4).

### [OPEN] probe run 40979677999 failed
- `FileNotFoundError: probe/frieren.jpg` — workflow fetched
  `frieren_raw.jpg` then renamed inconsistently. Irrelevant now (files
  removed); noted so the fetch script is written carefully.

## Watch list (not bugs)

- Tiny bubbles (< 60 px) absent from the Frieren sample; AC9 unproven until
  a dense-dialogue fixture exists.
- `armeabi-v7a` TFLite delegate coverage must be checked at build time.
