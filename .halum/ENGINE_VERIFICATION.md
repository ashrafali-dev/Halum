# Engine verification

Run by `tools/engine_verify.py` in `integration-test.yml` (D18). Regenerated on
every integration run; the block below is the last result copied back into the
repo. Evidence PNGs live in the `engine-verification` CI artifact.

## Last run — 2026-10-30, integration 41027485611 (all green)

| engine | result | detail |
|---|---|---|
| bubbledetector | PASS | bubbles=31 small=5 recall=1.000 (frieren 7, motl 7, wha 12, ajin 5) |
| inpainter | PASS | model=migan, 4 pages (see INPAINT_VERIFY below) |
| chatbridge | PASS | 4 sessions (single block, 3-block batch, refusal, silent) |
| overlay | PASS | fits=27 over=4 over 4 pages |
| lenstransport | PASS | blocks=8 letters=100 |
| modelsizes | PASS | total=20.5 MB (bubble 5.9, migan 14.7) |

### INPAINT_VERIFY

The AC4 metric is **D14.3-scoped**: `residual` is the mean absolute change
inside the full bubble mask (0..255), and the pass condition is
`residual > 5` (the fill replaced text) **and** `fillRatio >= 0.90` **and**
flatness below a text-like threshold. A literal `residual < 5` is
unachievable for a generative fill (probe measured ~48/255) and would only
pass a model that copies its input, i.e. keeps the ghost text.

Per-fixture `INPAINT_VERIFY:` lines are printed by the harness on each run.
Before this memory update the harness logged a single aggregate
`change_min`. Example of the aggregate from the green run:

```
INPAINT_VERIFY: model=migan residual_min=69.41 fill_min=1.000 flat_max=10.2 pages=4 pass=true
```

(`69.41` = the lowest per-page mean |generated-original| inside the mask;
higher is better here — it means the source text was genuinely replaced.)

The next integration run emits one `INPAINT_VERIFY:` line per fixture, which
replaces this aggregate.
