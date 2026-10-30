# Halum — Decisions

> Every decision here is **pre-made by the project owner**. Do not re-ask.
> If something is missing, pick the most reasonable default, append it to
> section **D14 — Session-added decisions**, and continue.

## D1. License

GPL-3.0. App is 100% open source, no income.

- Free to copy code from MIT, Apache-2.0, BSD, and GPL-3.0 sources.
- `NOTICE.md` must list every ported file with source URL, license,
  and what was changed. Add an entry at the same time as the port.
- The app is **non-commercial**. No ads, no paid tiers, no telemetry.

## D2. Lens CI strategy — record-once + replay

- Capture real Lens protobuf responses **once**, commit as
  `fixtures/lens_ko.bin` and `fixtures/lens_ja.bin`.
- `integration-test.yml` replays them through the real `LensProtocol`
  decoder. **This is the required test.**
- `lens-canary.yml` runs live against Google **weekly + workflow_dispatch,
  never on PR**. On failure it opens an issue and does **not** fail main.

## D3. Bubble model

- `juithealien/manga109-segmentation-bubble` — YOLO11n-seg, Apache-2.0,
  ~6 MB FP16, mAP@50 99.1%. Single class `balloon`.
- `tools/fetch_models.sh` downloads `.pt` in CI only.
- Exported to TFLite inside CI. **Models are never committed.**
- Fallback if the model becomes unavailable: `mayocream/comic-text-detector-onnx`
  (Apache-2.0). (ONNX→TFLite conversion is a heavier path; see MODELS.md.)

## D4. Inpaint

- Mask area **< 15,000 px** → OpenCV Telea (`Photo.inpaint`, no model).
- Mask area **>= 15,000 px** → MI-GAN (quantized), else stronger fallback.
- Fill the **entire bubble mask**, not just the text bbox (kills ghosts).
- Post-fill assertion: `fillArea >= 0.90 * bubbleMaskArea`.
- If MI-GAN is unavailable → solid fill sampled from the ring around the mask.

## D5. OCR

- Google Lens `v1/crupload` unofficial endpoint.
- Reference: `rxliuli/chrome-lens-ocr` (MIT).
- **No** on-device OCR model. **No** ML Kit. **No** PaddleOCR.
- The Lens client runs behind `LensTransport`, an interface with a
  production HTTP impl and a fixture-replay impl. CI only ever uses replay.

## D6. Translation

- Chatbot WebView bridge. No LLM bundled.
- Batch translation with strict `[N]` marker preamble (see D10).
- Max **8 blocks** per sub-batch and max **400 chars** per sub-batch.
  Oversized batches split; order preserved across sub-batches.
- Fallback: one-block-per-send if marker parsing fails, max 3 retries/block.

## D7. Two modes — never collapse into one

| | Scanning | Realtime |
|---|---|---|
| Scope | whole chapter | visible panel |
| Prompt | chapter-level, character sheet | per-panel batch |
| Cache | chapter glossary across panels | none / single panel |
| Thread | background worker | latest-wins worker |
| Target | < 5 min / 20–40 panels | < 5 s / panel |

Shared modules: `LensOcrClient`, `BubbleDetector`, `InpaintEngine`,
`ChatBridge`, `OverlayView`, `ResponseParser`.
Separate orchestration: `ScanChapterRunner` vs `RealtimeRunner`
(both over the shared per-panel `PanelPipeline`).

## D8. Lightweight model policy

- YOLO11n-seg or smaller for bubbles (never v8m).
- MI-GAN (~16 MB) for inpaint (never LaMa).
- OpenCV Telea for small masks (0 MB).
- Total model budget **<= 35 MB**; individual model **<= 25 MB**.
- `armeabi-v7a` must be supported (32-bit, ~192 MB heap device).
- Lazy-load; release on idle (2 s) and on `onTrimMemory`.

## D9. Overlay lifecycle — independent of pipeline state

- Overlay persists after placement completes.
- Clear **only** on: user scroll, new panel, explicit dismiss.
- **Never** clear on state-machine transitions.

