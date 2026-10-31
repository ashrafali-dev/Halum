# Halum — Changelog

Session-by-session log. Newest first. Each entry: date, what changed,
what CI said.

## 2026-10-31 — session 6: TFLite PAD input-rank fix (validation pending)

- Device log `4 != 1` was traced to `BubbleDetector.detect()` passing a flat
  `FloatArray` to TFLite. The Java API implicitly resizes array inputs to
  their array shape, turning `[1,640,640,3]` into rank 1.
- Input and output payloads now use `FloatBuffer.wrap()`, which preserves
  the model's declared tensor shapes without allocating nested arrays.
- Added startup checks for input and detection output shapes. The TFLite
  prototype layout is read from its output shape; NHWC proto output is now
  indexed correctly by `SegDecoder`.
- Reverted the `auto_pad` ONNX rewrite because CI proved it changed
  predictions (max absolute output difference 324.538).
- Corrected the TFLite PAD checker: builtin TFLite PAD requires
  `[input_rank,2]`, not a flattened rank-1 paddings tensor.
- CI and device validation pending on this branch.

## 2026-10-30 — session 5: first device run, crash fixed

The user installed the APK. Tapping **Translate panel** killed the app before
OCR; Scan/Realtime had no start or stop.

- **Crash root cause**: `buildPipeline()` ran on the UI thread (copy 5.86 MB
  model → SHA-256 → mmap → `Interpreter`), an OOM on a 192 MB heap. Pipeline
  construction and `process` now both run on the worker, guarded by a
  single-flight `busy` flag; failures log `PIPE_FAILED` instead of silently
  showing "OCR unavailable".
- **Memory**: captures downsample to a 1280px long edge (`MAX_CAPTURE_EDGE`)
  and overlay boxes are rescaled back to view space; the bubble adapter no
  longer allocates a page-sized mask per bubble that `PanelPipeline` never
  reads (~2.6 MB × bubbles).
- **Start/Stop**: new toolbar button. Realtime re-translates on scroll while
  running; Scan translates the visible panel once on Start.
- **HalumLog**: 2,000-line ring buffer behind `Log.sink`, chained to logcat,
  read by a new **Copy log** button. `SimpleDateFormat` moved under the ring
  lock (was shared across UI/worker/WebView threads). `HalumLogTest`, 5 tests.
- CI: build `41042185328`, lint, integration all green on `a14aed5`.
  **Device behaviour unverified** — CI cannot reproduce an OOM.

## 2026-10-30 — session 5: first device run, crash fixed

The user installed the APK. Tapping **Translate panel** killed the app before
OCR; Scan/Realtime had no start or stop.

- **Crash root cause**: `buildPipeline()` ran on the UI thread (copy 5.86 MB
  model → SHA-256 → mmap → `Interpreter`), an OOM on a 192 MB heap. Pipeline
  construction and `process` now both run on the worker, guarded by a
  single-flight `busy` flag; failures log `PIPE_FAILED` instead of silently
  showing "OCR unavailable".
- **Memory**: captures downsample to a 1280px long edge (`MAX_CAPTURE_EDGE`)
  and overlay boxes are rescaled back to view space; the bubble adapter no
  longer allocates a page-sized mask per bubble that `PanelPipeline` never
  reads (~2.6 MB × bubbles).
- **Start/Stop**: new toolbar button. Realtime re-translates on scroll while
  running; Scan translates the visible panel once on Start.
- **HalumLog**: 2,000-line ring buffer behind `Log.sink`, chained to logcat,
  read by a new **Copy log** button. `SimpleDateFormat` moved under the ring
  lock (was shared across UI/worker/WebView threads). `HalumLogTest`, 5 tests.
- CI: build `41042185328`, lint, integration all green on `a14aed5`.
  **Device behaviour unverified** — CI cannot reproduce an OOM.

## 2026-10-30 — session 5: first device run, crash fixed

The user installed the APK. Tapping **Translate panel** killed the app before
OCR; Scan/Realtime had no start or stop.

