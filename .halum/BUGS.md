# Halum — Bugs & known issues

Format: `[OPEN|FIXING|FIXED] short title` — symptom, root cause, fix, run id.

## Fixed

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

### [FIXED] Lens fixtures claimed to be genuine jspb bytes; they are hand-built
- `fixtures/lens/README.md` and `LensProtocolTest` said the `.bin` files came
  from jspb `serializeBinary()`. They do not: `tools/lens_fixture.py`
  `encode_page` built them, and it emitted the paragraph one level too deep
  (`Layout.1 > 2 > {2:words, 3:box}` instead of `Layout.1 > {2:line, 3:box, 5:lang}`).
- `--check` still passed because it compared our encoder to itself on a
  fixture it had produced; the decode-vs-expected half never saw a line.
- Fix: `encode_page` now emits `TextLine.word` and the paragraph directly
  under the layout, `--check` asserts `decode(encode(decode(x))) == decode(x)`,
  README and test doc comments state the self-built caveat, and AC2 now
  depends on a D2 live capture for independence.

## Open

### [OPEN] AC2 fixtures are self-built, so AC2 is not yet independent
- Both `.bin` files and `LensProtocol` were derived from the same proto
  reading. A wrong field number would still pass. Capture one real response
  (D2: `BuildConfig.HALUM_CAPTURE` → `lens_capture.bin`) and swap it in.

### [OPEN] engine_verify.py never ran before this session's CI
- Written 2026-10-30 session 4; the only validation is a careful read, because
  the Termux device has no Python (D20 forbids local test runs). If a check
  fails its first run, the failure detail lands in `.halum/ENGINE_VERIFICATION.md`
  and `.halum/verify/`; fix that engine before Step 2.

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

### [FIXED] lint red: ktlint, then detekt once ktlint passed
- Runs 40985772775 / 41008957412: 38 ktlint `function-signature` etc.; after
  `format.yml` cleared those, detekt's 6 findings surfaced — it had never run
  because the ktlint step failed first.
- Fix (session 3): refactored `ChatBridge.translate` into `translateBatch` +
  `retryIndividually`, `Proto.skip`/`readLengthDelimited` onto one
  `advance()` bounds check, split `decodeGeometry`/`decodeParagraphText`,
  lifted `ResponseParser.collectByMarker` out of `parse`.
- Verified: lint green on `d9a03b0`.

### [FIXED] bridge painted refusals and stalled on repeated replies
- A refusal passed `ResponseParser`'s positional mode (1 block, 1 line), so
  `translate` returned DONE with the apology as the translation.
- A partial `[N]` reply resent the whole batch `MAX_RETRIES` times, then
  timed out instead of falling back per block.
- `sendAwait` ignored a reply identical to the previous one, so a chatbot
  that repeats its refusal looked silent until the 120 s timeout.
- Fix: refusal check at batch level, markers keep what arrived and only
  missing blocks are re-sent, `newConversation()` makes any non-blank reply
  count as the answer. Verified: integration green on `41008957375`.

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

### [OPEN] HttpTransport passes width/height 0 to UploadBody
- `HttpTransport.post()` calls `UploadBody.build(bytes, 0, 0)`, so the
  `processed_image_dimensions` part is always `0,0`. The Lens request also
  carries real dimensions via `LensProtocol.Request`, so decode works, but the
  multipart dimension hint is wrong. Fix when the capture path lands in Step 4
  testing: thread width/height through `LensTransport.post` or build the body
  in `LensPort`.
