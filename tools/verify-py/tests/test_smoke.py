"""Teste de fumaça do scaffold: garante que pytest roda e que SHA-256 está disponível."""

import hashlib


def test_sha256_of_abc_matches_fips180_vector():
    assert (
        hashlib.sha256("abc".encode("utf-8")).hexdigest()
        == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
