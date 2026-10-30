# Halum — Plan

Phases are ordered so that **each phase leaves a green required check**.
A phase is only "done" when its acceptance lines are true in CI.

Legend: `[ ]` not started · `[~]` in progress · `[x]` done (green in CI)

## Phase 0 — Memory + skeleton

- [ ] `.halum/` 6 files committed
- [ ] `settings.gradle.kts`, root `build.gradle.kts`, `libs.versions.toml`
- [ ] Gradle wrapper (8.9)
- [ ] `app/` module: `AndroidManifest.xml`, `MainActivity` stub with two WebViews
- [ ] `.gitignore` (build/, `local.properties`, `*.tflite`, `*.onnx`, `*.pt`, `.gradle/`)
- [ ] `LICENSE` (GPL-3.0 full text), `NOTICE.md` skeleton, `README.md`
- [ ] `build.yml` + `lint.yml` + placeholder `integration-test.yml`,
      `memory-test.yml`, `lens-canary.yml`
- **Done when:** `build.yml` green (debug APK + unit tests), `lint.yml` green.

## Phase 1 — Pure-Kotlin core (JVM-testable, no Android)

Everything that can be unit-tested without an emulator lives here under
`app/src/main/java/app/halum/core/**` with zero Android imports.

- [ ] `Proto.kt` — minimal protobuf wire reader/writer (varint, len-delimited)
- [ ] `LensProtocol.kt` — request encoder + response decoder
      (`OcrBlock(text, bbox, script, conf)`)
- [ ] `ResponseParser.kt` — `[N]` regex, positional fallback, partial report
- [ ] `BatchPlanner.kt` — 8-block / 400-char splitter, order preserving (D6)
- [ ] `PromptBuilder.kt` — preamble verbatim (D10) + block numbering
- [ ] `ChatBridge.kt` + `ChatPage` interface + 6-state machine, timeouts,
      cancellation (D14.11)
- [ ] `BubbleClassifier.kt` — `isSmall` rule (D11); never merge small+large
- [ ] `InpaintRouter.kt` — method choice by mask area (D4)
- [ ] `TextFitter.kt` — font shrink 8/10 sp, ellipsis rule
- [ ] Unit tests for all of the above
- **Done when:** `testDebugUnitTest` green.

## Phase 2 — Android image engines

- [ ] `ModelStore` — asset copy + sha256 versioning + mmap (D14.13)
- [ ] `BubbleDetector` — TFLite YOLO11n-seg INT8 post-processing (decode
      `(1,37,8400)`, mask proto 32×160×160, proto→full-res mask, letterbox undo)
- [ ] `InpaintEngine` — OpenCV Telea, MI-GAN TFLite, solid fill; fill-ratio
      assertion >= 0.90; logs `INPAINT:`
- [ ] `LensOcrClient` — `HttpURLConnection` + cookie jar, retry, rate limit,
      Behind `LensTransport` (http | replay) for CI
- [ ] `OverlayView` — canvas, rounded path, lifecycle per D9; logs `OVERLAY_*`
- [ ] `WebViewChatPage` — implements `ChatPage` over the CHAT WebView
- **Done when:** `integration-test.yml` passes: Lens replay, bubble recall
  >= 80%, small bubbles separate, inpaint assertions, bridge mock, pipeline JSON.

## Phase 3 — Orchestration + UI

- [ ] `PanelPipeline` — shared per-panel steps
- [ ] `ScanChapterRunner` — chapter loop, chapter prompt, progress callback
- [ ] `RealtimeRunner` — visible panel, latest-wins, cancel on scroll
- [ ] `MainActivity` — splitter, top bar toggle, progress UI, `onTrimMemory`
- [ ] `ScanChapterService`-less background executor (no service; app-lifetime)
- **Done when:** `memory-test.yml` passes 10-min scripted scan clean.

## Phase 4 — Tooling + fixtures

- [ ] `tools/fetch_models.sh` (pseudo-label calibrate → litert/tflite int8;
      MI-GAN quant → TFLite; writes `app/src/main/assets/models/`)
- [ ] `tools/integration_test.py` (all checks in D12)
- [ ] `tools/capture_lens_fixture.md` (one-time manual procedure)
- [ ] fixtures: sample pages + labels; `lens_ja.bin`, `lens_ko.bin`
- [ ] `tools/report_sizes.py` — APK / model size gates

## Phase 5 — Hardening

- [ ] Rate limiting + backoff (Lens 60/min, bridge retries)
- [ ] Crash-safe logging (logcat tags exactly as in spec)
- [ ] `lens-canary.yml` issue creation via `gh`
- [ ] NOTICE.md complete for every ported file
- [ ] Tag `v0.1.0`

## Known risks

| risk | mitigation |
|---|---|
| TFLite INT8 export of YOLO11n-seg fails in CI | FP16 TFLite (~6 MB) is acceptable under budget; try FP16 first, INT8 second |
| `armeabi-v7a` TFLite + NNAPI quirks on A136B | benchmark in memory-test; fallback to 4-thread XNNPACK only |
| MI-GAN TFLite conversion fails | float32 TFLite, else solid-fill-only mode |
| Lens fixture has no real capture | synthetic protobufs (D14.8), decoder still tested |
| ChatWebView selectors change | platform adapter registry + canary of selectors; Generic adapter with baseline observer |
| 192 MB heap OOM on 512×512 inpainting | process panels one at a time, recycle bitmaps, `inPreferredConfig=RGB_565` for inpaint input where possible |
| Android emulator in CI is slow (15–25 min) | memory-test is `workflow_dispatch` + nightly, not on every PR |
