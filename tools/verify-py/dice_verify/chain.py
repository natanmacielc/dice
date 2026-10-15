"""Cadeias chain-v1 e chain-v2 e prova de um roll (spec/ALGORITHM.md, seções 6 e 7).

A chain-v1 usa só a stdlib; a assinatura Ed25519 da chain-v2 usa a pyca/cryptography.
"""

from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone

from .core import DiceError, parse_expression, randomness_matches_signature, roll

CHAIN_FORMAT = "chain-v1"
CHAIN_FORMAT_V2 = "chain-v2"
CHAIN_FORMATS = (CHAIN_FORMAT, CHAIN_FORMAT_V2)
QUICKNET_NETWORK = "quicknet"
QUICKNET_CHAIN_HASH = "52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971"
QUICKNET_GENESIS = 1692803367
QUICKNET_PERIOD = 3
GENESIS_PREV_HASH = "0" * 64
MAX_SAFE_INTEGER = 2**53 - 1

_TYPES = frozenset({"REQUEST", "ROUND", "RESULT"})
_HEX64 = re.compile(r"[0-9a-f]{64}", re.ASCII)
_INSTANT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z", re.ASCII)
_SHORT_ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\t": "\\t", "\n": "\\n", "\f": "\\f", "\r": "\\r"}


class ChainError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code


def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def _escape(s: str) -> str:
    out = ['"']
    for ch in s:
        if 0xD800 <= ord(ch) <= 0xDFFF:
            raise ChainError("CHAIN_STRUCTURE", "string com surrogate sem par")
        if ch in _SHORT_ESCAPES:
            out.append(_SHORT_ESCAPES[ch])
        elif ord(ch) < 0x20:
            out.append(f"\\u{ord(ch):04x}")
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def _utf16_key(s: str) -> bytes:
    return s.encode("utf-16-be")  # ordem por unidades UTF-16, como o JCS


def canonical_json(value) -> str:
    """JSON canônico (seção 6.3). Lança ChainError(CHAIN_STRUCTURE) para tipos proibidos."""
    if isinstance(value, str):
        return _escape(value)
    if _is_int(value):
        if abs(value) > MAX_SAFE_INTEGER:
            raise ChainError("CHAIN_STRUCTURE", f"inteiro fora de ±(2^53-1): {value}")
        return str(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(v) for v in value) + "]"
    if isinstance(value, dict):
        if not all(isinstance(k, str) for k in value):
            raise ChainError("CHAIN_STRUCTURE", "chave não-string")
        keys = sorted(value, key=_utf16_key)
        return "{" + ",".join(f"{_escape(k)}:{canonical_json(value[k])}" for k in keys) + "}"
    raise ChainError("CHAIN_STRUCTURE", f"tipo não permitido: {type(value).__name__}")


def entry_hash(prev_hash: str, type_: str, payload: dict) -> str:
    """hash = SHA-256(utf8(prevHash) || utf8(type) || utf8(canonical(payload)))"""
    return hashlib.sha256((prev_hash + type_ + canonical_json(payload)).encode("utf-8")).hexdigest()


def time_of(round_: int) -> int:
    """Instante de publicação do round, em segundos desde a epoch."""
    return QUICKNET_GENESIS + (round_ - 1) * QUICKNET_PERIOD


def _check_entry(e, previous, prev_hash_of_first: str) -> None:
    # 1. Estrutura
    if (
        not isinstance(e, dict)
        or not _is_int(e.get("seq"))
        or e["seq"] < 1
        or e.get("type") not in _TYPES
        or not isinstance(e.get("prevHash"), str)
        or _HEX64.fullmatch(e["prevHash"]) is None
        or not isinstance(e.get("hash"), str)
        or _HEX64.fullmatch(e["hash"]) is None
        or not isinstance(e.get("payload"), dict)
    ):
        raise ChainError("CHAIN_STRUCTURE", "entrada mal formada")
    canonical = canonical_json(e["payload"])
    # 2. Contiguidade
    if previous is not None and e["seq"] != previous["seq"] + 1:
        raise ChainError("CHAIN_SEQ_GAP", f"seq {e['seq']} depois de {previous['seq']}")
    # 3. Seq no payload
    ps = e["payload"].get("seq")
    if not _is_int(ps) or ps != e["seq"]:
        raise ChainError("CHAIN_SEQ_MISMATCH", f"payload.seq {ps!r} != seq {e['seq']}")
    # 4. Encadeamento
    expected_prev = prev_hash_of_first if previous is None else previous["hash"]
    if e["prevHash"] != expected_prev:
        raise ChainError("CHAIN_LINK_BROKEN", f"prevHash da seq {e['seq']} não encadeia")
    # 5. Hash
    if hashlib.sha256((e["prevHash"] + e["type"] + canonical).encode("utf-8")).hexdigest() != e["hash"]:
        raise ChainError("CHAIN_HASH_MISMATCH", f"hash da seq {e['seq']} não confere")


