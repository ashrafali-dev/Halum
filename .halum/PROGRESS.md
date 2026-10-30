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
| CI workflows: build / lint / integration-test / memory-test / lens-canary | committed, all red as of 4098577xxxx |
| CI `format.yml` (ktlint --format, auto-commit) | added session 2 |
| Bubble export (`tools/export_bubble.py`) | fixed session 2 — needs a green run |
| MI-GAN export (`tools/export_migan.py`) | written, never run in CI (needs `MIGAN_ONNX_URL`) |
| Core Kotlin (pure JVM: lens/split/placement/bridge) | not started |
| Android engines (Lens/TFLite/OpenCV/Overlay) | not started |
| Orchestration + full UI | not started |
| `fixtures/lens_ko.bin`, `fixtures/lens_ja.bin` | not captured |
| Tag `v0.1.0` | not reached |

## What works

- Skeleton: Gradle 8.9 wrapper, version catalog, `app` module, manifests,
  `MainActivity` (MAIN + CHAT WebViews, draggable splitter, URL prompt),
  `HalumApp`, `util/Log` + its JVM unit test.
- 5 workflows + `tools/{fetch_models,export_bubble,export_migan,report_sizes}`.
- Nothing has ever passed CI yet. Do not claim otherwise.

## What is broken / blocked

1. **build + integration-test** were red on `exports` — the venv pip failed on
   a nonexistent `onnx2tf-typing`. Fixed in session 2 (onnx2tf 2.6.9), unverified.
2. **lint** was red on ktlint formatting. Fixed by hand in session 2 and now
   guarded by `format.yml`; unverified.
3. **Unverified dependency/API surface**: `app/build.gradle.kts` references
   TFLite/OpenCV/etc. but no engine code exists yet; first real compile of
   those deps happens when Phase 2 starts.
4. `report_sizes.py` gates never executed because CI never got past export.

## What is next (exact order)

1. Push session-2 changes; watch **build**, **lint**, **format**,
   **integration-test** to green (`gh run watch <id> --exit-status`). Fix
   whatever the first real Gradle compile surfaces (expect dependency/API
   mismatches — none of `app/build.gradle.kts` has been exercised).
2. Fill the `integration-test.yml` body (it currently only fetches models and
   collects fixtures) until AC2/AC3/AC4 assertions exist.
3. Phase 1: pure-Kotlin core in `app/src/main/java/app/halum/core/` with unit
   tests: LensProtocol decoder (replay fixtures), text splitter (D6 rules),
   placement, bridge state machine.
4. Capture `fixtures/lens_ko.bin` / `lens_ja.bin` via `workflow_dispatch` +
   artifact (record-once, D2).
5. Phase 2 engines, then UI, then `lens-canary.yml`, then `v0.1.0`.

## Open questions for the user (blocking, ask before spending CI time)

- `MIGAN_ONNX_URL` is empty, so no inpaint model is produced. Supply a mirror
  URL (or approve shipping D4 fallback-only: Telea + ring-colour fill).
- `HF_TOKEN` is passed to export but the bubble repo is public; drop the
  secret if unnecessary.

## Acceptance status

| # | criterion | status |
|---|---|---|
| AC1 | 4 required workflows pass | red — fixes unverified |
| AC2 | Lens replay asserts decoder output | not started |
| AC3 | bubble recall >= 80% | not started |
| AC4 | inpaint residual (scoped, D14.3) | not started |
| AC5 | bridge state machine streaming/timeout/cancel | not started |
| AC6 | scan 20–40 panels < 5 min | not started |
| AC7 | realtime 1 panel < 5 s | not started |
| AC8 | overlay persists until scroll | not started |
| AC9 | small bubbles never merged | not started |
| AC10 | APK <= 60 MB, models <= 35 MB, armeabi-v7a | not started |
| AC11 | 6 memory files current | yes — updated session 2 |
