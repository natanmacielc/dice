package io.github.natanmacielc.dice.app.ledger;

import io.github.natanmacielc.dice.app.og.ImageStore;
import io.github.natanmacielc.dice.app.og.S3ImageStore;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

/** S3 real (s3mock) com um interruptor para simular o S3 fora do ar. */
@Alternative
@Singleton
public class FlakyImageStore implements ImageStore {

    private final S3ImageStore delegate;
    volatile boolean down;

    public FlakyImageStore(S3ImageStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void putPng(String key, byte[] png) {
        if (down) {
            throw new IllegalStateException("S3 fora do ar (simulado)");
        }
        delegate.putPng(key, png);
    }
}
