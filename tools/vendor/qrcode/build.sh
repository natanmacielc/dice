#!/usr/bin/env bash
# Gera web/vendor/qrcode-generator-2.0.4.mjs a partir de versões fixas (package-lock.json).
# Uso: tools/vendor/qrcode/build.sh
set -euo pipefail
cd "$(dirname "$0")"
npm ci --ignore-scripts --no-audit --no-fund
out=../../../web/vendor/qrcode-generator-2.0.4.mjs
./node_modules/.bin/esbuild entry.mjs --bundle --format=esm --platform=browser --target=es2022 \
  --minify --legal-comments=eof --outfile="$out"
shasum -a 256 "$out"
