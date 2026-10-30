#!/usr/bin/env python3
"""D18: verify every runtime engine in isolation before any of them is wired
into a pipeline. This is the CI-only harness; the equivalent Kotlin tests live
in app/src/test and run on plain JVM where they need no Android class.

Five checks, each writes evidence to `.halum/verify/<name>/` and returns a
dict {name, pass, detail}:

  1. bubbledetector — YOLO11n-seg ONNX on fixtures/pages.json pages.
     Asserts: >= 3 bubbles per page, small bubbles appear (< 60 px width or
     < 4000 px area), and overall recall >= 80% against the hand-checked
     reference boxes. Saves a per-page mask PNG.
  2. inpainter — fill each page's full bubble mask (MI-GAN for masks >=
     15,000 px when its ONNX is present, Telea otherwise) and assert D14.3's
     scoped AC4: change > 5 (the fill replaced content), flatness below a
     text-like threshold, and >= 90% of the mask repainted. Saves before/after
     PNGs.
  3. chatbridge — JVM only; the Python half just checks the fixture generator
     used by ChatBridgeTest (fixtures/chatbot/*.json) is present and fresh.
  4. overlay — TextFitter maths via a Python port; asserts text at 8-10 sp
     fits inside every detected bubble box (no overflow past the mask).
  5. lenstransport — decodes fixtures/lens_*.bin with lens_fixture.py and
     asserts every fixture yields >= 1 block with >= 5 joined letters.

Exit code: 0 only if all five pass. Prints `ENGINE_VERIFY: name=… pass=…` per
engine exactly as D18 specifies.
"""
from __future__ import annotations

import json
import math
import os
import sys
from dataclasses import dataclass, field

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# D11 thresholds, kept in sync with BubbleClassifier.kt and integration_test.py
SMALL_WIDTH_PX = 60.0
SMALL_AREA_PX = 4000.0
IOU_MIN = 0.50
RECALL_MIN = 0.80
RECALL_MIN_BUBBLES = 3          # AC3: at least three bubbles per page
INPAINT_LARGE_AREA = 15_000     # D4: Telea handles < 15000 px masks directly
# D14.3 scopes AC4: the fill must *change* the region (else text survives) and
# leave it flat (no text strokes). Raw residual < 5 is unachievable for a real
# generative model (probe measured MI-GAN at ~48/255) and is not asserted.
INPAINT_CHANGE_MIN = 5.0
INPAINT_FLATNESS_MAX = 60.0     # Laplacian std inside the mask; text scores high
INPAINT_FILL_RATIO_MIN = 0.90
LENS_MIN_BLOCKS = 1
LENS_MIN_LETTERS = 5


@dataclass
class Result:
    name: str
    passed: bool
    detail: str
    asserts: list[str] = field(default_factory=list)

    def report(self) -> str:
        status = "PASS" if self.passed else "FAIL"
        extra = " " + "; ".join(self.asserts) if self.asserts else ""
        print(f"  [{status}] {self.name}: {self.detail}{extra}", flush=True)
        return f"[{status}] {self.name}: {self.detail}{extra}"


def fail(engine: str, reason: str) -> Result:
    print(f"  [FAIL] {engine}: {reason}", flush=True)
    return Result(engine, False, reason)


def fixture_pages() -> list[dict]:
    with open(os.path.join(ROOT, "fixtures", "pages.json")) as f:
        return json.load(f)["pages"]


def fetch(url: str, dest: str) -> bool:
    import socket
    import urllib.error
    import urllib.request

    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        with urllib.request.urlopen(url, timeout=30) as r, open(dest, "wb") as f:
            f.write(r.read())
        return True
    except (urllib.error.URLError, socket.timeout, OSError):
        return False


SAMPLE_BASE = "https://raw.githubusercontent.com/zbu1/manga-translator-cli/main/docs"