def _check_player(e, seen: set) -> None:
    """chain-v2: 6. player bem formado; 7. vínculos até o aparelho; 8. assinatura do pedido;
    9. requestId inédito no segmento."""
    from .player import links_valid, player_message, player_problem, verify_ed25519

    p = e["payload"]
    problem = player_problem(p.get("player"))
    if problem:
        raise ChainError("CHAIN_STRUCTURE", f"REQUEST seq {e['seq']}: {problem}")
    if not all(isinstance(p.get(k), str) for k in ("sessionId", "clientSeed", "expression")):
        raise ChainError("CHAIN_STRUCTURE", f"REQUEST seq {e['seq']} sem sessionId, clientSeed ou expression")
    player = p["player"]
    if not links_valid(player["identityKey"], player["links"], player["publicKey"]):
        raise ChainError("CHAIN_PLAYER_SIGNATURE", f"vínculos do aparelho à identidade inválidos no REQUEST seq {e['seq']}")
    message = player_message(
        p["sessionId"], p["clientSeed"], p["expression"], player["identityKey"], player["name"], player["requestId"]
    )
    if not verify_ed25519(player["publicKey"], message, player["signature"]):
        raise ChainError("CHAIN_PLAYER_SIGNATURE", f"assinatura do jogador inválida no REQUEST seq {e['seq']}")
    if player["requestId"] in seen:
        raise ChainError("CHAIN_REQUEST_REPLAY", f"requestId repetido no REQUEST seq {e['seq']}")
    seen.add(player["requestId"])


def _check_segment(entries: list, prev_hash_of_first: str, chain_format: str = CHAIN_FORMAT) -> None:
    previous = None
    seen: set = set()
    for e in entries:
        _check_entry(e, previous, prev_hash_of_first)
        if chain_format == CHAIN_FORMAT_V2 and e["type"] == "REQUEST":
            _check_player(e, seen)
        previous = e


def _outcome(fn, *args) -> tuple[bool, str | None, str | None]:
    try:
        fn(*args)
    except ChainError as e:
        return False, e.code, str(e)
    return True, None, None


def verify_segment(entries: list, prev_hash_of_first: str = GENESIS_PREV_HASH, chain_format: str = CHAIN_FORMAT):
    """Seção 6.4 (chain-v1) ou 7.3 (chain-v2). Devolve (válido, código, mensagem)."""
    if chain_format not in CHAIN_FORMATS:
        raise ValueError(f"formato desconhecido: {chain_format}")
    return _outcome(_check_segment, entries, prev_hash_of_first, chain_format)


def _structure(msg: str) -> ChainError:
    return ChainError("PROOF_STRUCTURE", msg)


def _str(p: dict, key: str) -> str:
    if not isinstance(p.get(key), str):
        raise _structure(f"campo {key} ausente ou não-string")
    return p[key]


def _int(p: dict, key: str) -> int:
    if not _is_int(p.get(key)):
        raise _structure(f"campo {key} ausente ou não-inteiro")
    return p[key]


def _same(a, b) -> bool:
    try:
        return canonical_json(a) == canonical_json(b)
    except ChainError:
        return False


