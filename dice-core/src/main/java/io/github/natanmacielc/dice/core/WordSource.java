package io.github.natanmacielc.dice.core;

/** Fonte de palavras uint32 (devolvidas como long em [0, 2^32)). */
@FunctionalInterface
interface WordSource {
    long next();
}
