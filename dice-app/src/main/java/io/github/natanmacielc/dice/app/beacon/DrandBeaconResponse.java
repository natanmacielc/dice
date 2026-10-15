package io.github.natanmacielc.dice.app.beacon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Corpo de /{chain}/public/{round|latest}. A ordem dos campos varia entre relays. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DrandBeaconResponse(Long round, String randomness, String signature) {
}