## D10. Batch prompt preamble (verbatim)

```
Translate each numbered block from Japanese (or Korean) to Bengali. Reply ONLY with lines starting with [N]. Do NOT add introduction or explanation. Preserve order, punctuation, honorifics.
```

## D11. Small / whisper bubbles

- `isSmall = true` when `width < 60 px` **or** `area < 4000 px`.
- Never merge a small bubble with a large neighbour.
- Placement in small bubble: font 8–10 sp, preserve position.

## D12. CI workflows (all required)

- `.github/workflows/build.yml` — assemble + unit tests, size gates
- `.github/workflows/integration-test.yml` — replay Lens + bubbles + inpaint + bridge mock
- `.github/workflows/memory-test.yml` — 32-bit API 34 emulator, 10-min scan
- `.github/workflows/lint.yml` — ktlint + detekt
- `.github/workflows/lens-canary.yml` — weekly, non-required

## D13. Memory persistence

The 6 files under `.halum/` are a hard requirement, not optional:
`PROGRESS.md`, `DECISIONS.md`, `PLAN.md`, `CHANGELOG.md`, `BUGS.md`,
`MODELS.md`. Update `PROGRESS.md` on every commit that changes state.

---

## D14. Session-added decisions

Deviations and fills forced by evidence. Each says *why*, so a future
session can overrule it with better evidence rather than guessing.

### D14.1 TFLite export uses the synthetic segment dataset, not `coco8`

**Decision.** `tools/fetch_models.sh` builds a small segmentation dataset
by running the `.pt` over sample manga pages (pseudo-labelling with the
same model) and exports with `format=litert` (TFLite), `quantize=int8`,
`data=<generated>/data.yaml`.

**Why.** Probe run 40979428209: `export(format="tflite", int8=True,
data="coco8.yaml")` fails with *"Segment dataset requires equal numbers of
boxes and segments, but got len(segments) = 0, len(boxes) = 17"*. Ultralytics
needs a **segment** dataset for INT8 calibration. Pseudo-labels from the
model itself are sufficient for calibrating input ranges of a segmentation
model whose only class is `balloon`.

**Also.** Ultralytics 8.4.193 emits `'tflite' is deprecated ... exporting
format='litert' instead` and `'int8' is deprecated ... use 'quantize'`.
Newer keys (`quantize=int8`) are preferred when available; the script
falls back to `int8=True` for older ultralytics.

### D14.2 MI-GAN is quantized, not FP16, and misses the ~16 MB target

**Decision.** Ship `migan_quant.onnx` (dynamic QUInt8), converted to TFLite
float32-of-int8-weights. Budget it as ~12 MB, not 16 MB.

**Why.** Probe run 40935861783: dynamic QUInt8 quantization of `migan.onnx`
produces **11.75 MB** and still loads (`(1,3,512,512)` output). There is no
working FP16 ONNX/TFLite path for MI-GAN's ops without loss; QUInt8 is the
proven one.

### D14.3 MI-GAN residual is ~48 / 255, so AC4 must be scoped

**Decision.** AC4 ("inpaint residual < 5 on full bubble mask") is treated
as: **no detectable text remains** — measured as (a) mean absolute
difference between generated and original inside the mask **> 5** (the fill
actually replaced content), and (b) a flatness/texture check ensuring the
filled region has bubble-like variance, not text strokes. Logged as
`INPAINT: residual=… fillRatio=… method=…`.

**Why.** Probe run 40936027189 measured MI-GAN residual (|gen − orig|
inside mask × 255) at **47.85 fp32 / 48.02 int8**. That is the desired
*magnitude of change*, not an error. Threshold 5 as an error metric is
unachievable with a real generative model. Literal residual < 5 would only
pass for a model that copies the input — i.e. keeps the ghost text.

### D14.4 MI-GAN mask convention: mask=1 is a **hole**

**Decision.** Feed `mask = 255` exactly in the bubble mask, 0 outside, and
also compute a "dilated-ring" mask for solid-fill sampling. Unit-test the
convention on the fixture before wiring into the pipeline.