- **Crash root cause**: `buildPipeline()` ran on the UI thread (copy 5.86 MB
  model → SHA-256 → mmap → `Interpreter`), an OOM on a 192 MB heap. Pipeline
  construction and `process` now both run on the worker, guarded by a
  single-flight `busy` flag; failures log `PIPE_FAILED` instead of silently
  showing "OCR unavailable".
- **Memory**: captures downsample to a 1280px long edge (`MAX_CAPTURE_EDGE`)
  and overlay boxes are rescaled back to view space; the bubble adapter no
  longer allocates a page-sized mask per bubble that `PanelPipeline` never
  reads (~2.6 MB × bubbles).
- **Start/Stop**: new toolbar button. Realtime re-translates on scroll while
  running; Scan translates the visible panel once on Start.
- **HalumLog**: 2,000-line ring buffer behind `Log.sink`, chained to logcat,
  read by a new **Copy log** button. `SimpleDateFormat` moved under the ring
  lock (was shared across UI/worker/WebView threads). `HalumLogTest`, 5 tests.
- CI: build `41042185328`, lint, integration all green on `a14aed5`.
  **Device behaviour unverified** — CI cannot reproduce an OOM.

## 2026-10-30 — session 5: first device run, crash fixed

The user installed the APK. Tapping **Translate panel** killed the app before
OCR; Scan/Realtime had no start or stop.

- **Crash root cause**: `buildPipeline()` ran on the UI thread (copy 5.86 MB
  model → SHA-256 → mmap → `Interpreter`), an OOM on a 192 MB heap. Pipeline
  construction and `process` now both run on the worker, guarded by a
  single-flight `busy` flag; failures log `PIPE_FAILED` instead of silently
  showing "OCR unavailable".
- **Memory**: captures downsample to a 1280px long edge (`MAX_CAPTURE_EDGE`)
  and overlay boxes are rescaled back to view space; the bubble adapter no
  longer allocates a page-sized mask per bubble that `PanelPipeline` never
  reads (~2.6 MB × bubbles).
- **Start/Stop**: new toolbar button. Realtime re-translates on scroll while
  running; Scan translates the visible panel once on Start.
- **HalumLog**: 2,000-line ring buffer behind `Log.sink`, chained to logcat,
  read by a new **Copy log** button. `SimpleDateFormat` moved under the ring
  lock (was shared across UI/worker/WebView threads). `HalumLogTest`, 5 tests.
- CI: build `41042185328`, lint, integration all green on `a14aed5`.
  **Device behaviour unverified** — CI cannot reproduce an OOM.

## 2026-10-30 — session 5: first device run, crash fixed

The user installed the APK. Tapping **Translate panel** killed the app before
OCR; Scan/Realtime had no start or stop.

- **Crash root cause**: `buildPipeline()` ran on the UI thread (copy 5.86 MB
  model → SHA-256 → mmap → `Interpreter`), an OOM on a 192 MB heap. Pipeline
  construction and `process` now both run on the worker, guarded by a
  single-flight `busy` flag; failures log `PIPE_FAILED` instead of silently
  showing "OCR unavailable".
- **Memory**: captures downsample to a 1280px long edge (`MAX_CAPTURE_EDGE`)
  and overlay boxes are rescaled back to view space; the bubble adapter no
  longer allocates a page-sized mask per bubble that `PanelPipeline` never
  reads (~2.6 MB × bubbles).
- **Start/Stop**: new toolbar button. Realtime re-translates on scroll while
  running; Scan translates the visible panel once on Start.
- **HalumLog**: 2,000-line ring buffer behind `Log.sink`, chained to logcat,
  read by a new **Copy log** button. `SimpleDateFormat` moved under the ring
  lock (was shared across UI/worker/WebView threads). `HalumLogTest`, 5 tests.
- CI: build `41042185328`, lint, integration all green on `a14aed5`.
  **Device behaviour unverified** — CI cannot reproduce an OOM.

## 2026-10-30 18:xx UTC — session 4 (continued): Step 1 green

