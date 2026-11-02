#!/usr/bin/env bash
# Downloads the PP-OCRv6 Small LiteRT release used by the camera OCR lab into
# app/src/main/assets/ppocrv6/ (gitignored) and verifies the published SHA-256s.
set -euo pipefail

REPO="https://huggingface.co/litert-community/PP-OCRv6-Small-LiteRT/resolve/main"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/app/src/main/assets/ppocrv6"

FILES=(
  "ppocrv6_small_det_640_fp32.tflite ec9430b6e592ffb28e83b6bab539262a30d267d2475c136b193926ad83835768"
  "ppocrv6_small_rec_320_fp32.tflite 569bae77214a57476106ae66996f4ba4ef3ac66f81d150966c44e31a8fb0ae36"
  "ppocrv6_small_rec_640_fp32.tflite 904c9a589763123d8a4c01d205c78669cfe3723b2a23b2221bdeb26fccc66a5d"
  "ppocrv6_small_rec_960_fp32.tflite cc72f0f41729e0e5d66194ff657d25cdd10646a2066a780e01b13357135e553e"
  "characters.json 9c89db8aa95ad30c83ee305d53d366c6bba3f85ff2eab0375ef345fd50b0674a"
)

mkdir -p "$DEST"
for entry in "${FILES[@]}"; do
  name="${entry%% *}"
  expected="${entry##* }"
  target="$DEST/$name"
  if [[ -f "$target" ]] && [[ "$(shasum -a 256 "$target" | cut -d' ' -f1)" == "$expected" ]]; then
    echo "ok       $name"
    continue
  fi
  echo "fetching $name"
  curl -fsSL -o "$target.part" "$REPO/$name"
  actual="$(shasum -a 256 "$target.part" | cut -d' ' -f1)"
  if [[ "$actual" != "$expected" ]]; then
    rm -f "$target.part"
    echo "SHA-256 mismatch for $name: $actual" >&2
    exit 1
  fi
  mv "$target.part" "$target"
done
echo "PP-OCRv6 Small models ready in $DEST"
