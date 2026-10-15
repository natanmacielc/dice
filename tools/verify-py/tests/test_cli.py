import json

from dice_verify.__main__ import main

R = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3"
SIG = "83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72"


def test_cli_prints_roll_and_beacon_check(capsys):
    code = main(["--randomness", R, "--seed", "mesa", "--nonce", "1", "--expr", "2d6+3", "--signature", SIG])
    out = json.loads(capsys.readouterr().out)
    assert code == 0
    assert out["beaconValid"] is True
    assert out["expression"] == "2d6+3"
    assert out["total"] == sum(out["dice"]) + 3


def test_cli_fails_on_tampered_signature(capsys):
    code = main(["--randomness", R, "--seed", "mesa", "--nonce", "1", "--expr", "1d20", "--signature", "93" + SIG[2:]])
    assert code == 1
    assert json.loads(capsys.readouterr().out)["beaconValid"] is False


def test_cli_reports_error_code(capsys):
    code = main(["--randomness", R, "--seed", "", "--nonce", "1", "--expr", "1d20"])
    assert code == 2
    assert json.loads(capsys.readouterr().err)["error"] == "SEED_EMPTY"
