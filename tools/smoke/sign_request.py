"""Assina um pedido de roll com uma chave Ed25519 efêmera e imprime o corpo do POST (chain-v2).

Uso: python3 tools/smoke/sign_request.py SESSION_ID SEED EXPRESSAO_CANONICA APELIDO
"""

from __future__ import annotations

import json
import secrets
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verify-py"))
from dice_verify.player import player_message  # noqa: E402


def main(argv: list[str]) -> int:
    session_id, seed, expression, name = argv
    key = Ed25519PrivateKey.generate()
    request_id = secrets.token_hex(16)
    public_key = key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw).hex()
    # Aparelho que é a própria identidade: identityKey = publicKey e nenhum vínculo.
    signature = key.sign(player_message(session_id, seed, expression, public_key, name, request_id))
    player = {"publicKey": public_key, "name": name, "requestId": request_id, "signature": signature.hex(),
              "identityKey": public_key, "links": []}
    print(json.dumps({"clientSeed": seed, "expression": expression, "player": player}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
