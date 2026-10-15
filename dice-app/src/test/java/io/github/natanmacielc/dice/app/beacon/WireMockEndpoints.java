package io.github.natanmacielc.dice.app.beacon;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Sobe 3 relays drand falsos e aponta drand.endpoints para eles, nesta ordem. */
public class WireMockEndpoints implements QuarkusTestResourceLifecycleManager {

    static final List<WireMockServer> SERVERS = List.of(
            new WireMockServer(options().dynamicPort()),
            new WireMockServer(options().dynamicPort()),
            new WireMockServer(options().dynamicPort()));

    @Override
    public Map<String, String> start() {
        SERVERS.forEach(WireMockServer::start);
        return Map.of(
                "drand.endpoints", SERVERS.stream().map(WireMockServer::baseUrl).collect(Collectors.joining(",")),
                "drand.connect-timeout", "500ms",
                "drand.read-timeout", "500ms");
    }

    @Override
    public void stop() {
        SERVERS.forEach(WireMockServer::stop);
    }
}
