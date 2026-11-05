#!/usr/bin/env bash
# Copies the Bark 3.0 Clean speech model into the (gitignored) app assets:
#   app/src/main/assets/bark/  preprocessor.onnx, encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt, NOTICE
#
# Usage: tools/bark/setup.sh [MODEL_DIR] [PREPROCESSOR]
#   MODEL_DIR     encoder/decoder/joiner/tokens export (default: exact 4-bit weights + int8 activations, ~2% WER vs original, ~2x faster than exact)
#   PREPROCESSOR  feature extractor ONNX matching the original model (default: out/preprocessor.onnx)
# Both come from ~/zainbits/bark-onnx (see its README for how they were made and checked).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$HOME/zainbits/bark-onnx/out"
MODEL_DIR="${1:-$SRC/v_exact4_b128_acc4}"
PREPROCESSOR="${2:-$SRC/preprocessor.onnx}"
ASSETS="$ROOT/app/src/main/assets/bark"
NOTICE_SRC="$HOME/zainbits/bark-server/models/bark-3.0-clean/NOTICE"

for f in "$MODEL_DIR/encoder.onnx" "$MODEL_DIR/decoder.onnx" "$MODEL_DIR/joiner.onnx" "$MODEL_DIR/tokens.txt" "$PREPROCESSOR"; do
  [[ -f "$f" ]] || { echo "Missing $f" >&2; exit 1; }
done
# Models are memory-mapped from the APK one file each; external-data exports can't load that way.
if ls "$MODEL_DIR"/*.weights >/dev/null 2>&1 || ls "$MODEL_DIR"/*.data >/dev/null 2>&1; then
  echo "$MODEL_DIR uses external weight files; use a single-file export." >&2
  exit 1
fi

rm -rf "$ASSETS"
mkdir -p "$ASSETS"
# cp follows the symlinks the bark-onnx variant folders use for shared files.
cp -L "$PREPROCESSOR" "$ASSETS/preprocessor.onnx"
cp -L "$MODEL_DIR/encoder.onnx" "$ASSETS/encoder.onnx"
cp -L "$MODEL_DIR/decoder.onnx" "$ASSETS/decoder.onnx"
cp -L "$MODEL_DIR/joiner.onnx" "$ASSETS/joiner.onnx"
cp -L "$MODEL_DIR/tokens.txt" "$ASSETS/tokens.txt"
[[ -f "$NOTICE_SRC" ]] && cp "$NOTICE_SRC" "$ASSETS/NOTICE"
echo "Bark model from $MODEL_DIR:"
du -h "$ASSETS"/*
