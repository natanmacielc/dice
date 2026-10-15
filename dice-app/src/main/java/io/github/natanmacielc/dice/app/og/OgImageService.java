package io.github.natanmacielc.dice.app.og;

import io.github.natanmacielc.dice.app.ledger.LedgerRepository;
import io.github.natanmacielc.dice.app.ledger.RollRecord;
import io.github.natanmacielc.dice.core.chain.ChainEntry;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Gera og/{rollId}.png de um roll resolvido e marca o roll. Falhas não derrubam a resolução:
 * a página /r/{id} tenta de novo enquanto a imagem não existir.
 */
@ApplicationScoped
public class OgImageService {

    private static final Logger LOG = Logger.getLogger(OgImageService.class);

    private final ImageStore store;
    private final LedgerRepository ledger;
    private final String host;

    public OgImageService(ImageStore store, LedgerRepository ledger,
                          @ConfigProperty(name = "dice.public-base-url") URI publicBaseUrl) {
        this.store = store;
        this.ledger = ledger;
        this.host = publicBaseUrl.getAuthority();
    }

    public static String key(String rollId) {
        return "og/" + rollId + ".png";
    }

    /** Devolve true se a imagem foi publicada. */
    public boolean tryPublish(RollRecord roll) {
        if (!roll.resolved()) {
            return false;
        }
        try {
            List<ChainEntry> result = ledger.entries(roll.sessionId(), roll.resultSeq(), roll.resultSeq(), 1);
            String corner = result.isEmpty() ? null : "RESULT " + result.get(0).hash().substring(0, 8);
            String dice = roll.dice().stream().map(String::valueOf).collect(Collectors.joining(" · "));
            String name = roll.player() == null ? "" : OgImageRenderer.displayable(roll.player().name());
            byte[] png = OgImageRenderer.render(new OgImageRenderer.Card(roll.expression(), String.valueOf(roll.total()),
                    (name.isEmpty() ? "" : name + " · ") + "dados " + dice, "drand quicknet · round " + roll.round() + " · " + host + "/r/" + roll.rollId(),
                    corner));
            store.putPng(key(roll.rollId()), png);
            ledger.markOgImage(roll.rollId());
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("imagem OG do roll %s não publicada: %s", roll.rollId(), e.toString());
            return false;
        }
    }
}