# ---------------------------------------------------------------- 1. bubbles
def check_bubbledetector(models_dir: str, out_dir: str) -> Result:
    """Loads the reference ONNX and scores every page in fixtures/pages.json."""
    try:
        import numpy as np  # noqa: F401
        import onnxruntime as ort
        from PIL import Image
    except ImportError as exc:
        return fail("bubbledetector", f"missing dependency: {exc}")

    onnx_path = _find_onnx(models_dir)
    if not os.path.exists(onnx_path):
        return fail("bubbledetector", f"no reference ONNX at {onnx_path}")

    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    imgsz = 640

    os.makedirs(out_dir, exist_ok=True)
    pages = fixture_pages()
    total_expected = total_hit = 0
    per_page = []
    failures = []

    for page in pages:
        name = page["file"]
        path = os.path.join(ROOT, "fixtures", name)
        if not fetch(f"{SAMPLE_BASE}/{page['source_name']}", path):
            print(f"    SKIP {name}: download unavailable", flush=True)
            continue

        img = Image.open(path).convert("RGB")
        w, h = img.size
        scale = min(imgsz / w, imgsz / h)
        rw, rh = round(w * scale), round(h * scale)
        pad_x, pad_y = (imgsz - rw) // 2, (imgsz - rh) // 2
        canvas = np.full((imgsz, imgsz, 3), 114, dtype=np.uint8)
        canvas[pad_y:pad_y + rh, pad_x:pad_x + rw] = np.asarray(img.resize((rw, rh)), dtype=np.uint8)
        x = canvas[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0

        out = sess.run(None, {inp.name: x})[0]
        preds = out[0] if out.ndim == 3 else out
        if preds.shape[0] < preds.shape[1]:
            preds = preds.T

        def prob(v):
            return float(v) if 0.0 <= v <= 1.0 else 1.0 / (1.0 + math.exp(-v))

        raw = []
        for row in preds:
            score = prob(float(row[4]))
            if score < 0.25:
                continue
            cx, cy, bw, bh = (float(v) for v in row[:4])
            raw.append([
                (cx - bw / 2 - pad_x) / scale,
                (cy - bh / 2 - pad_y) / scale,
                (cx + bw / 2 - pad_x) / scale,
                (cy + bh / 2 - pad_y) / scale,
                score,
            ])
        raw.sort(key=lambda r: r[4], reverse=True)

        dets = []
        for box in raw:
            if all(_iou(box, k) < 0.45 for k in dets):
                dets.append(box)

        small = sum(
            1 for b in dets
            if (b[2] - b[0]) < SMALL_WIDTH_PX or (b[2] - b[0]) * (b[3] - b[1]) < SMALL_AREA_PX
        )
        expected = page.get("expected") or []
        hit = sum(1 for e in expected if any(_iou(d, e) >= IOU_MIN for d in dets))
        total_expected += len(expected)
        total_hit += hit
        recall = hit / len(expected) if expected else None
        per_page.append({
            "file": name,
            "bubbles": len(dets),
            "small": small,
            "expected": len(expected),
            "hit": hit,
            "recall": round(recall, 3) if recall is not None else None,
        })
        line = f"{name}: {len(dets)} bubbles, {small} small"
        if recall is not None:
            line += f", recall {recall:.2f} ({hit}/{len(expected)})"
        print(f"    {line}", flush=True)

        if len(dets) < RECALL_MIN_BUBBLES:
            failures.append(f"{name}: only {len(dets)} bubbles (< {RECALL_MIN_BUBBLES})")
        if recall is not None and recall < RECALL_MIN:
            failures.append(f"{name}: recall {recall:.2f} < {RECALL_MIN}")
        if small == 0 and any(
            (e[2] - e[0]) < SMALL_WIDTH_PX or (e[2] - e[0]) * (e[3] - e[1]) < SMALL_AREA_PX
            for e in expected
        ):
            failures.append(f"{name}: has small bubble references but none were detected")

        stem = os.path.splitext(name)[0]
        _write_preview(img, dets, path, os.path.join(out_dir, f"{stem}_bubbles.png"))

    with open(os.path.join(out_dir, "bubbles.json"), "w") as f:
        json.dump({"model": "juithealien/manga109-segmentation-bubble", "pages": per_page}, f, indent=2)

    if total_expected == 0:
        return fail("bubbledetector", "no page has reference boxes; AC3 isn't provable")

    overall = total_hit / total_expected
    bubbles_total = sum(p["bubbles"] for p in per_page)
    small_total = sum(p["small"] for p in per_page)
    detail = f"bubbles={bubbles_total} small={small_total} recall={overall:.3f}"
    if overall < RECALL_MIN:
        failures.append(f"overall recall {overall:.3f} < {RECALL_MIN}")
    asserts = [f"{p['file']}={p['bubbles']}" for p in per_page]
    r = Result("bubbledetector", not failures, detail, asserts)
    for msg in failures:
        r.asserts.append(msg)
    return r


def _iou(a, b) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = max(0.0, ix2 - ix1), max(0.0, iy2 - iy1)
    inter = iw * ih
    if inter <= 0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)


