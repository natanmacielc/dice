package io.github.natanmacielc.dice.app.api;

import io.github.natanmacielc.dice.app.ledger.NotFoundException;
import io.github.natanmacielc.dice.app.ledger.RollRecord;
import io.github.natanmacielc.dice.app.ledger.RollService;
import io.github.natanmacielc.dice.app.og.OgImageService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * /r/{rollId}: HTML com Open Graph renderizado no servidor (o crawler do Facebook não roda JS).
 * A verificação acontece no navegador, em web/roll.js.
 */
@Path("/r")
public class RollPageResource {

    private static final String HTML = "text/html; charset=utf-8";
    private static final Map<String, String> STATUS = Map.of(
            "PENDING", "Aguardando o drand",
            "RESOLVED", "Resolvido",
            "EXPIRED", "Atrasado: o drand ainda não respondeu");

    private final RollService rolls;
    private final OgImageService ogImages;
    private final Template page;
    private final Template notFound;
    private final String base;

    public RollPageResource(RollService rolls, OgImageService ogImages, @Location("r.html") Template page,
                            @Location("r-404.html") Template notFound,
                            @ConfigProperty(name = "dice.public-base-url") URI publicBaseUrl) {
        this.rolls = rolls;
        this.ogImages = ogImages;
        this.page = page;
        this.notFound = notFound;
        this.base = publicBaseUrl.toString().replaceAll("/+$", "");
    }

    @GET
    @Path("/{rollId}")
    public Response get(@PathParam("rollId") String rollId) {
        RollRecord roll;
        try {
            roll = rolls.roll(rollId);
        } catch (NotFoundException e) {
            return Response.status(404).type(HTML).entity(notFound.render()).build();
        }
        boolean hasImage = roll.ogImage() || (roll.resolved() && ogImages.tryPublish(roll));
        return Response.ok(page.data("page", view(roll, hasImage)).render()).type(HTML).build();
    }

    private RollPage view(RollRecord r, boolean hasImage) {
        String status = rolls.displayStatus(r);
        String title;
        String description;
        String image;
        String total;
        String dice;
        if (r.resolved()) {
            String list = r.dice().stream().map(String::valueOf).collect(Collectors.joining(", "));
            title = r.expression() + " = " + r.total() + " · dados " + list;
            description = "Seed “" + r.clientSeed() + "” · round " + r.round()
                    + " do drand · qualquer pessoa pode conferir este resultado.";
            image = hasImage ? base + "/" + OgImageService.key(r.rollId()) : base + "/static/og-default.png";
            total = String.valueOf(r.total());
            dice = "dados " + r.dice().stream().map(String::valueOf).collect(Collectors.joining(" · "));
        } else {
            title = "Rolagem pendente: " + r.expression();
            description = "Aguardando o round " + r.targetRound()
                    + " do drand. O resultado ainda não existe; ninguém pode prevê-lo.";
            image = base + "/static/og-pending.png";
            total = "?";
            dice = "aguardando o round " + r.targetRound();
        }
        return new RollPage(r.rollId(), r.sessionId(), status, STATUS.getOrDefault(status, status), title, description,
                base + "/r/" + r.rollId(), image, r.expression(), total, dice, r.clientSeed(), r.nonce(),
                r.targetRound(), r.requestedAt());
    }
}