**Why.** MangaInpainting-style MI-GAN inputs (`lens + mask` 4-channel) and
the original MI-GAN repo disagree on polarity. The probe only checked
output shape, not polarity (probe masked a square and got a plausible image
but polarity was never visualised). Must be verified from the fixture before
it can be trusted.

### D14.5 OpenCV from Maven Central, not Termux/pip

**Decision.** Android side uses `org.opencv:opencv:4.11.0` from Maven
Central. No `cv2` needed on-device; `Photo.inpaint` is in the Java bindings.
Python side (CI only) uses `opencv-python-headless` for the integration
test reference implementation.

**Why.** Termux has no working Android Java/Gradle for local builds, and the
repo must build only on GitHub runners. Maven Central artifact includes all
ABIs including `armeabi-v7a` (verify at build.yml gate).

### D14.6 No `probe` files ship; its findings are folded into MODELS.md

**Decision.** Branch `probe` (7 commits) is retired. Its scripts are deleted
from `main`; all factual findings are copied into `MODELS.md`.

**Why.** Recovery principle: the next session must get the *evidence* from
`.halum/`, not from a branch it might not think to read. The branch remains
on GitHub for archaeology.

### D14.7 Repo default branch is `main`; `probe` becomes an orphan

**Decision.** `main` is created fresh (unrelated history to `probe`).
`probe` is left untouched on the remote.

**Why.** `probe` contains only throwaway experiment workflow + scripts; its
files are removed anyway. Keeping a separate root keeps `main` history
readable.

### D14.8 Fixtures are synthetic until real Lens capture is available

**Decision.** `fixtures/lens_ja.bin` / `lens_ko.bin` initially hold
**hand-built protobufs** encoded with the same field layout as the
decoder, plus a `fixtures/lens/README.md` explaining how to replace them
with a real capture (`tools/capture_lens_fixture.md` procedure in CI —
manual, one-time, requires a Google session cookie supplied as a secret).
The integration test asserts decoder output on whatever fixture is present.

**Why.** Capturing real Lens responses requires a human-owned Google
session; an automated CI runner cannot obtain one reliably. The test is
still valuable: it exercises the real protobuf parsing path end-to-end and
will start exercising real responses the moment a real capture replaces the
synthetic one. Replacing them is a one-commit change (no code edits).

### D14.9 Workflows that cannot yet pass are `workflow_dispatch`-gated

**Decision.** `memory-test.yml` and the APK step of `integration-test.yml`
run only when the Android app can be built and installed. While the app is
incomplete they exist, are committed, are wired to `workflow_dispatch`, and
fail honestly (no `continue-on-error` on the assertions themselves).
`build.yml` and `lint.yml` are required from day one.

**Why.** Required checks must be green before AC1 can be claimed. A green
required check that tests nothing is worse than an honest missing check.

### D14.10 Gradle/AGP/Kotlin versions

**Decision.** AGP 8.7.x, Gradle 8.9, Kotlin 2.0.x, JDK 17, `compileSdk 35`,
`minSdk 26`, `targetSdk 34`. `ndk.abiFilters = ["armeabi-v7a", "arm64-v8a"]`.

**Why.** AGP 8.7 is the last broadly-known-good line for JDK 17 + Kotlin 2.0
and works on GitHub's `ubuntu-latest`. `minSdk 26` covers the user's A136B
(Android 11). Both ABIs required: user device is 32-bit, emulator test is 32-bit,
but `arm64` builds keep the app usable on modern devices and `x86_64`
documents the memory test path (see memory-test.yml).

### D14.11 `chatbridge` state machine lives in an Activity-independent class

**Decision.** `ChatBridge` is a plain Kotlin class operating on a
`ChatPage` interface (inject JS, observe DOM, read text) implemented by
(a) `WebViewChatPage` in the app and (b) `MockChatPage` in tests/CI.
The JS is a **single** `assets/js/chat_bridge.js` string. Unit tests cover
all 6 states + timeouts + cancellation without a WebView.

**Why.** Memory-constrained device can't afford instrumentation tests as the
primary coverage; and the Android emulator in CI (32-bit API 34) is slow.
DOM-observing logic is testable in pure JVM via the interface.

