"""Player assinado da chain-v2 (spec/ALGORITHM.md, seção 7). Ed25519 pela pyca/cryptography."""

from __future__ import annotations

import hashlib
import re

PLAYER_CONTEXT = "dice-player-v1"
LINK_CONTEXT = "dice-device-v1"
MAX_NAME_BYTES = 32
MAX_LINKS = 8

_KEY = re.compile(r"[0-9a-f]{64}", re.ASCII)
_REQUEST_ID = re.compile(r"[0-9a-f]{32}", re.ASCII)
_SIGNATURE = re.compile(r"[0-9a-f]{128}", re.ASCII)
_FIELDS = {"publicKey", "name", "requestId", "signature", "identityKey", "links"}
_LINK_FIELDS = {"deviceKey", "signature"}


def name_problem(name) -> str | None:
    """Regras da clientSeed (seção 3.2), com no máximo 32 bytes. None se estiver ok."""
    if not isinstance(name, str):
        return "ausente"
    if any(0xD800 <= ord(ch) <= 0xDFFF for ch in name):
        return "com surrogate sem par"
    size = len(name.encode("utf-8"))
    if size == 0 or size > MAX_NAME_BYTES:
        return f"deve ter de 1 a {MAX_NAME_BYTES} bytes UTF-8"
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in name):
        return "com caractere de controle"
    return None


def _hex(value, pattern) -> bool:
    return isinstance(value, str) and pattern.fullmatch(value) is not None


def links_problem(links) -> str | None:
    """Formato da lista de vínculos (seção 7.2), ou None."""
    if not isinstance(links, list) or len(links) > MAX_LINKS:
        return f"links deve ser uma lista de 0 a {MAX_LINKS} vínculos"
    for link in links:
        if (
            not isinstance(link, dict)
            or set(link) != _LINK_FIELDS
            or not _hex(link["deviceKey"], _KEY)
            or not _hex(link["signature"], _SIGNATURE)
        ):
            return "links: cada vínculo tem exatamente deviceKey (64 hex) e signature (128 hex)"
    return None


def player_problem(player) -> str | None:
    """Primeiro problema de formato do player (seção 7.2), ou None."""
    if not isinstance(player, dict) or set(player) != _FIELDS:
        return "player deve ter exatamente publicKey, name, requestId, signature, identityKey e links"
    if not isinstance(player["publicKey"], str) or _KEY.fullmatch(player["publicKey"]) is None:
        return "publicKey deve ter 64 hex minúsculos"
    problem = name_problem(player["name"])
    if problem:
        return f"name {problem}"
    if not isinstance(player["requestId"], str) or _REQUEST_ID.fullmatch(player["requestId"]) is None:
        return "requestId deve ter 32 hex minúsculos"
    if not isinstance(player["signature"], str) or _SIGNATURE.fullmatch(player["signature"]) is None:
        return "signature deve ter 128 hex minúsculos"
    if not _hex(player["identityKey"], _KEY):
        return "identityKey deve ter 64 hex minúsculos"
    return links_problem(player["links"])


def player_message(
    session_id: str, client_seed: str, expression: str, identity_key: str, name: str, request_id: str
) -> bytes:
    """utf8(canonical({clientSeed, context, expression, identityKey, name, requestId, sessionId}))."""
    from .chain import canonical_json

    return canonical_json(
        {
            "context": PLAYER_CONTEXT,
            "sessionId": session_id,
            "clientSeed": client_seed,
            "expression": expression,
            "identityKey": identity_key,
            "name": name,
            "requestId": request_id,
        }
    ).encode("utf-8")


def link_message(identity_key: str, device_key: str) -> bytes:
    """utf8(canonical({context, deviceKey, identityKey})), assinado pela chave anterior da cadeia."""
    from .chain import canonical_json

    return canonical_json({"context": LINK_CONTEXT, "identityKey": identity_key, "deviceKey": device_key}).encode(
        "utf-8"
    )


def links_valid(identity_key: str, links: list, end_key: str) -> bool:
    """O primeiro vínculo é assinado pela identidade, cada seguinte pelo deviceKey do anterior, e o último
    deviceKey é end_key. Sem vínculos, identity_key tem de ser end_key. Supõe o formato já conferido."""
    issuer = identity_key
    for link in links:
        if not verify_ed25519(issuer, link_message(identity_key, link["deviceKey"]), link["signature"]):
            return False
        issuer = link["deviceKey"]
    return issuer == end_key


def verify_ed25519(public_key_hex: str, message: bytes, signature_hex: str) -> bool:
    """Ed25519 (RFC 8032). Chave fora da curva ou assinatura recusada dão False."""
    # Importa aqui para a chain-v1 continuar verificável sem a dependência.
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

    try:
        key = Ed25519PublicKey.from_public_bytes(bytes.fromhex(public_key_hex))
        key.verify(bytes.fromhex(signature_hex), message)
        return True
    except (InvalidSignature, ValueError):
        return False


def fingerprint(public_key_hex: str) -> str:
    """Impressão para exibição (seção 7.5): 4 primeiros bytes de SHA-256(chave), como '3f9a-c2e1'."""
    h = hashlib.sha256(bytes.fromhex(public_key_hex)).hexdigest()[:8]
    return f"{h[:4]}-{h[4:]}"
