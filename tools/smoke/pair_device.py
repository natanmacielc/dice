"""Pareia um aparelho novo pela API (spec/ALGORITHM.md, seção 7.7) e imprime um corpo de roll assinado por ele.

Dois aparelhos efêmeros: A (a identidade) e B. Faz as quatro etapas do pareamento contra BASE, com os
MACs de verdade, e devolve o POST de roll de B, que rola como a identidade de A.

Uso: python3 tools/smoke/pair_device.py BASE SESSION_ID SEED EXPRESSAO_CANONICA APELIDO
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import sys
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verify-py"))
from dice_verify.chain import canonical_json  # noqa: E402
from dice_verify.player import link_message, links_valid, player_message  # noqa: E402


def call(base: str, method: str, path: str, body: dict | None = None) -> dict:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, method=method, headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read())


def public_hex(key: Ed25519PrivateKey) -> str:
    return key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw).hex()


def mac(secret: bytes, text: str) -> str:
    return hmac.new(secret, text.encode("utf-8"), hashlib.sha256).hexdigest()


def main(argv: list[str]) -> int:
    base, session_id, seed, expression, name = argv
    a, b = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
    identity, device = public_hex(a), public_hex(b)
    secret = secrets.token_bytes(16)

    pid = call(base, "POST", "/pairings", {})["pairingId"]
    call(base, "POST", f"/pairings/{pid}/device",
         {"deviceKey": device, "mac": mac(secret, f"dice-pair-v1|device|{pid}|{device}")})

    seen = call(base, "GET", f"/pairings/{pid}")["device"]
    if not hmac.compare_digest(seen["mac"], mac(secret, f"dice-pair-v1|device|{pid}|{seen['deviceKey']}")):
        raise SystemExit("MAC do aparelho não confere")
    links = [{"deviceKey": seen["deviceKey"], "signature": a.sign(link_message(identity, seen["deviceKey"])).hex()}]
    pkg = {"identityKey": identity, "links": links, "name": name}
    call(base, "POST", f"/pairings/{pid}/identity",
         {**pkg, "mac": mac(secret, f"dice-pair-v1|identity|{pid}|{canonical_json(pkg)}")})

    got = call(base, "GET", f"/pairings/{pid}")["identity"]
    expected = mac(secret, "dice-pair-v1|identity|" + pid + "|"
                   + canonical_json({k: got[k] for k in ("identityKey", "links", "name")}))
    if not hmac.compare_digest(got["mac"], expected) or not links_valid(got["identityKey"], got["links"], device):
        raise SystemExit("pacote de vínculos não confere")

    request_id = secrets.token_hex(16)
    signature = b.sign(player_message(session_id, seed, expression, got["identityKey"], name, request_id))
    player = {"publicKey": device, "name": name, "requestId": request_id, "signature": signature.hex(),
              "identityKey": got["identityKey"], "links": got["links"]}
    print(json.dumps({"clientSeed": seed, "expression": expression, "player": player}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