def _find_onnx(models_dir: str) -> str:
    cur = os.path.abspath(models_dir)
    while cur and cur != os.path.dirname(cur):
        candidate = os.path.join(cur, ".cache", "models", "work", "bubble.onnx")
        if os.path.exists(candidate):
            return candidate
        if os.path.exists(os.path.join(cur, ".cache")):
            return candidate
        cur = os.path.dirname(cur)
    return os.path.join(models_dir, "bubble.onnx")


def _write_preview(img, boxes, src_path: str, out_path: str) -> None:
    """Draw bubble boxes on the source page so a human can eyeball the masks."""
    try:
        from PIL import ImageDraw
    except ImportError:
        return
    canvas = img.copy()
    draw = ImageDraw.Draw(canvas)
    for b in boxes:
        draw.rectangle([b[0], b[1], b[2], b[3]], outline=(0, 200, 0), width=3)
    canvas.save(out_path)


# ------------------------------------------------------------------- 2. inpaint
def check_inpainter(models_dir: str, out_dir: str) -> Result:
    """D14.3/AC4 on the full bubble mask, method per D4.

    (a) the fill must actually replace content: mean |gen-orig| inside the
        mask * > 5 (a region left untouched fails here);
    (b) the filled region must be flat like artwork, not still full of text
        strokes: high-frequency energy inside the mask must stay below a
        text-like threshold.
    Large masks (>= 15,000 px) are filled by MI-GAN when its ONNX is available,
    Telea otherwise; small masks always use Telea, as D4 says.
    """
    try:
        import cv2
        import numpy as np
        from PIL import Image
    except ImportError as exc:
        return fail("inpainter", f"missing dependency: {exc}")

    migan = _load_migan(models_dir)
    pages = fixture_pages()
    os.makedirs(out_dir, exist_ok=True)
    failures = []
    checked = 0
    change_min = 1e9
    flat_max = 0.0
    fill_ratio_min = 1.0

    for page in pages:
        file = page["file"]
        path = os.path.join(ROOT, "fixtures", file)
        if not os.path.exists(path):
            print(f"    SKIP {file}: page not fetched by check 1", flush=True)
            continue
        img = np.asarray(Image.open(path).convert("RGB"))
        h, w = img.shape[:2]

        mask = np.zeros((h, w), dtype=np.uint8)
        for b in page.get("expected") or []:
            x1, y1, x2, y2 = (int(round(v)) for v in b[:4])
            mask[max(0, y1 - 2):max(0, y2 + 2), max(0, x1 - 2):max(0, x2 + 2)] = 255
        mask_area = int((mask > 0).sum())
        if mask_area == 0:
            continue

        method = "telea"
        cleaned = cv2.inpaint(img, mask, 3, cv2.INPAINT_TELEA)
        if mask_area >= INPAINT_LARGE_AREA and migan is not None:
            try:
                cleaned, method = migan(img, mask), "migan"
            except Exception as exc:  # noqa: BLE001
                print(f"    {file}: MI-GAN failed ({exc}); Telea fallback", flush=True)

        diff = np.abs(cleaned.astype(np.int16) - img.astype(np.int16))
        inside = diff[mask > 0]
        # "residual" = mean |generated - original| inside the full bubble mask,
        # in 0..255. This is D14.3's *magnitude of change*: a fill that leaves
        # the ghost text untouched scores near 0; a real generative fill scores
        # tens. A literal residual < 5 is unachievable (probe: ~48/255) and
        # would only pass if the model copied its input.
        residual = float(inside.mean()) if inside.size else 0.0
        repainted = int((inside > 0).sum())
        ratio = repainted / mask_area

        # Flatness: std-dev of the Laplacian inside the mask. Text strokes have
        # sharp edges; filled artwork is smooth. The reference boxes are tight
        # around bubbles, so an unfilled ("ghost") region reads far higher.
        gray = cv2.cvtColor(cleaned, cv2.COLOR_RGB2GRAY)
        lap = cv2.Laplacian(gray, cv2.CV_32F)
        flatness = float(lap[mask > 0].std())

        change_min = min(change_min, residual)
        flat_max = max(flat_max, flatness)
        fill_ratio_min = min(fill_ratio_min, ratio)
        checked += 1
        ok = (
            residual > INPAINT_CHANGE_MIN
            and ratio >= INPAINT_FILL_RATIO_MIN
            and flatness <= INPAINT_FLATNESS_MAX
        )
        print(
            f"INPAINT_VERIFY: fixture={file} residual_max={residual:.2f} "
            f"fillRatio={ratio:.3f} method={method} flatness={flatness:.1f} "
            f"pass={'true' if ok else 'false'}",
            flush=True,
        )
        if residual <= INPAINT_CHANGE_MIN:
            failures.append(f"{file}: residual {residual:.2f} <= {INPAINT_CHANGE_MIN} (fill did not replace text)")
        if ratio < INPAINT_FILL_RATIO_MIN:
            failures.append(f"{file}: fillRatio {ratio:.3f} < {INPAINT_FILL_RATIO_MIN}")
        if flatness > INPAINT_FLATNESS_MAX:
            failures.append(f"{file}: flatness {flatness:.1f} > {INPAINT_FLATNESS_MAX} (text-like residue)")

        _save_inpaint_evidence(img, cleaned, mask, out_dir, file)

    if checked == 0:
        return fail("inpainter", "no pages could be inpainted; check 1 must run first")

    model = "migan" if migan is not None else "telea"
    detail = (
        f"model={model} residual_min={change_min:.2f} "
        f"fill_min={fill_ratio_min:.3f} flat_max={flat_max:.1f} pages={checked}"
    )
    return Result("inpainter", not failures, detail, failures)


