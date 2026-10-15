package io.github.natanmacielc.dice.app.beacon;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@WithTestResource(WireMockEndpoints.class)
class HttpBeaconClientTest {

    // Round 1000000 real do quicknet.
    static final long ROUND = 1_000_000;
    static final String RANDOMNESS = "b22aad4794f7451896f7a371aa46106fd84d919f3f569acd5b2fddf1d1440af3";
    static final String SIGNATURE = "83ad29e4c409f9470fc2ef02f90214df49e02b441a1a241a82d622d9f608ef98fd8b11a029f1bee9d9e83b45088abe72";
    static final Beacon EXPECTED = new Beacon(ROUND, RANDOMNESS, SIGNATURE);

    static final String LATEST = "/" + DrandQuicknet.CHAIN_HASH + "/public/latest";
    static final String ROUND_PATH = "/" + DrandQuicknet.CHAIN_HASH + "/public/" + ROUND;

    static final WireMockServer WM1 = WireMockEndpoints.SERVERS.get(0);
    static final WireMockServer WM2 = WireMockEndpoints.SERVERS.get(1);
    static final WireMockServer WM3 = WireMockEndpoints.SERVERS.get(2);

    @Inject
    BeaconClient client;

    @Inject
    MutableClock clock;

    @BeforeEach
    void reset() {
        WireMockEndpoints.SERVERS.forEach(WireMockServer::resetAll);
        // Relógio 1 s depois da publicação do round 1000000: esperado = 1000000.
        clock.set(DrandQuicknet.timeOf(ROUND).plusSeconds(1));
    }

    static String json(long round, String randomness, String signature) {
        return "{\"round\":" + round + ",\"randomness\":\"" + randomness + "\",\"signature\":\"" + signature + "\"}";
    }

    static ResponseDefinitionBuilder valid() {
        return okJson(json(ROUND, RANDOMNESS, SIGNATURE));
    }

    static void stub(WireMockServer wm, MappingBuilder mapping) {
        wm.stubFor(mapping);
    }

