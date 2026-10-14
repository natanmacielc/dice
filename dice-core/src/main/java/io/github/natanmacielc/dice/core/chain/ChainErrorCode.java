package io.github.natanmacielc.dice.core.chain;

/** Códigos de erro da chain-v1. Os verificadores JS e Python usam os mesmos nomes. */
public enum ChainErrorCode {
    CHAIN_STRUCTURE,
    CHAIN_SEQ_GAP,
    CHAIN_SEQ_MISMATCH,
    CHAIN_LINK_BROKEN,
    CHAIN_HASH_MISMATCH,
    PROOF_STRUCTURE,
    PROOF_WRONG_ROUND,
    PROOF_BEACON_INVALID,
    PROOF_TARGET_NOT_FUTURE,
    PROOF_RESULT_MISMATCH,
    PROOF_SUMMARY_MISMATCH
}
