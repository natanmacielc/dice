package io.github.natanmacielc.dice.app.api;

import io.github.natanmacielc.dice.app.beacon.BeaconUnavailableException;
import io.github.natanmacielc.dice.app.ledger.ChainContentionException;
import io.github.natanmacielc.dice.app.ledger.InvalidRequestException;
import io.github.natanmacielc.dice.app.ledger.NotFoundException;
import io.github.natanmacielc.dice.app.ledger.RollPendingException;
import io.github.natanmacielc.dice.core.DiceException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Erros como {error, message}. Os códigos de entrada inválida são os da spec (ex.: SEED_TOO_LONG). */
public class ErrorMappers {

    @ServerExceptionMapper
    public Response invalidInput(DiceException e) {
        return error(400, e.code().name(), e.getMessage());
    }

    @ServerExceptionMapper
    public Response invalidRequest(InvalidRequestException e) {
        return error(400, "INVALID_REQUEST", e.getMessage());
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException e) {
        return error(404, "NOT_FOUND", e.getMessage());
    }

    @ServerExceptionMapper
    public Response pending(RollPendingException e) {
        return error(409, "ROLL_PENDING", e.getMessage());
    }

    @ServerExceptionMapper
    public Response beaconUnavailable(BeaconUnavailableException e) {
        return Response.fromResponse(error(503, "BEACON_UNAVAILABLE", "drand indisponível; tente de novo"))
                .header("Retry-After", "3").build();
    }

    @ServerExceptionMapper
    public Response contention(ChainContentionException e) {
        return Response.fromResponse(error(503, "LEDGER_BUSY", e.getMessage())).header("Retry-After", "1").build();
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).entity(new ErrorResponse(code, message)).build();
    }
}
