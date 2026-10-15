"""Roda spec/test-vectors.json, os mesmos vetores do Java e do JS."""

import json
from pathlib import Path

import pytest

from dice_verify import DiceError, randomness_matches_signature, roll, verify_roll

VECTORS = json.loads(
    (Path(__file__).resolve().parents[3] / "spec" / "test-vectors.json").read_text(encoding="utf-8")
)


def _ids(items):
    return [v["id"] for v in items]


def test_header_and_minimum_count():
    assert VECTORS["algorithm"] == "dice-v1"
    total = sum(len(VECTORS[k]) for k in ("beaconVectors", "rollVectors", "errorVectors"))
    assert total >= 30


@pytest.mark.parametrize("v", VECTORS["beaconVectors"], ids=_ids(VECTORS["beaconVectors"]))
def test_beacon(v):
    assert randomness_matches_signature(v["signature"], v["randomness"]) is v["valid"]


@pytest.mark.parametrize("v", VECTORS["rollVectors"], ids=_ids(VECTORS["rollVectors"]))
def test_roll(v):
    assert v["clientSeed"].encode("utf-8").hex() == v["clientSeedUtf8Hex"]
    r = roll(v["randomness"], v["clientSeed"], v["nonce"], v["expression"])
    exp = v["expected"]
    assert r.expression == exp["expression"]
    assert r.dice == exp["dice"]
    assert r.modifier == exp["modifier"]
    assert r.total == exp["total"]
    assert r.words_consumed == exp["wordsConsumed"]
    assert r.rejections == [(j["wordIndex"], j["value"]) for j in exp["rejections"]]
    assert verify_roll(v["randomness"], v["clientSeed"], v["nonce"], v["expression"], r.dice, r.total) == (True, None)


@pytest.mark.parametrize("v", VECTORS["errorVectors"], ids=_ids(VECTORS["errorVectors"]))
def test_error(v):
    with pytest.raises(DiceError) as e:
        roll(v["randomness"], v["clientSeed"], v["nonce"], v["expression"])
    assert e.value.code == v["error"]
