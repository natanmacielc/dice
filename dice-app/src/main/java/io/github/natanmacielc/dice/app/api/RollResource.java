package io.github.natanmacielc.dice.app.api;

import io.github.natanmacielc.dice.app.ledger.RollRecord;
import io.github.natanmacielc.dice.app.ledger.RollService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/rolls")
@Produces(MediaType.APPLICATION_JSON)
public class RollResource {

    private final RollService rolls;

    public RollResource(RollService rolls) {
        this.rolls = rolls;
    }

    @GET
    @Path("/{rollId}")
    public Map<String, Object> get(@PathParam("rollId") String rollId) {
        RollRecord r = rolls.roll(rollId);
        return Views.roll(r, rolls.displayStatus(r));
    }

    @GET
    @Path("/{rollId}/proof")
    public Map<String, Object> proof(@PathParam("rollId") String rollId) {
        return Views.proof(rolls.proof(rollId));
    }
}
