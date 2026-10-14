package io.github.natanmacielc.dice.app.beacon;

import io.smallrye.config.ConfigMapping;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigMapping(prefix = "drand")
public interface DrandConfig {

    /** Tentados nesta ordem. */
    List<URI> endpoints();

    Duration connectTimeout();

    Duration readTimeout();
}
