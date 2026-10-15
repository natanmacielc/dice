package io.github.natanmacielc.dice.core.chain;

/** Formatos da cadeia. A chain-v2 só acrescenta o player assinado ao REQUEST (spec/ALGORITHM.md, seção 7). */
public enum ChainFormat {
    CHAIN_V1("chain-v1"),
    CHAIN_V2("chain-v2");

    private final String id;

    ChainFormat(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** null se o id não for conhecido. */
    public static ChainFormat fromId(Object id) {
        for (ChainFormat f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }
}
