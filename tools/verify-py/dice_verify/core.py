"""Algoritmo dice-v1, implementado a partir de spec/ALGORITHM.md."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field

ALGORITHM = "dice-v1"

SEED_MAX_BYTES = 64
NONCE_MAX = 2**53 - 1
COUNT_MIN, COUNT_MAX = 1, 20
MAX_ABS_MODIFIER = 100
SUPPORTED_SIDES = frozenset({4, 6, 8, 10, 12, 20, 100})
WORDS_PER_BLOCK = 8

_RANDOMNESS = re.compile(r"[0-9a-f]{64}", re.ASCII)
_SIGNATURE = re.compile(r"(?:[0-9a-f]{2})+", re.ASCII)
_EXPRESSION = re.compile(
    r"[ \t]*(0|[1-9][0-9]?)?[dD](0|[1-9][0-9]{0,2})(?:[ \t]*([+-])[ \t]*(0|[1-9][0-9]{0,2}))?[ \t]*",
    re.ASCII,
)


class DiceError(ValueError):
    """Entrada inválida. `code` segue os nomes da spec (ex.: SEED_TOO_LONG)."""

    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code


@dataclass(frozen=True)
class Expression:
    count: int
    sides: int
    modifier: int

    @property
    def canonical(self) -> str:
        base = f"{self.count}d{self.sides}"
        if self.modifier > 0:
            return f"{base}+{self.modifier}"
        if self.modifier < 0:
            return f"{base}-{-self.modifier}"
        return base


@dataclass(frozen=True)
class RollResult:
    expression: str
    dice: list[int]
    modifier: int
    total: int
    words_consumed: int
    rejections: list[tuple[int, int]] = field(default_factory=list)  # (wordIndex, value)


def parse_expression(text: str) -> Expression:
    m = _EXPRESSION.fullmatch(text)
    if m is None:
        raise DiceError("INVALID_EXPRESSION", f"expressão inválida: {text!r}")
    count = int(m.group(1)) if m.group(1) is not None else 1
    sides = int(m.group(2))
    modifier = int(m.group(4)) if m.group(3) is not None else 0
    if m.group(3) == "-":
        modifier = -modifier
    if not COUNT_MIN <= count <= COUNT_MAX:
        raise DiceError("DICE_COUNT_OUT_OF_RANGE", f"N fora de 1..20: {count}")
    if sides not in SUPPORTED_SIDES:
        raise DiceError("UNSUPPORTED_DIE", f"d{sides}")
    if abs(modifier) > MAX_ABS_MODIFIER:
        raise DiceError("MODIFIER_OUT_OF_RANGE", f"|M| > 100: {modifier}")
    return Expression(count, sides, modifier)


def _check_randomness(randomness: str) -> None:
    if not isinstance(randomness, str) or _RANDOMNESS.fullmatch(randomness) is None:
        raise DiceError("RANDOMNESS_FORMAT", "esperado 64 caracteres hex minúsculos")


def _encode_seed(seed: str) -> bytes:
    try:
        raw = seed.encode("utf-8")
    except UnicodeEncodeError as e:
        raise DiceError("SEED_INVALID_UTF8", str(e)) from None
    if not raw:
        raise DiceError("SEED_EMPTY", "clientSeed vazia")
    if len(raw) > SEED_MAX_BYTES:
        raise DiceError("SEED_TOO_LONG", f"{len(raw)} bytes")
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in seed):
        raise DiceError("SEED_CONTROL_CHAR", "caractere de controle")
    return raw


def _check_nonce(nonce: int) -> None:
    if isinstance(nonce, bool) or not isinstance(nonce, int) or not 0 <= nonce <= NONCE_MAX:
        raise DiceError("NONCE_OUT_OF_RANGE", f"nonce fora de [0, 2^53-1]: {nonce!r}")


def _words(randomness: str, seed_bytes: bytes, nonce: int):
    prefix = randomness.encode("ascii") + b"|" + seed_bytes + b"|" + str(nonce).encode("ascii") + b"|"
    counter = 0
    while True:
        block = hashlib.sha256(prefix + str(counter).encode("ascii")).digest()
        for i in range(WORDS_PER_BLOCK):
            yield int.from_bytes(block[4 * i : 4 * i + 4], "big")
        counter += 1


def roll(randomness: str, client_seed: str, nonce: int, expression: str) -> RollResult:
    """Valida na ordem da spec (randomness, clientSeed, nonce, expressão) e rola."""
    _check_randomness(randomness)
    seed_bytes = _encode_seed(client_seed)
    _check_nonce(nonce)
    expr = parse_expression(expression)

    limit = 2**32 - (2**32 % expr.sides)
    dice: list[int] = []
    rejections: list[tuple[int, int]] = []
    index = 0
    stream = _words(randomness, seed_bytes, nonce)
    while len(dice) < expr.count:
        v = next(stream)
        if v >= limit:
            rejections.append((index, v))
        else:
            dice.append(v % expr.sides + 1)
        index += 1
    return RollResult(expr.canonical, dice, expr.modifier, sum(dice) + expr.modifier, index, rejections)


def randomness_matches_signature(signature: str, randomness: str) -> bool:
    """randomness do drand = SHA-256(bytes da assinatura). Não verifica BLS."""
    _check_randomness(randomness)
    if not isinstance(signature, str) or _SIGNATURE.fullmatch(signature) is None:
        raise DiceError("SIGNATURE_FORMAT", "assinatura deve ser hex minúsculo com tamanho par")
    return hashlib.sha256(bytes.fromhex(signature)).hexdigest() == randomness


def verify_roll(
    randomness: str,
    client_seed: str,
    nonce: int,
    expression: str,
    claimed_dice: list[int],
    claimed_total: int,
    signature: str | None = None,
) -> tuple[bool, str | None]:
    """Recalcula e compara. Devolve (válido, motivo)."""
    try:
        if signature is not None and not randomness_matches_signature(signature, randomness):
            return False, "randomness não é SHA-256(signature)"
        actual = roll(randomness, client_seed, nonce, expression)
    except DiceError as e:
        return False, f"entrada inválida: {e}"
    if list(claimed_dice) != actual.dice:
        return False, f"dados declarados {list(claimed_dice)} != recalculados {actual.dice}"
    if claimed_total != actual.total:
        return False, f"total declarado {claimed_total} != recalculado {actual.total}"
    return True, None
