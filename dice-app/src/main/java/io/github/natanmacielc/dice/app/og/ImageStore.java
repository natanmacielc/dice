package io.github.natanmacielc.dice.app.og;

/** Onde as imagens OG ficam publicadas (S3 atrás do CloudFront em produção). */
public interface ImageStore {

    void putPng(String key, byte[] png);
}
