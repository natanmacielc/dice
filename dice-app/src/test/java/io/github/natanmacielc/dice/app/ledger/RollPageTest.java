package io.github.natanmacielc.dice.app.ledger;

import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.newSession;
import static io.github.natanmacielc.dice.app.ledger.LedgerTestSupport.requestRoll;
import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.natanmacielc.dice.app.beacon.MutableClock;
import io.github.natanmacielc.dice.core.Quicknet;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@QuarkusTest
@TestProfile(LedgerProfile.class)
@WithTestResource(LocalAwsResource.class)
class RollPageTest {

    static final long LATEST = 3_000_000;
    static final Instant T0 = Quicknet.timeOf(LATEST).plusSeconds(1);
    static final String BASE = "https://dados.exemplo.dev";

    @Inject
    MutableClock clock;

    @Inject
    FakeBeaconClient beacon;

    @Inject
    FlakyImageStore images;

    @BeforeEach
    void reset() {
        clock.set(T0);
        beacon.reset();
        images.down = false;
    }

    static ResponseBytes<GetObjectResponse> object(String rollId) {
        try (S3Client s3 = LocalAwsResource.s3Client()) {
            return s3.getObjectAsBytes(r -> r.bucket(LocalAwsResource.BUCKET).key("og/" + rollId + ".png"));
        }
    }

    static boolean objectExists(String rollId) {
        try {
            object(rollId);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Test
    void pendingThenResolvedPageWithOpenGraphImageInS3() throws Exception {
        String rollId = requestRoll(newSession(), "mesa", "2d6+3").then().statusCode(201).extract().path("rollId");

        get("/r/" + rollId).then().statusCode(200)
                .contentType(startsWith("text/html"))
                .header("Content-Security-Policy", containsString("connect-src 'self' https://api.drand.sh"))
                .header("X-Content-Type-Options", "nosniff")
                .body(containsString("<meta property=\"og:title\" content=\"Rolagem pendente: 2d6+3\">"))
                .body(containsString("<meta property=\"og:image\" content=\"" + BASE + "/static/og-pending.png\">"))
                .body(containsString("<meta property=\"og:url\" content=\"" + BASE + "/r/" + rollId + "\">"))
                .body(containsString("data-roll-id=\"" + rollId + "\""));
        assertFalse(objectExists(rollId));

        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/api/rolls/" + rollId).then().statusCode(200); // resolve e publica a imagem
        ResponseBytes<GetObjectResponse> png = object(rollId);
        assertEquals("image/png", png.response().contentType());
        assertTrue(png.response().cacheControl().contains("immutable"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png.asByteArray()));
        assertEquals(1200, img.getWidth());
        assertEquals(630, img.getHeight());

        String total = get("/api/rolls/" + rollId).then().extract().path("result.total").toString();
        get("/r/" + rollId).then().statusCode(200)
                .body(containsString("<meta property=\"og:image\" content=\"" + BASE + "/og/" + rollId + ".png\">"))
                .body(containsString("<meta property=\"og:image:type\" content=\"image/png\">"))
                .body(containsString("<meta name=\"twitter:image\" content=\"" + BASE + "/og/" + rollId + ".png\">"))
                .body(containsString("<link rel=\"canonical\" href=\"" + BASE + "/r/" + rollId + "\">"))
                .body(containsString("<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"))
                .body(containsString("<meta property=\"og:title\" content=\"2d6+3 = " + total + " · dados "))
                .body(containsString("data-status=\"RESOLVED\""));
    }

    @Test
    void imageFailureDoesNotUndoResolutionAndPageRetries() {
        String rollId = requestRoll(newSession(), "s3-fora", "1d20").then().statusCode(201).extract().path("rollId");
        images.down = true;
        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/api/rolls/" + rollId).then().statusCode(200).body(containsString("RESOLVED"));
        assertFalse(objectExists(rollId));
        get("/r/" + rollId).then().statusCode(200)
                .body(containsString("<meta property=\"og:image\" content=\"" + BASE + "/static/og-default.png\">"));

        images.down = false;
        get("/r/" + rollId).then().statusCode(200)
                .body(containsString("<meta property=\"og:image\" content=\"" + BASE + "/og/" + rollId + ".png\">"));
        assertTrue(objectExists(rollId));
    }

    @Test
    void userTextIsEscapedInHtml() {
        String seed = "<script>alert(1)</script>\"'";
        String rollId = requestRoll(newSession(), seed, "1d6").then().statusCode(201).extract().path("rollId");
        clock.set(Quicknet.timeOf(LATEST + 2));
        get("/r/" + rollId).then().statusCode(200)
                .body(not(containsString("<script>alert(1)</script>")))
                .body(containsString("&lt;script&gt;alert(1)&lt;/script&gt;&quot;&#39;"));
    }

    @Test
    void unknownRollIsAnHtml404() {
        get("/r/0000000000000000").then().statusCode(404).contentType(startsWith("text/html"))
                .body(containsString("Rolagem não encontrada"));
        get("/r/nao-e-um-id").then().statusCode(404);
    }

    @Test
    void servesStaticSiteFromTheJar() {
        get("/").then().statusCode(200).body(containsString("Roll Dice")).body(containsString("/app.js"));
        get("/verifier/chain-v1.js").then().statusCode(200);
        get("/verifier/drand-verify.js").then().statusCode(200);
        get("/vendor/drand-client-1.4.2.mjs").then().statusCode(200);
        get("/static/og-pending.png").then().statusCode(200).contentType("image/png");
        get("/verifier/chain-v1.test.mjs").then().statusCode(404);
    }
}
