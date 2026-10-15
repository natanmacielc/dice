#!/usr/bin/env bash
# Smoke test pós-deploy: cria mesa e roll reais, espera o drand, verifica a prova de forma
# independente (verificador Python) e confere página, imagem OG e headers de segurança.
# Uso: tools/smoke/smoke-dev.sh https://dices.nimbos.solutions
set -euo pipefail
BASE="${1:?uso: smoke-dev.sh <url>}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
json() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }

echo "== página inicial e headers"
headers="$(curl -fsS -D - -o /dev/null "$BASE/")"
required=(content-security-policy x-content-type-options)
# HSTS e /og/* vêm do CloudFront; rodando local (http) não existem.
[[ "$BASE" == https://* ]] && required+=(strict-transport-security)
for h in "${required[@]}"; do
  grep -qi "^$h:" <<<"$headers" || { echo "faltando header $h"; exit 1; }
done

echo "== mesa e roll"
session="$(curl -fsS -X POST "$BASE/sessions" -H 'content-type: application/json' -d '{"name":"smoke"}' | json "['sessionId']")"
receipt="$(curl -fsS -X POST "$BASE/sessions/$session/rolls" -H 'content-type: application/json' \
  -d "{\"clientSeed\":\"smoke-${GITHUB_RUN_ID:-local}\",\"expression\":\"2d6+3\"}")"
roll="$(json "['rollId']" <<<"$receipt")"
echo "roll $roll (round alvo $(json "['targetRound']" <<<"$receipt"))"

echo "== resolução"
for _ in $(seq 1 15); do
  status="$(curl -fsS "$BASE/rolls/$roll" | json "['status']")"
  [ "$status" = RESOLVED ] && break
  sleep 2
done
[ "$status" = RESOLVED ] || { echo "roll não resolveu: $status"; exit 1; }

echo "== prova verificada pelo verificador Python independente"
curl -fsS "$BASE/rolls/$roll/proof" | (cd "$ROOT/tools/verify-py" && python3 -m dice_verify.proof -)

echo "== página /r e imagem OG"
page="$(curl -fsS "$BASE/r/$roll")"
grep -q "<meta property=\"og:image\" content=\"$BASE/og/$roll.png\">" <<<"$page" || { echo "og:image inesperada"; exit 1; }
if [[ "$BASE" == https://* ]]; then
  curl -fsS -o /dev/null -w '%{content_type}\n' "$BASE/og/$roll.png" | grep -q '^image/png' || { echo "PNG ausente"; exit 1; }
fi

echo "smoke OK: $BASE/r/$roll"
