# Halum

Manga / webtoon translation overlay for Android. On-device runtime, no paid
APIs, 32-bit friendly.

**Status:** early skeleton. See `.halum/PROGRESS.md` for the real state.

## How it works

1. **Lens OCR** — Google Lens `v1/crupload` unofficial endpoint extracts text
   and boxes from the panel bitmap.
2. **Bubble detection** — YOLO11n-seg (TFLite INT8) finds balloon masks.
3. **Inpaint** — OpenCV Telea for small masks, MI-GAN for large ones; the
   whole bubble is filled so no source text ghosts through.
4. **Translate** — a chatbot WebView (ChatGPT / Claude / Gemini) is driven by
   an injected bridge; numbered `[N]` blocks in, numbered `[N]` lines out.
5. **Overlay** — Bengali text is drawn back into the exact bubble rectangles.

Two modes: **Scan Chapter** (whole chapter, chapter-level prompt, target
< 5 min) and **Realtime** (visible panel, target < 5 s).

## Build

Everything runs in GitHub Actions; no local toolchain is needed or assumed.

- `build.yml` — debug APK + unit tests + size gates
- `integration-test.yml` — Lens fixture replay, bubble recall, inpaint, bridge mock
- `lint.yml` — ktlint + detekt + Android Lint
- `memory-test.yml` — 32-bit emulator, 10-minute scripted scan
- `lens-canary.yml` — weekly live Lens check (non-blocking)

## Project continuity

All decisions and status live in `.halum/`:

- `PROGRESS.md` — read this first
- `DECISIONS.md` — every pre-made decision (D1–D14)
- `PLAN.md` — phase plan
- `MODELS.md` — model provenance, sizes, probe evidence
- `BUGS.md` — open issues
- `CHANGELOG.md` — session log

## Licence

GPL-3.0. Non-commercial. See `LICENSE` and `NOTICE.md`.