def _check_proof(proof) -> None:
    # 1. Cabeçalho
    drand = proof.get("drand") if isinstance(proof, dict) else None
    entries = proof.get("entries") if isinstance(proof, dict) else None
    if (
        not isinstance(proof, dict)
        or proof.get("algorithm") != "dice-v1"
        or proof.get("chainFormat") not in CHAIN_FORMATS
        or not isinstance(drand, dict)
        or drand.get("network") != QUICKNET_NETWORK
        or drand.get("chainHash") != QUICKNET_CHAIN_HASH
        or not isinstance(entries, list)
        or not entries
    ):
        raise _structure("cabeçalho da prova inválido")
    # 2. Segmento (prevHash esperado = o da própria primeira entrada)
    first_prev = entries[0].get("prevHash") if isinstance(entries[0], dict) else GENESIS_PREV_HASH
    chain_format = proof["chainFormat"]
    _check_segment(entries, first_prev, chain_format)

    # 3. Forma da prova
    request, result = entries[0], entries[-1]
    if request["type"] != "REQUEST" or result["type"] != "RESULT":
        raise _structure("deve ir do REQUEST ao RESULT")
    roll_id = _str(request["payload"], "rollId")
    session_id = _str(request["payload"], "sessionId")
    round_entry = None
    for i, e in enumerate(entries):
        if _str(e["payload"], "sessionId") != session_id:
            raise _structure(f"seq {e['seq']} de outra mesa")
        if _str(e["payload"], "rollId") != roll_id:
            continue
        if e["type"] == "REQUEST" and i != 0:
            raise _structure("REQUEST repetido")
        if e["type"] == "RESULT" and i != len(entries) - 1:
            raise _structure("RESULT fora do fim")
        if e["type"] == "ROUND":
            if round_entry is not None:
                raise _structure("mais de um ROUND")
            round_entry = e
    if round_entry is None:
        raise _structure("sem ROUND do roll")
    if _str(result["payload"], "rollId") != roll_id:
        raise _structure("RESULT final é de outro roll")
    req = request["payload"]
    if _str(req, "algorithm") != "dice-v1" or _str(req, "drandChainHash") != QUICKNET_CHAIN_HASH:
        raise _structure("algorithm ou drandChainHash inesperado")
    client_seed = _str(req, "clientSeed")
    expression = _str(req, "expression")
    nonce = _int(req, "nonce")
    target_round = _int(req, "targetRound")
    requested_at_text = _str(req, "requestedAt")
    if _INSTANT.fullmatch(requested_at_text) is None:
        raise _structure("requestedAt fora do formato")
    try:
        requested_at = datetime.strptime(requested_at_text, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        canonical_expression = parse_expression(expression).canonical
    except (ValueError, DiceError) as e:
        raise _structure(str(e)) from None
    if canonical_expression != expression:
        raise _structure("expressão fora da forma canônica")
    rnd = round_entry["payload"]
    round_number = _int(rnd, "round")
    randomness = _str(rnd, "randomness")
    signature = _str(rnd, "signature")
    res = result["payload"]
    if not isinstance(res.get("dice"), list) or not all(_is_int(d) for d in res["dice"]):
        raise _structure("dice inválido")
    modifier = _int(res, "modifier")
    total = _int(res, "total")

    # 4. Round alvo
    if round_number != target_round:
        raise ChainError("PROOF_WRONG_ROUND", f"ROUND {round_number} != targetRound {target_round}")
    # 5. Beacon
    try:
        beacon_ok = randomness_matches_signature(signature, randomness)
    except DiceError:
        beacon_ok = False
    if not beacon_ok:
        raise ChainError("PROOF_BEACON_INVALID", "randomness != SHA-256(signature)")
    # 6. Pedido antes do round alvo
    if target_round < 1 or not requested_at.timestamp() < time_of(target_round):
        raise ChainError("PROOF_TARGET_NOT_FUTURE", "requestedAt não é anterior ao round alvo")
    # 7. Resultado
    try:
        actual = roll(randomness, client_seed, nonce, expression)
    except DiceError as e:
        raise _structure(str(e)) from None
    if actual.dice != res["dice"] or actual.modifier != modifier or actual.total != total:
        raise ChainError("PROOF_RESULT_MISMATCH", f"recalculado {actual.dice} total {actual.total}")
    # 8. Resumos
    beacon, summary = proof.get("beacon"), proof.get("roll")
    if not isinstance(beacon, dict) or not isinstance(summary, dict):
        raise ChainError("PROOF_SUMMARY_MISMATCH", "resumos ausentes")
    checks = [
        (beacon, rnd, ("round", "randomness", "signature")),
        (summary, req, ("rollId", "sessionId", "nonce", "clientSeed", "expression", "targetRound", "requestedAt")),
        (summary, res, ("dice", "modifier", "total")),
    ]
    for target, source, fields in checks:
        for f in fields:
            if f not in target or not _same(target[f], source[f]):
                raise ChainError("PROOF_SUMMARY_MISMATCH", f"campo {f} do resumo não bate")
    if chain_format == CHAIN_FORMAT_V2 and ("player" not in summary or not _same(summary["player"], req["player"])):
        raise ChainError("PROOF_SUMMARY_MISMATCH", "campo player do resumo não bate")


def verify_proof(proof) -> tuple[bool, str | None, str | None]:
    """Seção 6.5 (chain-v1) ou 7.4 (chain-v2). Devolve (válido, código, mensagem)."""
    return _outcome(_check_proof, proof)
