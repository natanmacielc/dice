#!/usr/bin/env bash
# Gera web/vendor/qrcode-generator-2.0.4.mjs a partir de versões fixas (package-lock.json).
# Uso: tools/vendor/qrcode/build.sh
set -euo pipefail
cd "$(dirname "$0")"
npm ci --ignore-scripts --no-audit --no-fund
out=../../../web/vendor/qrcode-generator-2.0.4.mjs
# O pacote não traz comentário legal que o esbuild preserve; a licença MIT exige o aviso na cópia.
license='/*! qrcode-generator 2.0.4 | Copyright (c) 2009 Kazuhiko Arase | MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
 * following conditions: The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 */'
./node_modules/.bin/esbuild entry.mjs --bundle --format=esm --platform=browser --target=es2022 \
  --minify --legal-comments=eof --banner:js="$license" --outfile="$out"
shasum -a 256 "$out"
