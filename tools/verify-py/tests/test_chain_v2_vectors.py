"""Seções chain-v2 de spec/test-vectors.json, as mesmas do Java e do JS."""

import hashlib
import json
from pathlib import Path

import pytest

from dice_verify.chain import CHAIN_FORMAT_V2, verify_proof, verify_segment
from dice_verify.player import fingerprint, link_message, name_problem, player_message, verify_ed25519
from dice_verify.proof import main as proof_main

VECTORS = json.loads(
    (Path(__file__).resolve().parents[3] / "spec" / "test-vectors.json").read_text(encoding="utf-8")
)


def _ids(items):
    return [v["id"] for v in items]


@pytest.mark.parametrize("v", VECTORS["playerSignatureVectors"], ids=_ids(VECTORS["playerSignatureVectors"]))
def test_player_signature(v):
    if "request" in v:
        r = v["request"]
        built = player_message(
            r["sessionId"], r["clientSeed"], r["expression"], r["identityKey"], r["name"], r["requestId"]
        )
        assert built.decode("utf-8") == v["message"]
        assert built.hex() == v["messageHex"]
    assert verify_ed25519(v["publicKey"], bytes.fromhex(v["messageHex"]), v["signature"]) is v["valid"]


@pytest.mark.parametrize("v", VECTORS["linkSignatureVectors"], ids=_ids(VECTORS["linkSignatureVectors"]))
def test_link_signature(v):
    built = link_message(v["link"]["identityKey"], v["link"]["deviceKey"])
    assert built.decode("utf-8") == v["message"]
    assert built.hex() == v["messageHex"]
    assert verify_ed25519(v["issuerKey"], built, v["signature"]) is v["valid"]


@pytest.mark.parametrize("v", VECTORS["logVectorsV2"], ids=_ids(VECTORS["logVectorsV2"]))
def test_log_v2(v):
    valid, code, message = verify_segment(v["entries"], v["prevHashOfFirst"], CHAIN_FORMAT_V2)
    assert valid is v["valid"], message
    if not v["valid"]:
        assert code == v["error"], message


@pytest.mark.parametrize("v", VECTORS["proofVectorsV2"], ids=_ids(VECTORS["proofVectorsV2"]))
def test_proof_v2(v):
    valid, code, message = verify_proof(v["proof"])
    assert valid is v["valid"], message
    if not v["valid"]:
        assert code == v["error"], message


def test_fingerprint():
    key = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    h = hashlib.sha256(bytes.fromhex(key)).hexdigest()[:8]
    assert fingerprint(key) == f"{h[:4]}-{h[4:]}"


def test_name_rules():
    assert name_problem("Ana") is None
    assert name_problem("é" * 16) is None
    for bad in ["é" * 16 + "a", "", "a\tb", "a\x7f", "a\ud800", None]:
        assert name_problem(bad) is not None


def test_proof_cli_v2(tmp_path, capsys):
    valid = next(v for v in VECTORS["proofVectorsV2"] if v["valid"] and v["proof"]["chainFormat"] == CHAIN_FORMAT_V2)
    f = tmp_path / "p.json"
    f.write_text(json.dumps(valid["proof"]), encoding="utf-8")
    assert proof_main([str(f)]) == 0
    out = json.loads(capsys.readouterr().out)
    assert out["chainFormat"] == CHAIN_FORMAT_V2
    assert out["player"]["name"] == valid["proof"]["roll"]["player"]["name"]
