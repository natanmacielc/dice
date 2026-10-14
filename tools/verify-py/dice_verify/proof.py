"""CLI: python3 -m dice_verify.proof prova.json  (ou '-' para ler da entrada padrão)."""

from __future__ import annotations

import argparse
import json
import sys

from .chain import verify_proof


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="dice_verify.proof", description="Verifica a prova de um roll (chain-v1).")
    p.add_argument("file", help="arquivo JSON da prova, ou - para stdin")
    args = p.parse_args(argv)
    text = sys.stdin.read() if args.file == "-" else open(args.file, encoding="utf-8").read()
    valid, code, message = verify_proof(json.loads(text))
    out = {"valid": valid}
    if valid:
        proof = json.loads(text)
        out.update(rollId=proof["roll"]["rollId"], dice=proof["roll"]["dice"], total=proof["roll"]["total"],
                   round=proof["beacon"]["round"])
    else:
        out.update(error=code, message=message)
    print(json.dumps(out, ensure_ascii=False))
    return 0 if valid else 1


if __name__ == "__main__":
    sys.exit(main())
