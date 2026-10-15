"""Lista as mesas de uma identidade com um pedido assinado (spec/ALGORITHM.md, seção 7.8) e imprime os IDs.

Uso: python3 tools/smoke/my_tables.py BASE ARQUIVO_CHAVES   (o arquivo vem de pair_device.py)
"""

from __future__ import annotations

import json
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verify-py"))
from dice_verify.chain import canonical_json  # noqa: E402


def main(argv: list[str]) -> int:
    base, keys_file = argv
    keys = json.loads(Path(keys_file).read_text(encoding="utf-8"))
    now = datetime.now(timezone.utc)
    at = now.strftime("%Y-%m-%dT%H:%M:%S.") + f"{now.microsecond // 1000:03d}Z"  # formato de requestedAt
    message = canonical_json({"context": "dice-tables-v1", "action": "list", "identityKey": keys["identityKey"], "at": at})
    signature = Ed25519PrivateKey.from_private_bytes(bytes.fromhex(keys["sk"])).sign(message.encode("utf-8")).hex()
    auth = {"publicKey": keys["publicKey"], "identityKey": keys["identityKey"], "links": keys["links"], "at": at,
            "signature": signature}
    req = urllib.request.Request(base + "/me/tables", data=json.dumps({"auth": auth, "action": "list"}).encode("utf-8"),
                                 method="POST", headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        for table in json.loads(resp.read())["tables"]:
            print(table["sessionId"])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
