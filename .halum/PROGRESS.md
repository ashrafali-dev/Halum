# Halum — Progress

> **Read this first.** It is the single source of truth for resuming.
> Update it before every session ends and on every milestone commit.

**Last updated:** 2026-10-30 (session 1)
**Branch:** `main`
**Current phase:** 0 — Memory + skeleton

## Snapshot

| area | state |
|---|---|
| `.halum/` memory files | done |
| Gradle skeleton + wrapper | not started |
| App module + MainActivity stub | not started |
| Core Kotlin (pure JVM) | not started |
| Android engines (Lens/TFLite/OpenCV/Overlay/Bridge) | not started |
| Orchestration + UI | not started |
| CI: build / lint | not started |
| CI: integration / memory / canary | not started |
| Tag `v0.1.0` | not reached |

## What works

Nothing yet — repo was empty except an unrelated `probe` branch (now
retired; findings preserved in `MODELS.md`).

## What is broken / blocked

Nothing broken. Anticipated blockers are in `BUGS.md`.

## What is next (exact order)

1. Gradle skeleton: `settings.gradle.kts`, version catalog, wrapper 8.9,
   `app` module manifests, empty `MainActivity` with two WebViews, `.gitignore`,
   `LICENSE`, `NOTICE.md`, `README.md`.
2. `build.yml` + `lint.yml` first, prove green (debug APK assembly + ktlint).
3. Phase 1 pure-Kotlin core with unit tests.
4. Phase 2 engines + `integration-test.yml`.
5. Phase 3 UI/orchestration + `memory-test.yml`.
6. Phase 4 tooling + `lens-canary.yml`. Tag `v0.1.0`.

## Acceptance status

| # | criterion | status |
|---|---|---|
| AC1 | 4 required workflows pass | not started |
| AC2 | Lens replay asserts decoder output | not started |
| AC3 | bubble recall >= 80% | not started |
| AC4 | inpaint residual (scoped, D14.3) | not started |
| AC5 | bridge state machine streaming/timeout/cancel | not started |
| AC6 | scan 20–40 panels < 5 min | not started |
| AC7 | realtime 1 panel < 5 s | not started |
| AC8 | overlay persists until scroll | not started |
| AC9 | small bubbles never merged | not started |
| AC10 | APK <= 60 MB, models <= 35 MB, armeabi-v7a | not started |
| AC11 | 6 memory files current | **in progress** |
