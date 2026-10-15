package io.github.natanmacielc.dice.app.beacon;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** API HTTP do drand. Uma instância por endpoint, criada em {@link HttpBeaconClient}. */
@Path("/{chain}/public")
@Produces(MediaType.APPLICATION_JSON)
public interface DrandApi {

    @GET
    @Path("/latest")
    DrandBeaconResponse latest(@PathParam("chain") String chain);

    @GET
    @Path("/{round}")
    DrandBeaconResponse round(@PathParam("chain") String chain, @PathParam("round") long round);
}
