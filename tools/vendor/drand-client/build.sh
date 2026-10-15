#!/usr/bin/env bash
# Gera web/vendor/drand-client-1.4.2.mjs a partir de versões fixas (package-lock.json).
# Uso: tools/vendor/drand-client/build.sh
set -euo pipefail
cd "$(dirname "$0")"
npm ci --ignore-scripts --no-audit --no-fund
out=../../../web/vendor/drand-client-1.4.2.mjs
./node_modules/.bin/esbuild entry.mjs --bundle --format=esm --platform=browser --target=es2022 \
  --minify --legal-comments=eof --outfile="$out"
shasum -a 256 "$out"
