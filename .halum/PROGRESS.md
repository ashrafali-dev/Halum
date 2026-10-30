# Halum — Progress

> **Read this first.** It is the single source of truth for resuming.
> Update it before every session ends and on every milestone commit.

**Last updated:** 2026-10-30 (session 4)
**Branch:** `main`
**Current phase:** D19 Steps 1-4 written and green in CI (`ddb36a7`):
build+lint+integration+format pass. Q1 (bridge adapters), Q2 (editable
prompts), Q3 (3-layer ad block) implemented. Next: on-device sanity,
memory-test.yml, `v0.1.0`.

## Snapshot

| area | state |
|---|---|
| `.halum/` memory files | done — keep current |
| Gradle skeleton + wrapper | committed (`a351925`) — never verified green |
| App module + MainActivity stub (two WebViews, splitter) | committed, compiles in CI |
| CI `build.yml` | **green** (40988332274) — APK 58.1 MB, bubble.tflite 5.86 MB |
| CI `lint.yml` + `format.yml` | **green** — ktlint *and* detekt clean since `d9a03b0` |
| CI `integration-test.yml` | **green** (41008957375) — Lens replay + core tests |
| CI `memory-test.yml` | never observed to run/pass |
| Bubble export (`tools/export_bubble.py`) | **works**: onnx2tf 2.6.9 → float16 |
| Bubble reference detector + AC3 boxes (`fixtures/pages.json`) | 36 boxes, 4 pages |
| MI-GAN export (`tools/export_migan.py`) | written, never run in CI (needs `MIGAN_ONNX_URL`) |
| Core Kotlin (pure JVM: lens/split/placement/bridge) | not started |
| Android engines (Lens/TFLite/OpenCV/Overlay) | not started |
| Orchestration + full UI | not started |
| `fixtures/lens_ko.bin`, `fixtures/lens_ja.bin` | committed, self-built (not a live capture) — decode + round-trip green |
| Core JVM: `Proto`, `LensProtocol`, `BatchPlanner`, `PromptBuilder`, `ResponseParser`, `ChatBridge` | done, 30 unit tests green |
| Engine verification (`tools/engine_verify.py`, D18) | **DONE — 6/6 green** (run 41027485611) |
| `ChatBridgeVerificationTest` + `fixtures/chatbot/sessions.json` | green |
| MI-GAN fetch (`MIGAN_URLS` in `fetch_models.sh`) | downloads + quantizes; 14.7 MB tflite into full flavor |
| Flavors (D21) | `default` (Telea/solid-fill) + `full` (MI-GAN), both ABIs |
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

1. **Engines are written but not wired.** Lens/bubble/inpaint/overlay classes
   exist; `MainActivity` only browses two WebViews (Phase 3 pipeline missing).
2. **AC3 references are self-generated** (same model). Locks regressions,
   is not independent ground truth — see BUGS.md.
3. MI-GAN now has documented download URLs in `fetch_models.sh`; whether the
   export succeeds in CI is unverified until the next integration run.
4. `memory-test.yml` has never been exercised.
5. AC2 is self-referential: the `.bin` fixtures were built by our own
   encoder from the same proto reading as the decoder. A live capture (D2)
   is needed before AC2 means anything.
6. **No Python locally** (Termux) and no android-35 SDK, so `engine_verify.py`
   and the Kotlin changes can only be validated by CI (D20).

## What is next (exact order, D19)

1. ~~Step 1 — engine verification~~ **DONE (6/6).**
2. ~~Step 2 — PanelPipeline~~ **done, green** (`PanelPipelineTest`, `PanelPipelineFixtureTest`).
3. ~~Step 3 — ScanChapterRunner + RealtimeRunner~~ **done, green** (`RunnersTest`).
4. ~~Step 4 — MainActivity wiring~~ **done**: capture, overlay, dismiss-on-scroll,
   long-press edit, settings, both WebViews. AC8 covered by overlay tests.
5. ~~Q1/Q2/Q3~~ **done**: adapters, editable prompts (3 levels), 3-layer ad block
   (`AdBlockRulesTest`, `PromptTemplateTest`).
6. **Remaining**: on-device smoke test, `memory-test.yml` first run,
   hand-annotate `wha.jpg` (real AC3), D2 Lens capture (AC2), then tag `v0.1.0`.

Independently still open: hand-annotate `wha.jpg` for real AC3 ground truth
(D18 only locks regressions), and a D2 Lens live capture for AC2.

## Open questions for the user (blocking, ask before spending CI time)

- `MIGAN_ONNX_URL` is empty → no inpaint model. Supply a mirror URL, or
  approve shipping D4 fallback-only (Telea + ring-colour fill).
- `HF_TOKEN` is passed to export but the bubble repo is public; drop the
  secret if unnecessary.

## Acceptance status

| # | criterion | status |
|---|---|---|
| AC1 | 4 required workflows pass | build+lint+integration green; memory-test pending; Step 1 verification added to integration |
| AC2 | Lens replay asserts decoder output | replay green, but self-built fixtures — not independent yet |
| AC3 | bubble recall >= 80% | 1.000 in run 40990108077; engine_verify re-asserts + small-bubble count |
| AC4 | inpaint residual (scoped, D14.3) | green: MI-GAN change_min 69.41, fill 1.000, flat 10.2 over 4 pages |
| AC5 | bridge state machine streaming/timeout/cancel | green: unit tests + `ChatBridgeVerificationTest` fixture replay |
| AC6 | scan 20–40 panels < 5 min | not started |
| AC7 | realtime 1 panel < 5 s | not started |
| AC8 | overlay persists until scroll | not started |
| AC9 | small bubbles never merged | not started |
| AC10 | APK <= 60 MB, models <= 35 MB, armeabi-v7a | default flavor 45.8 MB v7a-only before D21; both flavors gated in CI |
| AC11 | 6 memory files current | yes — updated session 3 |
