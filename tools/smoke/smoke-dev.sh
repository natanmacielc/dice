#!/usr/bin/env bash
# Smoke test pós-deploy: cria mesa e roll assinado reais, espera o drand, verifica a prova de forma
# independente (verificador Python) e confere página, imagem OG e headers de segurança.
# Precisa da pyca/cryptography (pip install -r tools/smoke/requirements.txt).
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
body="$(python3 "$ROOT/tools/smoke/sign_request.py" "$session" "smoke-${GITHUB_RUN_ID:-local}" 2d6+3 smoke)"
receipt="$(curl -fsS -X POST "$BASE/sessions/$session/rolls" -H 'content-type: application/json' -d "$body")"
roll="$(json "['rollId']" <<<"$receipt")"
echo "roll $roll (round alvo $(json "['targetRound']" <<<"$receipt"), jogador $(json "['player']['name']" <<<"$receipt"))"

echo "== aparelho vinculado (pareamento pela API) rola como a mesma identidade"
keys="$(mktemp)"
trap 'rm -f "$keys"' EXIT
linked="$(python3 "$ROOT/tools/smoke/pair_device.py" "$BASE" "$session" "smoke-linked-${GITHUB_RUN_ID:-local}" 1d20 smoke "$keys")"
linked_receipt="$(curl -fsS -X POST "$BASE/sessions/$session/rolls" -H 'content-type: application/json' -d "$linked")"
python3 -c "import json,sys; p=json.load(sys.stdin)['player']; assert p['identityKey'] != p['publicKey'], p; print('aparelho', p['publicKey'][:8], 'como', p['fingerprint'])" <<<"$linked_receipt"

echo "== lista de mesas (pedido assinado pelo aparelho vinculado)"
python3 "$ROOT/tools/smoke/my_tables.py" "$BASE" "$keys" | grep -qx "$session" || { echo "mesa $session fora da lista"; exit 1; }

echo "== resolução"
for _ in $(seq 1 15); do
  status="$(curl -fsS "$BASE/rolls/$roll" | json "['status']")"
  [ "$status" = RESOLVED ] && break
  sleep 2
done
[ "$status" = RESOLVED ] || { echo "roll não resolveu: $status"; exit 1; }

echo "== prova verificada pelo verificador Python independente"
curl -fsS "$BASE/rolls/$roll/proof" | (cd "$ROOT/tools/verify-py" && python3 -m dice_verify.proof -)
linked_roll="$(json "['rollId']" <<<"$linked_receipt")"
for _ in $(seq 1 15); do
  [ "$(curl -fsS "$BASE/rolls/$linked_roll" | json "['status']")" = RESOLVED ] && break
  sleep 2
done
curl -fsS "$BASE/rolls/$linked_roll/proof" | (cd "$ROOT/tools/verify-py" && python3 -m dice_verify.proof -)

echo "== página /r e imagem OG"
page="$(curl -fsS "$BASE/r/$roll")"
grep -q "<meta property=\"og:image\" content=\"$BASE/og/$roll.png\">" <<<"$page" || { echo "og:image inesperada"; exit 1; }
if [[ "$BASE" == https://* ]]; then
  curl -fsS -o /dev/null -w '%{content_type}\n' "$BASE/og/$roll.png" | grep -q '^image/png' || { echo "PNG ausente"; exit 1; }
fi

echo "smoke OK: $BASE/r/$roll"
