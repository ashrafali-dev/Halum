# Halum — Changelog

Session-by-session log. Newest first. Each entry: date, what changed,
what CI said.

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
