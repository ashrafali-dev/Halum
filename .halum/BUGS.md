# Halum — Bugs & known issues

Format: `[OPEN|FIXING|FIXED] short title` — symptom, root cause, fix, run id.

## Fixed

### [FIXING] app died on the first "Translate panel" tap, before OCR
- Session 5 device report: tap Translate → crash. `translateVisiblePanel`
  called `buildPipeline()` on the **UI thread**, which copies the 5.86 MB
  `bubble.tflite` out of assets, SHA-256s it, mmaps it and constructs a TFLite
  `Interpreter`. On the 192 MB heap that is an OOM before any OCR runs.
  Two more allocations sat on the same path: `captureView` grabbed the full
  screen at ARGB_8888 (1080x2400 = 10.4 MB) and the bubble adapter built a
  page-sized mask per bubble (~2.6 MB each) that `PanelPipeline` never reads.
- Fix (`71a9620`): pipeline construction + `process` both run on the worker
  behind a single-flight `busy` flag; captures are downsampled to a 1280px
  long edge and overlay boxes are rescaled back to view space; the mask
  allocation is removed. `runCatching` now logs `PIPE_FAILED` instead of
  silently returning null.
- CI: build/lint/integration green on `a14aed5`. **Not yet confirmed on the
  device** — CI cannot reproduce a heap OOM.

### [FIXED] no way to start or stop Scan / Realtime
- `ScanChapterRunner` and `RealtimeRunner` were written and unit-tested but no
  UI reached them; the mode toggle only set a field.
- Fix: `btnStartStop` toggles `running`. Realtime re-translates the visible
  panel on scroll while running; Scan translates once on Start. The full
  chapter walk still needs a panel list from the page, so Scan is not yet the
  multi-panel pass the runner implements — tracked as OPEN below.

### [FIXED] HalumLog ring buffer: thread-unsafe timestamp, wrong error count
- `SimpleDateFormat` was shared across UI, worker and WebView threads (writes
  can interleave) and `errorCount` counted warnings as errors, then matched a
  `startsWith("[E]")` prefix that the leading timestamp never satisfies.
- Fix: formatting moved inside the ring lock; level tags are `[E]`/`[W]`;
  `errorCount` matches `" [E] "`. `HalumLogTest` covers it.


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

### [FIXING] Bubble inference resizes the model input to rank 1
- Session 6 device log: `bubble.tflite` loads, but the first inference fails
  in PAD with `SizeOfDimension(..., 0) != dims (4 != 1)`.
- Root cause: `Letterboxer` returns a flat `FloatArray`; TFLite Java treats
  primitive arrays as shaped tensors and implicitly resizes the model input
  to rank 1. The graph expects `[1,640,640,3]`, while its PAD nodes expect
  rank-4 paddings `[4,2]`. This reproduces the exact `4 != 1` failure.
- Fix in branch `fix/tflite-rank-preserving-buffers-20261031`: pass input and
  output as `FloatBuffer` so TFLite does not infer or resize tensor shapes.
  Validate the input/output shape contract at startup.
- Also removed the ONNX `auto_pad` rewrite: CI measured max output difference
  324.538, so that rewrite changed model predictions and must not ship.
- Corrected the TFLite checker: builtin PAD requires `[input_rank,2]`; the
  previous checker incorrectly expected a flattened rank-1 paddings tensor.
- Device confirmation: the 2026-10-31 log shows the model loaded with
  input `[1,640,640,3]` and `BUBBLES: 3 scored`; the former first-inference PAD
  crash is no longer present. Mask quality and the full translation path are
  not covered by this detector-only evidence.
- Status: inference fix appears confirmed on-device; current branch CI is
  running after the additional chat-bridge correction.

### [FIXING] WebView chat callbacks time out before any prompt is sent
- Session 7 device log: `js timeout: !!(window.halum && window.halum.ready())`
  repeats for ~30 seconds, ending with `BRIDGE: state=TIMED_OUT attempts=0`,
  `translated=0`, and `placed=0`.
- Root cause: `WebViewChatPage` is constructed lazily after `chatgpt.com` has
  already loaded, then calls `addJavascriptInterface()`. Android does not
  expose a newly added JS interface until the next page load. The injected
  `HalumBridge.onResult()` call therefore cannot reach Kotlin.
- Fix in the same branch: remove the JS interface and receive evaluation
  results through `WebView.evaluateJavascript()`'s `ValueCallback`; this
  works with the already-loaded page and removes the callback ID map.
- Status: code committed; CI and a fresh device run are pending.

### [OPEN] InpaintEngine is never called
- `OverlayView` draws translated text straight over the original artwork;
  nothing fills the bubbles first. AC4 numbers (MI-GAN residual) come from the
  CI harness, not from the app path. Wiring it is Phase 3 work.

### [OPEN] Scan mode is not a chapter pass yet
- Start translates the visible panel once. `ScanChapterRunner` wants a list of
  panels plus a character sheet, which needs page-level panel discovery.

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
