package io.github.natanmacielc.dice.app.pairing;

import io.github.natanmacielc.dice.core.chain.Player;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pareamento de aparelhos: só repassa dados públicos entre os dois lados (spec/ALGORITHM.md, seção 7.7). */
@Path("/api/pairings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PairingResource {

    public record DeviceStep(String deviceKey, String mac) {
    }

    public record IdentityStep(String identityKey, List<Player.Link> links, String name, String mac) {
    }

    private final PairingService pairings;

    public PairingResource(PairingService pairings) {
        this.pairings = pairings;
    }

    @POST
    public Response create() {
        PairingRecord p = pairings.create();
        return Response.created(URI.create("/api/pairings/" + p.pairingId())).entity(view(p)).build();
    }

    @GET
    @Path("/{pairingId}")
    public Map<String, Object> get(@PathParam("pairingId") String id) {
        return view(pairings.get(id));
    }

    @POST
    @Path("/{pairingId}/device")
    public Map<String, Object> device(@PathParam("pairingId") String id, DeviceStep body) {
        return view(pairings.registerDevice(id, body == null ? null : body.deviceKey(), body == null ? null : body.mac()));
    }

    @POST
    @Path("/{pairingId}/identity")
    public Map<String, Object> identity(@PathParam("pairingId") String id, IdentityStep body) {
        if (body == null) {
            throw new PairingException(400, "PAIRING_INVALID", "corpo ausente");
        }
        return view(pairings.registerIdentity(id, body.identityKey(), body.links(), body.name(), body.mac()));
    }

    private static Map<String, Object> view(PairingRecord p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pairingId", p.pairingId());
        m.put("expiresAt", Instant.ofEpochSecond(p.expiresAt()).toString());
        if (p.device() != null) {
            m.put("device", Map.of("deviceKey", p.device().deviceKey(), "mac", p.device().mac()));
        }
        if (p.identity() != null) {
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("identityKey", p.identity().identityKey());
            identity.put("links", p.identity().links().stream().map(Player.Link::toPayload).toList());
            identity.put("name", p.identity().name());
            identity.put("mac", p.identity().mac());
            m.put("identity", identity);
        }
        return m;
    }
}
