"""CLI: python3 -m dice_verify --randomness HEX --seed TEXTO --nonce N --expr 2d6+3 [--signature HEX]"""

from __future__ import annotations

import argparse
import json
import sys

from .core import ALGORITHM, DiceError, randomness_matches_signature, roll


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="dice_verify", description=f"Recalcula um roll ({ALGORITHM}).")
    p.add_argument("--randomness", required=True, help="randomness do round alvo (64 hex)")
    p.add_argument("--seed", required=True, help="clientSeed (UTF-8)")
    p.add_argument("--nonce", required=True, type=int)
    p.add_argument("--expr", required=True, help="expressão, ex.: 2d6+3")
    p.add_argument("--signature", help="assinatura do round; confere randomness = SHA-256(signature)")
    args = p.parse_args(argv)

    try:
        out: dict = {"algorithm": ALGORITHM}
        if args.signature is not None:
            out["beaconValid"] = randomness_matches_signature(args.signature, args.randomness)
        r = roll(args.randomness, args.seed, args.nonce, args.expr)
    except DiceError as e:
        print(json.dumps({"error": e.code, "message": str(e)}, ensure_ascii=False), file=sys.stderr)
        return 2
    out.update(
        expression=r.expression,
        dice=r.dice,
        modifier=r.modifier,
        total=r.total,
        wordsConsumed=r.words_consumed,
        rejections=[{"wordIndex": i, "value": v} for i, v in r.rejections],
    )
    print(json.dumps(out, ensure_ascii=False))
    return 1 if out.get("beaconValid") is False else 0


if __name__ == "__main__":
    sys.exit(main())
