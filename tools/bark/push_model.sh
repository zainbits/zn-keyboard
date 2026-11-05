#!/usr/bin/env bash
# Copies the Bark 3.0 Clean model folder to a connected phone, with checksums. The app never
# bundles the model: in Settings -> Tools -> Voice typing, tap "Choose model folder" and pick it.
#
# Usage: tools/bark/push_model.sh [MODEL_DIR] [PHONE_DIR]
#   MODEL_DIR  folder with preprocessor.onnx, encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt
#              (default: ~/zainbits/bark-onnx/out/release, made by ~/zainbits/bark-onnx)
#   PHONE_DIR  default: /sdcard/Documents/ZnKeyboard/bark-model
set -euo pipefail

MODEL_DIR="${1:-$HOME/zainbits/bark-onnx/out/release}"
PHONE_DIR="${2:-/sdcard/Documents/ZnKeyboard/bark-model}"
ADB="${ADB:-$(command -v adb || echo "$HOME/Library/Android/sdk/platform-tools/adb")}"

for f in preprocessor.onnx encoder.onnx decoder.onnx joiner.onnx tokens.txt; do
  [[ -f "$MODEL_DIR/$f" ]] || { echo "Missing $MODEL_DIR/$f" >&2; exit 1; }
done

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for f in preprocessor.onnx encoder.onnx decoder.onnx joiner.onnx tokens.txt NOTICE README.txt; do
  [[ -e "$MODEL_DIR/$f" ]] && cp -L "$MODEL_DIR/$f" "$tmp/$f"
done
(cd "$tmp" && shasum -a 256 * > SHA256SUMS)

"$ADB" shell mkdir -p "$PHONE_DIR"
"$ADB" push "$tmp/." "$PHONE_DIR/"
"$ADB" shell "cd '$PHONE_DIR' && sha256sum -c SHA256SUMS"
echo "On the phone: Settings -> Tools -> Voice typing -> Choose model folder -> ${PHONE_DIR#/sdcard/}"