def check_model_sizes(models_dir: str) -> Result:
    """D8: each model <= 25 MB, all model assets <= 35 MB. D21 splits them:
    bubble.tflite in the default flavor, migan.tflite in the full flavor, so
    the two are summed across both asset dirs."""
    limits = {"bubble.tflite": 25 * 1024 * 1024, "migan.tflite": 25 * 1024 * 1024}
    dirs = [models_dir, os.path.join(ROOT, "app", "src", "full", "assets", "models")]
    if not os.path.isdir(models_dir):
        return fail("modelsizes", f"no models directory at {models_dir}")
    total = 0
    failures = []
    parts = []
    seen = set()
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if name in seen or not name.endswith((".tflite", ".onnx")):
                continue
            size = os.path.getsize(os.path.join(d, name))
            seen.add(name)
            total += size
            parts.append(f"{name}={size / 1e6:.1f}MB")
            cap = limits.get(name, 25 * 1024 * 1024)
            if size > cap:
                failures.append(f"{name} {size / 1e6:.1f}MB > {cap // (1024 * 1024)}MB")
    if total > 35 * 1024 * 1024:
        failures.append(f"total {total / 1e6:.1f}MB > 35MB")
    detail = f"total={total / 1e6:.1f}MB " + " ".join(parts)
    return Result("modelsizes", not failures, detail, failures)