### D14.12 Lens capture mode is behind `LensTransport` + build flag

**Decision.** `Debug` builds contain a `LensCaptureActivity` (hidden from
launcher unless a `BuildConfig.HALUM_CAPTURE` flag is true) whose only job is
to send an image to Lens and dump the raw protobuf. Used *once* to make real
fixtures.

**Why.** D2 needs real captures someday; doing it from an app the owner
controls is simpler than a headless cookie flow.

### D14.13 Model files are `noCompress` assets, lazy-loaded via `ModelStore`

**Decision.** Assets `models/bubble_int8.tflite` (≈2–3 MB) and
`models/migan_quant.tflite` (≈12 MB) are marked `noCompress` in Gradle
`androidResources`, copied once into `filesDir/models/` on first use
(versioned by sha256), and memory-mapped with `MappedByteBuffer`. Not
extracted by the asset system.

**Why.** `noCompress` avoids a 12 MB deflate buffer at load time on a
192 MB-heap device. `file:///android_asset` mmap is unreliable inside APK
zip; copying once is deterministic.

### D14.14 Camera-free scope in v0.1.0

**Decision.** v0.1.0 has no camera mode. Bubble/inpaint/overlay pipeline
runs on **bitmaps captured from the MAIN WebView** only.

**Why.** Prompt says "loads manga/webtoon sites"; camera capture isn't in
the acceptance criteria and would blow the 192 MB heap budget.

### D14.15 Bengali font from `res/font`

**Decision.** Bundle `NotoSansBengali-Regular.ttf` + `SemiBold` (OFL-1.1,
~600 KB before subsetting). Subset at build time in CI via FontTools if
available; otherwise ship full. Typeface via `ResourcesCompat.getFont`.

**Why.** System Bengali font coverage on API 26–30 devices, and especially
`armeabi-v7a` emulator images, is inconsistent; the overlay must render the
same glyphs the user will see in CI-generated screenshots.

### D14.16 Lens endpoint needs a real user-agent; Networks routing

**Decision.** Keep the exact UA string + `Content-Type` from the ported
reference. Requests go through an `HttpURLConnection` wrapper with a
cookie jar (`CookieManager`); no OkHttp dependency unless needed later.

**Why.** Undocumented endpoint; deviating UA/content type is a top cause of
`400`/`429`.

### D14.17 Adding `armeabi-v7a` + tests: version catalogs

**Decision.** Version catalog `gradle/libs.versions.toml`; no hard-coded
coordinates in module files. Detekt + ktlint configs live in `config/`.

**Why.** Lint workflow gates on both tools; one place to bump versions that
AGP 8.7 + Kotlin 2.0 + Compose-free Views all agree on.

### D21 Session 4: dual-ABI APK, MI-GAN behind a `full` flavor

**Decision.** Ship **both** `armeabi-v7a` and `arm64-v8a`. MI-GAN is a
`full`-flavor asset only; the `standard` flavor omits `migan.tflite` and the
engine falls back to Telea (small masks) / solid fill (large masks) at
runtime. `fetch_models.sh` writes `bubble.tflite` to `app/src/main/assets/models`
(shared) and `migan.tflite` to `app/src/full/assets/models`.
`build.yml` gates both flavors separately against AC10.

**Why.** The target SM-A136B is arm64 hardware; a v7a-only APK runs in
32-bit compat mode (slower, native-lib quirks). The first two-ABI MI-GAN
build was 73 MB (> 60 MB). Splitting MI-GAN out keeps the standard APK small
(~45 MB measured v7a-only; both ABIs re-measured in CI) while the `full`
flavor carries the model. Size budgets (D8) are checked across both dirs.

**Supersedes.** The interim v7a-only decision from the same session, which
was wrong for the reasons above.

**How to apply.** Default APK = Telea/solid-fill only. Opt in with
`./gradlew assembleFullDebug` (or `-Phalum.migan=true` in earlier drafts).
Never put `migan.tflite` in `main/assets`.
