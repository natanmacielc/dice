"""Verificador independente do Dado Verificável (algoritmo dice-v1). Só stdlib."""

from .core import (
    ALGORITHM,
    DiceError,
    Expression,
    RollResult,
    parse_expression,
    randomness_matches_signature,
    roll,
    verify_roll,
)

__all__ = [
    "ALGORITHM",
    "DiceError",
    "Expression",
    "RollResult",
    "parse_expression",
    "randomness_matches_signature",
    "roll",
    "verify_roll",
]