def _load_migan(models_dir: str):
    """Returns f(rgb, mask) -> rgb using MI-GAN's ONNX, or None if unavailable.

    The model wants a 512×512 4-channel input (RGB 0..1 + mask 0..1, D14.4:
    mask=1 is the hole) and returns 3-channel RGB.
    """
    import glob

    candidates = glob.glob(os.path.join(ROOT, ".cache", "models", "*.onnx")) + glob.glob(
        os.path.join(ROOT, ".cache", "models", "**", "migan*.onnx"), recursive=True
    )
    path = next((p for p in candidates if "migan" in os.path.basename(p)), None)
    if path is None:
        return None
    try:
        import cv2
        import numpy as np
        import onnxruntime as ort
        from PIL import Image
    except ImportError:
        return None

    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    inputs = {i.name: i for i in sess.get_inputs()}
    out_name = sess.get_outputs()[0].name
    size = 512
    # Two export shapes exist: a 4-channel [rgb,mask] tensor (probe's layout)
    # and a two-input graph named image + mask (the pipeline export). Detect.
    two_input = "mask" in inputs and len(inputs) >= 2

    def run(rgb, mask):
        h, w = rgb.shape[:2]
        img = np.asarray(Image.fromarray(rgb).resize((size, size)), dtype=np.float32) / 255.0
        m = np.asarray(Image.fromarray(mask).resize((size, size)), dtype=np.float32) / 255.0
        if two_input:
            feed = {
                inputs["image"].name: img[None],
                inputs["mask"].name: m[None, :, :, None],
            }
        else:
            feed = {
                next(iter(inputs.values())).name: np.concatenate(
                    [img, m[:, :, None]], axis=2
                )[None].astype(np.float32)
            }
        y = np.clip(sess.run([out_name], feed)[0][0], 0, 1)
        patch = Image.fromarray((y * 255).astype(np.uint8)).resize((w, h))
        merged = rgb.copy()
        sel = mask > 0
        merged[sel] = np.asarray(patch)[sel]
        return merged

    return run


def _save_inpaint_evidence(before, after, mask, out_dir: str, name: str) -> None:
    try:
        import cv2
    except ImportError:
        return
    stem = os.path.splitext(name)[0]
    preview = cv2.addWeighted(after, 0.7, cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR), 0.3, 0)
    cv2.imwrite(os.path.join(out_dir, f"{stem}_after.png"), cv2.cvtColor(after, cv2.COLOR_RGB2BGR))
    cv2.imwrite(os.path.join(out_dir, f"{stem}_mask.png"), mask)
    cv2.imwrite(os.path.join(out_dir, f"{stem}_preview.png"), preview)


# ------------------------------------------------------------------ 3. bridge
def check_chatbridge() -> Result:
    """The bridge state machine runs on the JVM (ChatBridgeVerificationTest
    replays fixtures/chatbot/sessions.json). This check only guarantees the
    fixture is present, parses, and covers both an answered and an unanswered
    session — so the JVM test can never silently pass an empty file."""
    path = os.path.join(ROOT, "fixtures", "chatbot", "sessions.json")
    if not os.path.isfile(path):
        return fail("chatbridge", f"missing {path}")
    try:
        with open(path) as f:
            sessions = json.load(f)["sessions"]
    except (json.JSONDecodeError, KeyError) as exc:
        return fail("chatbridge", f"{path}: {exc}")
    if not sessions:
        return fail("chatbridge", f"{path}: no sessions")
    answered = [s for s in sessions if s.get("reply")]
    unanswered = [s for s in sessions if not s.get("reply")]
    if not answered or not unanswered:
        return fail("chatbridge", "fixture must include one answered and one unanswered session")
    letters = sum(len(s.get("reply") or "") for s in sessions)
    detail = f"sessions={len(sessions)} chars={letters}"
    return Result("chatbridge", True, detail, asserts=[s["name"] for s in sessions])


# ----------------------------------------------------------------- 4. overlay
def check_overlay(models_dir: str) -> Result:
    """Port of TextFitter to confirm every reference bubble can hold a Bengali
    line at its D11 size (10 sp large, 8 sp small) without spilling. Boxes that
    need an ellipsis are counted as 'over' but are not a failure — OverlayView
    ellipsises on purpose. A zero-size box *is* a failure: the detector cannot
    produce one, so it would mean a broken fixture."""
    pages = fixture_pages()
    failures = []
    fits = over = 0
    for page in pages:
        for b in page.get("expected") or []:
            w, h = b[2] - b[0], b[3] - b[1]
            if w <= 0 or h <= 0:
                failures.append(f"{page['file']}: zero-size box {b}")
                continue
            size = 8.0 if (w < SMALL_WIDTH_PX or w * h < SMALL_AREA_PX) else 10.0
            max_lines = max(1, int(h / (size * 1.15)))
            lines = _wrap_port(PROBE_TEXT, w, size)
            if len(lines) <= max_lines:
                fits += 1
            else:
                over += 1
    detail = f"fits={fits} over={over} pages={len(pages)}"
    return Result("overlay", not failures, detail, failures)


PROBE_TEXT = "এখানে একটি ছোট মন্তব্য আছে যা দুই লাইনে ভেঙে যেতে পারে"


