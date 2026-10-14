package io.github.natanmacielc.dice.app.api;

import io.github.natanmacielc.dice.app.ledger.LedgerRepository;
import io.github.natanmacielc.dice.app.ledger.RollService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;

@Path("/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SessionResource {

    public record CreateSession(String name) {
    }

    public record CreateRoll(String clientSeed, String expression) {
    }

    private final RollService rolls;

    public SessionResource(RollService rolls) {
        this.rolls = rolls;
    }

    @POST
    public Response create(CreateSession body) {
        Map<String, Object> view = Views.session(rolls.createSession(body == null ? null : body.name()));
        return Response.created(URI.create("/sessions/" + view.get("sessionId"))).entity(view).build();
    }

    @POST
    @Path("/{sessionId}/rolls")
    public Response roll(@PathParam("sessionId") String sessionId, CreateRoll body) {
        LedgerRepository.Requested r = rolls.requestRoll(sessionId,
                body == null ? null : body.clientSeed(), body == null ? null : body.expression());
        return Response.created(URI.create("/rolls/" + r.roll().rollId()))
                .entity(Views.receipt(r.roll(), r.entry(), rolls.displayStatus(r.roll()))).build();
    }

    @GET
    @Path("/{sessionId}/log")
    public Map<String, Object> log(@PathParam("sessionId") String sessionId,
                                   @QueryParam("after") @DefaultValue("0") long after,
                                   @QueryParam("limit") @DefaultValue("100") int limit) {
        return Views.log(rolls.log(sessionId, after, limit));
    }
}
