package io.github.natanmacielc.dice.core.chain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Quem pediu o roll na chain-v2 (spec/ALGORITHM.md, seção 7). {@code publicKey} é a chave do aparelho,
 * que assina o pedido; {@code identityKey} é a chave raiz do jogador; {@code links} é a cadeia de
 * vínculos que liga a identidade ao aparelho (vazia quando o aparelho é a própria identidade).
 */
public record Player(String publicKey, String name, String requestId, String signature, String identityKey,
                     List<Link> links) {

    public static final String CONTEXT = "dice-player-v1";
    public static final String LINK_CONTEXT = "dice-device-v1";
    public static final int MAX_NAME_BYTES = 32;
    public static final int MAX_LINKS = 8;

    private static final Pattern KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{128}");
    // SubjectPublicKeyInfo do Ed25519 (RFC 8410) seguido dos 32 bytes crus da chave.
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** Vínculo: a chave anterior da cadeia assina que {@code deviceKey} pertence à identidade. */
    public record Link(String deviceKey, String signature) {

        public Map<String, Object> toPayload() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceKey", deviceKey);
            m.put("signature", signature);
            return m;
        }
    }

    /**
     * Primeiro problema de formato, na ordem publicKey, name, requestId, signature, identityKey, links;
     * null se estiver tudo certo.
     */
    public String formatProblem() {
        if (publicKey == null || !KEY.matcher(publicKey).matches()) {
            return "publicKey deve ter 64 hex minúsculos";
        }
        String nameProblem = nameProblem(name);
        if (nameProblem != null) {
            return "name " + nameProblem;
        }
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            return "requestId deve ter 32 hex minúsculos";
        }
        if (signature == null || !SIGNATURE.matcher(signature).matches()) {
            return "signature deve ter 128 hex minúsculos";
        }
        if (identityKey == null || !KEY.matcher(identityKey).matches()) {
            return "identityKey deve ter 64 hex minúsculos";
        }
        return linksProblem(links);
    }

    /** Formato da lista de vínculos; null se estiver certa. */
    public static String linksProblem(List<Link> links) {
        if (links == null || links.size() > MAX_LINKS) {
            return "links deve ser uma lista de 0 a " + MAX_LINKS + " vínculos";
        }
        for (Link l : links) {
            if (l == null || l.deviceKey() == null || !KEY.matcher(l.deviceKey()).matches()
                    || l.signature() == null || !SIGNATURE.matcher(l.signature()).matches()) {
                return "links: cada vínculo tem deviceKey (64 hex) e signature (128 hex)";
            }
        }
        return null;
    }

    /** Mesmas regras da clientSeed (seção 3.2), com no máximo 32 bytes; null se estiver certo. */
    public static String nameProblem(String name) {
        if (name == null) {
            return "ausente";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < name.length() && Character.isLowSurrogate(name.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return "com surrogate sem par";
            }
        }
        int bytes = name.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > MAX_NAME_BYTES) {
            return "deve ter de 1 a " + MAX_NAME_BYTES + " bytes UTF-8";
        }
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "com caractere de controle";
        }
        return null;
    }

    /** utf8(canonical({clientSeed, context, expression, identityKey, name, requestId, sessionId})). */
    public static byte[] message(String sessionId, String clientSeed, String expression, String identityKey,
                                 String name, String requestId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("context", CONTEXT);
        m.put("sessionId", sessionId);
        m.put("clientSeed", clientSeed);
        m.put("expression", expression);
        m.put("identityKey", identityKey);
        m.put("name", name);
        m.put("requestId", requestId);
        return CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8);
    }

    /** utf8(canonical({context, deviceKey, identityKey})), assinado pela chave anterior da cadeia. */
    public static byte[] linkMessage(String identityKey, String deviceKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("context", LINK_CONTEXT);
        m.put("identityKey", identityKey);
        m.put("deviceKey", deviceKey);
        return CanonicalJson.write(m).getBytes(StandardCharsets.UTF_8);
    }

    /** A cadeia de vínculos liga identityKey a publicKey? Supõe formatProblem() == null. */
    public boolean linksValid() {
        return linksValid(identityKey, links, publicKey);
    }

    /**
     * Sem vínculos, a identidade é a própria chave. Com vínculos, o primeiro é assinado pela identidade,
     * cada um seguinte pelo deviceKey do anterior, e o último deviceKey tem de ser {@code endKey}.
     */
    public static boolean linksValid(String identityKey, List<Link> links, String endKey) {
        String issuer = identityKey;
        for (Link l : links) {
            if (!verify(issuer, linkMessage(identityKey, l.deviceKey()), l.signature())) {
                return false;
            }
            issuer = l.deviceKey();
        }
        return issuer.equals(endKey);
    }

    /** A assinatura do aparelho vale para este pedido? Supõe formatProblem() == null. */
    public boolean signatureValid(String sessionId, String clientSeed, String expression) {
        return verify(publicKey, message(sessionId, clientSeed, expression, identityKey, name, requestId), signature);
    }

    /** Ed25519 (RFC 8032) pela JDK. Chave fora da curva ou assinatura recusada dão false. */
    public static boolean verify(String publicKeyHex, byte[] message, String signatureHex) {
        try {
            byte[] raw = HexFormat.of().parseHex(publicKeyHex);
            byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
            System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
            System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(HexFormat.of().parseHex(signatureHex));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ---------- Assinatura: só para testes e para o gerador de vetores (no navegador, quem assina é a WebCrypto) ----------

    /** Pedido assinado por um aparelho que é a própria identidade (sem vínculos). */
    public static Player sign(String privateKeyHex, String publicKeyHex, String sessionId, String clientSeed,
                              String expression, String name, String requestId) {
        return sign(privateKeyHex, publicKeyHex, publicKeyHex, List.of(), sessionId, clientSeed, expression, name,
                requestId);
    }

    /** Pedido assinado por um aparelho vinculado à identidade pelos {@code links}. */
    public static Player sign(String privateKeyHex, String publicKeyHex, String identityKey, List<Link> links,
                              String sessionId, String clientSeed, String expression, String name, String requestId) {
        String sig = signRaw(privateKeyHex, message(sessionId, clientSeed, expression, identityKey, name, requestId));
        return new Player(publicKeyHex, name, requestId, sig, identityKey, List.copyOf(links));
    }

    /** Vínculo de {@code deviceKey} à identidade, assinado pela chave anterior da cadeia. */
    public static Link link(String issuerPrivateKeyHex, String identityKey, String deviceKey) {
        return new Link(deviceKey, signRaw(issuerPrivateKeyHex, linkMessage(identityKey, deviceKey)));
    }

    static String signRaw(String privateKeyHex, byte[] message) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, HexFormat.of().parseHex(privateKeyHex)));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(message);
            return HexFormat.of().formatHex(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponível", e);
        }
    }

    /** Impressão para exibição: 4 primeiros bytes de SHA-256(bytes da chave), como "3f9a-c2e1". */
    public static String fingerprint(String publicKeyHex) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(HexFormat.of().parseHex(publicKeyHex));
            String hex = HexFormat.of().formatHex(digest, 0, 4);
            return hex.substring(0, 4) + "-" + hex.substring(4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Impressão da identidade: a mesma em todos os aparelhos do jogador. */
    public String fingerprint() {
        return fingerprint(identityKey);
    }

    /** Objeto player do payload REQUEST. */
    public Map<String, Object> toPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publicKey", publicKey);
        m.put("name", name);
        m.put("requestId", requestId);
        m.put("signature", signature);
        m.put("identityKey", identityKey);
        m.put("links", links.stream().map(Link::toPayload).toList());
        return m;
    }

    /** Lê e valida o formato do player de um payload. Lança ChainFormatException(CHAIN_STRUCTURE). */
    static Player fromPayload(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.size() != 6 || !(m.get("publicKey") instanceof String pk)
                || !(m.get("name") instanceof String n) || !(m.get("requestId") instanceof String r)
                || !(m.get("signature") instanceof String s) || !(m.get("identityKey") instanceof String id)
                || !(m.get("links") instanceof List<?> rawLinks)) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE,
                    "player deve ser objeto com publicKey, name, requestId, signature, identityKey e links");
        }
        List<Link> links = new ArrayList<>();
        for (Object l : rawLinks) {
            if (!(l instanceof Map<?, ?> lm) || lm.size() != 2 || !(lm.get("deviceKey") instanceof String dk)
                    || !(lm.get("signature") instanceof String ls)) {
                throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE,
                        "cada vínculo deve ser objeto com deviceKey e signature");
            }
            links.add(new Link(dk, ls));
        }
        Player p = new Player(pk, n, r, s, id, List.copyOf(links));
        String problem = p.formatProblem();
        if (problem != null) {
            throw new ChainFormatException(ChainErrorCode.CHAIN_STRUCTURE, "player: " + problem);
        }
        return p;
    }
}
