package io.github.natanmacielc.dice.app.tables;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lista de mesas do jogador (spec/ALGORITHM.md, seção 7.8). POST porque todo pedido vai assinado no
 * corpo: {@code {auth, action, sessionId?, cursor?}}, com action list, add ou remove.
 */
@Path("/api/me/tables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TablesResource {

    public record Request(TablesService.Auth auth, String action, String sessionId, String cursor) {
    }

    private final TablesService tables;

    public TablesResource(TablesService tables) {
        this.tables = tables;
    }

    @POST
    public Map<String, Object> handle(Request body) {
        if (body == null || body.auth() == null) {
            throw new TablesException(401, "AUTH_INVALID", "pedido sem auth");
        }
        String action = body.action();
        if (!List.of("list", "add", "remove").contains(action)) {
            throw new TablesException(400, "INVALID_REQUEST", "action deve ser list, add ou remove");
        }
        String identity = tables.authenticate(body.auth(), action, body.sessionId(), body.cursor());
        return switch (action) {
            case "list" -> {
                TablesService.Page page = tables.list(identity, body.cursor());
                Map<String, Object> m = new HashMap<>();
                m.put("tables", page.tables().stream().map(TablesResource::view).toList());
                m.put("next", page.next());
                yield m;
            }
            case "add" -> Map.of("table", view(tables.add(identity, body.sessionId())));
            default -> {
                tables.remove(identity, body.sessionId());
                yield Map.of("removed", body.sessionId());
            }
        };
    }

    private static Map<String, Object> view(TableRef t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", t.sessionId());
        if (t.name() != null) {
            m.put("name", t.name());
        }
        m.put("joinedAt", t.joinedAt());
        m.put("lastActiveAt", t.lastActiveAt());
        return m;
    }
}