    @Test
    void latestHappyPathUsesFixedChainHash() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
        WM1.verify(1, getRequestedFor(urlEqualTo(LATEST)));
        WM2.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void failsOverOnConnectionReset() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void failsOverOnEmptyResponse() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void failsOverOnTimeout() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(valid().withFixedDelay(1500)));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void failsOverOnServerError() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(aResponse().withStatus(500)));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(aResponse().withStatus(503)));
        stub(WM3, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void failsOverOnMalformedJson() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(okJson("{\"round\": 10")));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(okJson("{\"round\":1000000}")));
        stub(WM3, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void rejectsRandomnessThatIsNotSha256OfSignature() {
        String tampered = "93" + SIGNATURE.substring(2);
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(okJson(json(ROUND, RANDOMNESS, tampered))));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(okJson(json(ROUND, "0".repeat(64), SIGNATURE))));
        stub(WM3, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void rejectsBadHexFormats() {
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(okJson(json(ROUND, RANDOMNESS.toUpperCase(), SIGNATURE))));
        stub(WM2, get(urlEqualTo(LATEST)).willReturn(okJson(json(ROUND, RANDOMNESS, SIGNATURE + "0"))));
        stub(WM3, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void clockGuardRejectsStaleLatest() {
        clock.set(DrandQuicknet.timeOf(ROUND + 2)); // esperado = 1000002; latest 1000000 está velho
        WireMockEndpoints.SERVERS.forEach(wm -> stub(wm, get(urlEqualTo(LATEST)).willReturn(valid())));
        assertThrows(BeaconUnavailableException.class, () -> client.latest());
    }

    @Test
    void clockGuardRejectsLatestFromTheFuture() {
        clock.set(DrandQuicknet.timeOf(ROUND - 2)); // esperado = 999998; latest 1000000 está adiantado
        WireMockEndpoints.SERVERS.forEach(wm -> stub(wm, get(urlEqualTo(LATEST)).willReturn(valid())));
        assertThrows(BeaconUnavailableException.class, () -> client.latest());
    }

    @Test
    void clockGuardToleratesOneRound() {
        clock.set(DrandQuicknet.timeOf(ROUND + 1));
        stub(WM1, get(urlEqualTo(LATEST)).willReturn(valid()));
        assertEquals(EXPECTED, client.latest());
        clock.set(DrandQuicknet.timeOf(ROUND - 1));
        assertEquals(EXPECTED, client.latest());
    }

    @Test
    void latestThrowsWhenAllEndpointsFail() {
        WireMockEndpoints.SERVERS.forEach(wm -> stub(wm, get(urlEqualTo(LATEST)).willReturn(aResponse().withStatus(502))));
        BeaconUnavailableException ex = assertThrows(BeaconUnavailableException.class, () -> client.latest());
        assertTrue(ex.getMessage().contains("HTTP 502"), ex.getMessage());
    }

    @Test
    void roundUsesExactRoundPathAndNeverLatest() {
        stub(WM1, get(urlEqualTo(ROUND_PATH)).willReturn(valid()));
        assertEquals(Optional.of(EXPECTED), client.round(ROUND));
        WM1.verify(1, getRequestedFor(urlEqualTo(ROUND_PATH)));
        WireMockEndpoints.SERVERS.forEach(wm -> wm.verify(0, getRequestedFor(urlEqualTo(LATEST))));
    }

    @Test
    void roundFailsOverWhenRelayAnswersAnotherRound() {
        stub(WM1, get(urlEqualTo(ROUND_PATH)).willReturn(okJson(json(1,
                "1466a6cd24e327188770752f6134001c64d6efcc590ccc26b721611ad96f165a",
                "b55e7cb2d5c613ee0b2e28d6750aabbb78c39dcc96bd9d38c2c2e12198df95571de8e8e402a0cc48871c7089a2b3af4b"))));
        stub(WM2, get(urlEqualTo(ROUND_PATH)).willReturn(valid()));
        assertEquals(Optional.of(EXPECTED), client.round(ROUND));
    }

    @Test
    void futureRoundByClockReturnsEmptyWithoutAnyRequest() {
        clock.set(DrandQuicknet.timeOf(ROUND).minusMillis(1));
        assertEquals(Optional.empty(), client.round(ROUND));
        WireMockEndpoints.SERVERS.forEach(wm -> wm.verify(0, anyRequestedFor(anyUrl())));
    }

    @Test
    void tooEarlyOrNotFoundEverywhereMeansNotYetPublished() {
        stub(WM1, get(urlEqualTo(ROUND_PATH)).willReturn(aResponse().withStatus(425)));
        stub(WM2, get(urlEqualTo(ROUND_PATH)).willReturn(aResponse().withStatus(425)));
        stub(WM3, get(urlEqualTo(ROUND_PATH)).willReturn(aResponse().withStatus(404)));
        assertEquals(Optional.empty(), client.round(ROUND));
    }

    @Test
    void tooEarlyOnFirstButPublishedOnSecond() {
        stub(WM1, get(urlEqualTo(ROUND_PATH)).willReturn(aResponse().withStatus(425)));
        stub(WM2, get(urlEqualTo(ROUND_PATH)).willReturn(valid()));
        assertEquals(Optional.of(EXPECTED), client.round(ROUND));
    }

    @Test
    void roundThrowsWhenAllEndpointsFailWithoutNotFound() {
        WireMockEndpoints.SERVERS.forEach(wm -> stub(wm, get(urlEqualTo(ROUND_PATH)).willReturn(aResponse().withStatus(500))));
        assertThrows(BeaconUnavailableException.class, () -> client.round(ROUND));
    }

    @Test
    void roundBelowOneIsRejectedWithoutRequest() {
        assertThrows(IllegalArgumentException.class, () -> client.round(0));
        assertThrows(IllegalArgumentException.class, () -> client.round(-1));
        WireMockEndpoints.SERVERS.forEach(wm -> wm.verify(0, anyRequestedFor(anyUrl())));
    }

    @Test
    void acceptsCloudflareFieldOrderAndExtraFields() {
        String body = "{\"round\":" + ROUND + ",\"signature\":\"" + SIGNATURE + "\",\"randomness\":\"" + RANDOMNESS
                + "\",\"previous_signature\":\"\",\"extra\":{\"a\":1}}";
        stub(WM1, get(urlEqualTo(ROUND_PATH)).willReturn(okJson(body)));
        assertEquals(Optional.of(EXPECTED), client.round(ROUND));
    }
}
