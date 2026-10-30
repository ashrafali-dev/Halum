# Engine verification

Run by `tools/engine_verify.py` in `integration-test.yml` (D18). This file is
OVERWRITTEN by that harness on every run; do not hand-edit. It currently holds
no results because no CI run has executed the harness yet.

Per-engine evidence (masks, inpaint previews, decoded Lens JSON) is uploaded as
the `engine-verification` artifact and also written to `.halum/verify/` (which
is git-ignored).

## Status

_No run yet — the next push to `main` produces the first table._
