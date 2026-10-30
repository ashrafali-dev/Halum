# Halum — Progress

> **Read this first.** It is the single source of truth for resuming.
> Update it before every session ends and on every milestone commit.

**Last updated:** 2026-10-30 (session 2)
**Branch:** `main`
**Current phase:** 0 — skeleton green-up (blocking phase 1)

## Snapshot

| area | state |
|---|---|
| `.halum/` memory files | done — keep current |
| Gradle skeleton + wrapper | committed (`a351925`) — never verified green |
| App module + MainActivity stub (two WebViews, splitter) | committed, compiles in CI |
| CI `build.yml` | **green** (40988332274) — APK 58.1 MB, bubble.tflite 5.86 MB |
| CI `lint.yml` + `format.yml` | **green** (format reflowed main in f6ec1ab) |
| CI `integration-test.yml` | harness works; red only on AC3 assertion |
| CI `memory-test.yml` | never observed to run/pass |
| Bubble export (`tools/export_bubble.py`) | **works**: onnx2tf 2.6.9 → float16 |
| Bubble reference detector + AC3 boxes (`fixtures/pages.json`) | 36 boxes, 4 pages |
| MI-GAN export (`tools/export_migan.py`) | written, never run in CI (needs `MIGAN_ONNX_URL`) |
| Core Kotlin (pure JVM: lens/split/placement/bridge) | not started |
| Android engines (Lens/TFLite/OpenCV/Overlay) | not started |
| Orchestration + full UI | not started |
| `fixtures/lens_ko.bin`, `fixtures/lens_ja.bin` | not captured |
| Tag `v0.1.0` | not reached |

## What works

- Debug APK builds in CI (58.1 MB, armeabi-v7a present, size gates pass) and
  **runs** on the target device (user installed it 2026-10-30).
- Skeleton: Gradle 8.9 wrapper, version catalog, `app` module, manifests,
  `MainActivity` (MAIN + CHAT WebViews, draggable splitter, URL prompt),
  `HalumApp`, `util/Log` + JVM unit test.
- Model pipeline: HF download → Ultralytics ONNX → onnx2tf 2.6.9 FP16 →
  `bubble.tflite` 5.86 MB in the APK; cached via `.cache/models`.
- `tools/integration_test.py`: bubble detection via onnxruntime with
  sigmoid + NMS; writes `build/integration/bubble_ref.json`.
- Real counts, verified in CI: frieren 7 (probe agreement), motl 7,
  wha 12, ajin 5.

## What is broken / blocked

1. **No app functionality yet beyond browsing two WebViews.** Lens, bubbles,
   translate, inpaint, overlay: none implemented in Kotlin.
2. **AC3 references are self-generated** (same model). Locks regressions,
   is not independent ground truth — see BUGS.md.
3. `app/src/main/assets/models/` has only `bubble.tflite`; no inpaint model
   (`MIGAN_ONNX_URL` empty).
4. `memory-test.yml` has never been exercised.
5. No `fixtures/lens_*.bin` — AC2 cannot start.

## What is next (exact order)

1. Confirm integration-test is green now that `expected` boxes exist
   (run after 2026-10-30 08:10). Then fix anything red.
2. **Phase 1 core** (`app/src/main/java/app/halum/core/`, pure JVM, JUnit
   tests in `app/src/test/.../core/`):
   a. `lens/LensProtocol.kt` — decoder over `fixtures/lens_*.bin`, asserts
      text/coords (AC2), plus a parser test over a recorded JSON sample.
   b. `text/Splitter.kt` — D6 rules: 8 blocks / 400 chars per sub-batch,
      order preserved.
   c. `bridge/ChatBridge.kt` state machine with a fake WebView (AC5).
   Re-add `--tests 'app.halum.integration.*'` to integration-test.yml only
   when such a package exists.
3. Records Lens fixtures: `workflow_dispatch` job hits Lens, uploads
   `lens_ko.bin`/`lens_ja.bin` as artifact, commit them to `fixtures/`.
4. Phase 2 engines (Lens client, BubbleDetector TFLite, InpaintEngine,
   Overlay), then UI wiring, then `lens-canary.yml`, then `v0.1.0`.

## Open questions for the user (blocking, ask before spending CI time)

- `MIGAN_ONNX_URL` is empty → no inpaint model. Supply a mirror URL, or
  approve shipping D4 fallback-only (Telea + ring-colour fill).
- `HF_TOKEN` is passed to export but the bubble repo is public; drop the
  secret if unnecessary.

## Acceptance status

| # | criterion | status |
|---|---|---|
| AC1 | 4 required workflows pass | build+lint green; integration + memory-test pending |
| AC2 | Lens replay asserts decoder output | not started |
| AC3 | bubble recall >= 80% | harness ready, 36 refs; run pending |
| AC4 | inpaint residual (scoped, D14.3) | not started |
| AC5 | bridge state machine streaming/timeout/cancel | not started |
| AC6 | scan 20–40 panels < 5 min | not started |
| AC7 | realtime 1 panel < 5 s | not started |
| AC8 | overlay persists until scroll | not started |
| AC9 | small bubbles never merged | not started |
| AC10 | APK <= 60 MB, models <= 35 MB, armeabi-v7a | not started |
| AC11 | 6 memory files current | yes — updated session 2 |
