# Halum — Changelog

Session-by-session log. Newest first. Each entry: date, what changed,
what CI said.

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
