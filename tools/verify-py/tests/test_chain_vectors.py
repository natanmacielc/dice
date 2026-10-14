"""Seções chain-v1 de spec/test-vectors.json, as mesmas do Java e do JS."""

import json
from pathlib import Path

import pytest

from dice_verify.chain import ChainError, canonical_json, time_of, verify_proof, verify_segment
from dice_verify.proof import main as proof_main

VECTORS = json.loads(
    (Path(__file__).resolve().parents[3] / "spec" / "test-vectors.json").read_text(encoding="utf-8")
)


def _ids(items):
    return [v["id"] for v in items]


def test_chain_format():
    assert VECTORS["chainFormat"] == "chain-v1"


@pytest.mark.parametrize("v", VECTORS["canonicalJsonVectors"], ids=_ids(VECTORS["canonicalJsonVectors"]))
def test_canonical(v):
    assert canonical_json(v["value"]) == v["canonical"]


@pytest.mark.parametrize("v", VECTORS["logVectors"], ids=_ids(VECTORS["logVectors"]))
def test_log(v):
    valid, code, message = verify_segment(v["entries"], v["prevHashOfFirst"])
    assert valid is v["valid"], message
    if not v["valid"]:
        assert code == v["error"], message


@pytest.mark.parametrize("v", VECTORS["proofVectors"], ids=_ids(VECTORS["proofVectors"]))
def test_proof(v):
    valid, code, message = verify_proof(v["proof"])
    assert valid is v["valid"], message
    if not v["valid"]:
        assert code == v["error"], message


@pytest.mark.parametrize("bad", [True, None, 1.5, [1, None], {"a": False}, 2**53])
def test_canonical_rejects(bad):
    with pytest.raises(ChainError) as e:
        canonical_json(bad)
    assert e.value.code == "CHAIN_STRUCTURE"


def test_time_of_genesis():
    assert time_of(1) == 1692803367


def test_proof_cli(tmp_path, capsys):
    valid = next(v for v in VECTORS["proofVectors"] if v["valid"])
    f = tmp_path / "p.json"
    f.write_text(json.dumps(valid["proof"]), encoding="utf-8")
    assert proof_main([str(f)]) == 0
    assert json.loads(capsys.readouterr().out)["valid"] is True
    bad = next(v for v in VECTORS["proofVectors"] if not v["valid"])
    f.write_text(json.dumps(bad["proof"]), encoding="utf-8")
    assert proof_main([str(f)]) == 1
    assert json.loads(capsys.readouterr().out)["error"] == bad["error"]