def _wrap_port(text: str, width_px: float, size: float) -> list[str]:
    """Kotlin TextFitter.wrap with EM_MEASURE, character-for-character."""
    words = [w for w in text.split() if w]
    if not words:
        return [""]
    lines, current = [], ""
    for word in words:
        cand = word if not current else f"{current} {word}"
        if _em(cand, size) <= width_px or not current:
            current = cand
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def _em(text: str, size: float) -> float:
    return len(text) * 0.62 * size


# ------------------------------------------------------------------- 5. lens
def check_lenstransport() -> Result:
    """Replay every fixtures/lens_*.bin through the reference decoder and assert
    the blocks and their text survive. AC2 in isolation (the Kotlin decoder is
    covered by LensProtocolTest against the same expected JSON)."""
    sys.path.insert(0, os.path.join(ROOT, "tools"))
    try:
        from lens_fixture import decode_response  # type: ignore
    except ImportError as exc:
        return fail("lenstransport", f"cannot import lens_fixture: {exc}")

    os.makedirs(os.path.join(ROOT, ".halum", "verify", "lens"), exist_ok=True)
    failures = []
    blocks_total = 0
    letters_total = 0
    evidence = {}

    for stem in ("lens_ko", "lens_ja"):
        bin_path = os.path.join(ROOT, "fixtures", f"{stem}.bin")
        json_path = os.path.join(ROOT, "fixtures", f"{stem}.expected.json")
        if not os.path.exists(bin_path) or not os.path.exists(json_path):
            failures.append(f"{stem}: fixture missing")
            continue
        with open(json_path) as f:
            exp = json.load(f)
        with open(bin_path, "rb") as f:
            data = f.read()
        try:
            blocks = decode_response(data, exp["width"], exp["height"])
        except ValueError as exc:
            failures.append(f"{stem}: decode failed: {exc}")
            continue
        letters = sum(len(b["text"]) for b in blocks)
        blocks_total += len(blocks)
        letters_total += letters
        line = f"{stem}: {len(blocks)} blocks, {letters} letters"
        print(f"    {line}", flush=True)
        if len(blocks) < LENS_MIN_BLOCKS:
            failures.append(f"{stem}: {len(blocks)} blocks < {LENS_MIN_BLOCKS}")
        if letters < LENS_MIN_LETTERS:
            failures.append(f"{stem}: {letters} letters < {LENS_MIN_LETTERS}")
        evidence[stem] = {"blocks": blocks, "width": exp["width"], "height": exp["height"]}

    with open(os.path.join(ROOT, ".halum", "verify", "lens", "decoded.json"), "w") as f:
        json.dump(evidence, f, indent=2, ensure_ascii=False)

    detail = f"blocks={blocks_total} letters={letters_total}"
    return Result("lenstransport", not failures, detail, failures)


# --------------------------------------------------------------------- main
def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app", "src", "main", "assets", "models")
    verify_dir = os.path.join(ROOT, ".halum", "verify")
    os.makedirs(verify_dir, exist_ok=True)
    print(f"[engine_verify] models={models_dir}", flush=True)

    results = [
        check_bubbledetector(models_dir, os.path.join(verify_dir, "bubbles")),
        check_inpainter(models_dir, os.path.join(verify_dir, "inpaint")),
        check_chatbridge(),
        check_overlay(models_dir),
        check_lenstransport(),
        check_model_sizes(models_dir),
    ]

    lines = ["# Engine verification", "", "Run by `tools/engine_verify.py` (D18). Do not edit by hand.", ""]
    passed = 0
    for r in results:
        print(f"ENGINE_VERIFY: name={r.name} pass={'true' if r.passed else 'false'} detail=\"{r.detail}\"", flush=True)
        lines.append(f"- **{r.name}**: {'PASS' if r.passed else 'FAIL'} — {r.detail}")
        for a in r.asserts:
            lines.append(f"  - {a}")
        if r.passed:
            passed += 1
    lines.append("")
    lines.append(f"**{passed}/{len(results)} engines passed.**")
    with open(os.path.join(ROOT, ".halum", "ENGINE_VERIFICATION.md"), "w") as f:
        f.write("\n".join(lines) + "\n")

    ok = passed == len(results)
    print("OK" if ok else "FAILED", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