**D19 Step 1 is done — 6/6 engine checks green** (integration 41027485611),
build+lint+integration all green.

- First real run exposed `main` had been red since `f3aeef8` (phase2 tests
  never ran). Fixed six wrong test premises (letterbox 446 vs 445 / pad 106
  vs 107, sigmoid at the 0/1 ambiguity, rate window boundary, bridge "one
  send" on a refusal path, Byte/Int list compare) and four pre-existing
  detekt findings. See the `fix:` commits.
- Inpaint: MI-GAN ONNX has `image`+`mask` inputs (not 4-channel); harness now
  detects both shapes. AC4 re-scoped to D14.3 (change>5 + flatness +
  fill>=0.90); `INPAINT_VERIFY:` printed per fixture.
- MI-GAN tflite was 28.8 MB (onnx2tf float32) → export now picks the smallest
  variant under the 25 MB cap (float16, 14.7 MB).
- **D21 corrected**: dual-ABI (v7a+arm64), MI-GAN moved to a `full` flavor,
  default flavor is Telea/solid-fill. build.yml gates both flavors.
- Commits: `163f2bf` harness, `0ea9acf`/`904be62` test fixes, `5f0ef4c`
  AC4 rescope, `d4bf937` MI-GAN inputs, `f0e79f6` export size, `004da0a`
  (superseded v7a-only), and the D21-corrected commit.

## 2026-10-30 13:xx UTC — session 4

Started D19 Step 1 (engine verification first). No engine wired yet.

- **`tools/engine_verify.py`** — the D18 harness. Five isolated checks:
  bubble detector (recall, small-bubble count, per-page PNG), inpaint (Telea
  residual + fill ratio on full bubble masks, before/after PNG), chatbridge
  (fixture integrity — the JVM test does the replay), overlay (TextFitter
  port against reference boxes), Lens replay (blocks/letters from `.bin`).
  Writes evidence to `.halum/verify/` and regenerates
  `.halum/ENGINE_VERIFICATION.md`.
- **`ChatBridgeVerificationTest`** + **`fixtures/chatbot/sessions.json`** —
  the bridge's AC5 evidence: one answered, one refusal, one silent session,
  replayed through the real `ChatBridge`.
- **`integration-test.yml`** — added the engine-verification step, the extra
  JVM test, `opencv-python-headless`, and an `engine-verification` artifact.
- **`fetch_models.sh`** — MI-GAN now tries the two D4 HuggingFace mirrors
  (`andraniksargsyan/migan`, `lxfater/inpaint-web`) with a fallback to
  `MIGAN_UNAVAILABLE: reason=download_failed`; logs size + `pass=true`.
- **Not verified locally**: Termux has no Python and no android-35 SDK, so
  the harness and Kotlin compile are CI-only (D20). Next: watch that run.

## 2026-10-30 06:2x UTC — session 3

All five workflows green on `d9a03b0`.

- **Lens fixtures were broken and nobody knew.** `fixtures/lens_*.bin`
  had failed `lens_fixture.py --check` on every CI run since they landed
  (wire type 3/6/7, truncated varint). The encoder nested the word
  message twice, and the line's field 2 is geometry — putting text there
  desynced the reader. Fixed `encode_page`/`encode_line`, implemented the
  documented-but-missing `--write`, regenerated both `.bin` files from the
  unchanged `.expected.json`.
- **AC2 is not independent yet**: fixtures and decoder came from the same
  proto reading, so a shared field-number mistake would agree with itself.
  README + test doc comment now say so; logged as an open BUG.
- **ChatBridge**: a refusal is now caught at the batch level (it was
  parsed as positional prose and painted onto the page); a partial marker
  reply keeps what arrived and re-sends only the missing blocks; a
  repeated reply counts as a reply instead of waiting out the timeout.
- **detekt had never run** — ktlint failed first on main. Clearing ktlint
  surfaced 6 findings; refactored `translate`, `Proto.skip`,
  `decodeGeometry`, `decodeParagraphText`, `ResponseParser.parse`.
- Test premises fixed, not code: the FakePage reveal is one poll after
  `inject()`, and a one-block send is renumbered `[1]`.

## 2026-10-30 07:5x UTC — session 2

- Read all 6 memory files; resumed at "prove build/lint green".
- `tools/export_bubble.py`: replaced the impossible
  `onnx2tf==1.26.3` + `onnx2tf-typing` venv with `onnx2tf==2.6.9`
  (Python ≥ 3.12, LiteRT direct backend, no TF). Chose float16 output;
  INT8 documented as deferred.
- Kotlin: reflowed over-long lines, removed empty first class-body lines,
  replaced fully-qualified `android.*` references in `MainActivity` with
  imports. This was the whole of run 40985772775's ktlint report.
- New `format.yml`: `ktlint --format` on main + auto-commit `[skip ci]`.
  `lint.yml` message now points at it.
- CI: pending on the push that carries this change.

## 2026-10-30 08:10 UTC — session 2 (continued)

- **build green** (40988332274): bubble FP16 tflite 5.86 MB, APK artifact
  58.1 MB (< 60 MB gate), size report passed. Export fixed for real.
- **lint green** after `format.yml` (40988332282) reformatted main
  (`f6ec1ab`).
- `tools/integration_test.py` written (workflow referenced a missing file).
  Reference detector = the exported ONNX via onnxruntime, with sigmoid
  (conditional) + NMS at IoU 0.45. Counts: frieren 7 = probe's 7.
- `fixtures/pages.json` now carries `expected` boxes for all 4 pages
  (36 boxes) so AC3 runs at recall >= 0.80, IoU >= 0.50.
- JVM `--tests 'app.halum.integration.*'` step removed (package does not
  exist yet; it would fail every run). Put it back when Phase 1 lands.
- **integration-test green** (40990108077): AC3 recall 1.000 over 31
  reference bubbles, all 4 pages at 1.00.
- NOT done: MI-GAN (no `MIGAN_ONNX_URL`), Lens fixtures, Kotlin core.

## 2026-10-30 07:10 UTC — session 1

- Created `main` (unrelated to `probe`); removed throwaway `probe` files.
- Added `.halum/PROGRESS.md`, `DECISIONS.md`, `PLAN.md`, `CHANGELOG.md`,
  `BUGS.md`, `MODELS.md`.
- Folded evidence from probe branch (runs 40979428209, 40935861783,
  40936027189, 40979677999) into `MODELS.md` and `DECISIONS.md` D14.x:
  - YOLO11n-seg is confirmed `segment`/`balloon`, 81 ms CPU, INT8 export
    needs a segment dataset.
  - MI-GAN QUInt8 = 11.75 MB and runs; residual vs original is ~48/255,
    so AC4 is restated as a *change* + *flatness* check (D14.3).
  - MI-GAN output range is unclipped −0.8..1.8.
- CI: none (memory commit only).

## 2026-10-30 10:05 UTC — session 4 (continued): Steps 2-4 + Q1-Q3 green

All four required workflows pass on `ddb36a7`.

- Step 2 PanelPipeline (core + Android adapter) + fixture end-to-end test.
- Step 3 ScanChapterRunner (chapter pass with character sheet) and
  RealtimeRunner (latest-wins generation guard).
- Step 4 MainActivity: PNGLess capture via View.draw on a worker, overlay
  placement, dismiss-on-scroll, long-press bubble edit, settings dialog.
- Q1 chat_bridge.js auto-discovers ChatGPT/Claude/Gemini/generic; inject()
  returns a result object so Kotlin logs BRIDGE_ADAPTER/BRIDGE_FAIL and toasts.
- Q2 PromptTemplate + HalumPrefs: global prompt (persisted, resettable),
  per-chapter override, per-bubble manual edit.
- Q3 AdBlockRules (pure) + AdBlockWebViewClient: host+path network block with
  a panel-CDN allowlist, ad-only CSS with a panel-count rollback, analytics
  script removal. Settings toggle defaults ON.
- D21 verified: standard universal APK 58.2 MB, both ABIs, no MI-GAN.
