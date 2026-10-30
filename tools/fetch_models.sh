#!/usr/bin/env bash
# Downloads and exports the models Halum ships inside the APK.
#
# This script is CI-only by design (DECISIONS D3): models are never committed.
# It runs on ubuntu-latest with python3 + pip available; it is not expected to
# run on Termux.
#
# Results land in app/src/main/assets/models/:
#   bubble.tflite(+-bubble_fp16.tflite)
#   migan.tflite   (or nothing => InpaintEngine falls back to Telea-only)
#
# Exit codes:
#   0  bubble model present (required). MI-GAN optional.
#   1  bubble model could not be produced (hard failure).
#
# Overridable for local experimentation:
#   HALUM_MODELS_DIR .  CACHE_DIR
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODELS_DIR="${HALUM_MODELS_DIR:-$ROOT/app/src/main/assets/models}"
# D21: MI-GAN lives only in the `full` flavor's assets, so the standard APK
# (Telea + solid-fill) stays small. bubble.tflite is shared by both flavors.
FULL_MODELS_DIR="${HALUM_FULL_MODELS_DIR:-$ROOT/app/src/full/assets/models}"
CACHE_DIR="${CACHE_DIR:-$ROOT/.cache/models}"
WORK="$CACHE_DIR/work"
ULTRALYTICS_VERSION="${ULTRALYTICS_VERSION:-8.4.193}"

BUBBLE_REPO="https://huggingface.co/juithealien/manga109-segmentation-bubble/resolve/main/best.pt"
# D4: MI-GAN ships as an ONNX (~16 MB, MIT, Picsart ICCV 2023). Primary is the
# community mirror the model card documents; the backup is a second HF mirror
# used when the first is rate-limited in CI. An explicit MIGAN_ONNX_URL, if
# set in the environment, overrides both.
MIGAN_URLS=(
  "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan.onnx"
  "https://huggingface.co/lxfater/inpaint-web/resolve/main/migan.onnx"
)
MIGAN_ONNX="${MIGAN_ONNX_URL:-}"

# Sample manga pages: used for INT8 calibration and as the recall fixture
# source. Keep in sync with fixtures/bubbles/images.
SAMPLE_BASE="https://raw.githubusercontent.com/zbu1/manga-translator-cli/main/docs"
SAMPLES=(frieren_raw.jpg motl_raw.png wha_raw.jpg ajin_raw.jpg)

log() { printf '[fetch_models] %s\n' "$*"; }
die() { printf '[fetch_models] ERROR: %s\n' "$*" >&2; exit 1; }

mkdir -p "$MODELS_DIR" "$WORK/samples"
rm -f "$MODELS_DIR"/*.tflite

PY="${PYTHON:-python3}"

# ---------------------------------------------------------------- bubble model
log "downloading bubble model"
BEST="$CACHE_DIR/best.pt"
if [ ! -s "$BEST" ]; then
  curl -fL --retry 3 --retry-delay 5 -o "$BEST" "$BUBBLE_REPO" \
    || die "could not download $BUBBLE_REPO"
fi
log "best.pt: $(wc -c <"$BEST") bytes"

for s in "${SAMPLES[@]}"; do
  dst="$WORK/samples/${s%_*}.jpg"
  [ -s "$dst" ] || curl -fL --retry 3 -o "$dst" "$SAMPLE_BASE/$s" || log "sample $s unavailable"
done

log "installing python deps"
"$PY" -m pip install --quiet --disable-pip-version-check \
  "ultralytics==$ULTRALYTICS_VERSION" pillow numpy onnx 2>/dev/null \
  || "$PY" -m pip install --quiet --disable-pip-version-check \
       "ultralytics==$ULTRALYTICS_VERSION" pillow numpy onnx

log "exporting bubble model (int8 preferred, fp16 fallback)"
"$PY" "$ROOT/tools/export_bubble.py" --pt "$BEST" --out "$MODELS_DIR" --work "$WORK" \
  || die "bubble export failed"
ls -l "$MODELS_DIR"/bubble*.tflite || die "bubble export produced no tflite"

# ---------------------------------------------------------------- MI-GAN
# D4: try the explicit URL first, then each documented mirror. A download that
# succeeds is logged with its size; if every source fails, InpaintEngine falls
# back to Telea/solid fill and AC4 is measured against Telea instead.
migan_get() {
  local url="$1" dest="$2"
  curl -fL --retry 3 --retry-delay 5 -o "$dest" "$url" \
    && [ -s "$dest" ] && [ "$(wc -c <"$dest")" -gt 1000000 ]
}

migan_onnx=""
if [ -n "$MIGAN_ONNX" ]; then
  log "downloading MI-GAN from \$MIGAN_ONNX_URL"
  if migan_get "$MIGAN_ONNX" "$WORK/migan.onnx"; then migan_onnx="$WORK/migan.onnx"; fi
fi
if [ -z "$migan_onnx" ]; then
  for url in "${MIGAN_URLS[@]}"; do
    log "downloading MI-GAN from $url"
    if migan_get "$url" "$WORK/migan.onnx"; then migan_onnx="$WORK/migan.onnx"; break; fi
    log "MI-GAN mirror failed: $url"
  done
fi

if [ -n "$migan_onnx" ]; then
  log "MIGAN_DOWNLOAD: url=$migan_onnx sizeMB=$(awk "BEGIN{printf \"%.1f\", $(wc -c <"$migan_onnx")/1e6}") pass=true"
  mkdir -p "$FULL_MODELS_DIR"
  "$PY" "$ROOT/tools/export_migan.py" --onnx "$migan_onnx" --out "$FULL_MODELS_DIR" \
    || log "MIGAN_UNAVAILABLE: reason=export_failed fallback=telea"
else
  log "MIGAN_UNAVAILABLE: reason=download_failed fallback=telea"
fi

log "bubble model in $MODELS_DIR:"
ls -l "$MODELS_DIR" | sed 's/^/  /'
log "full-flavor models in $FULL_MODELS_DIR:"
ls -l "$FULL_MODELS_DIR" 2>/dev/null | sed 's/^/  /' || true
