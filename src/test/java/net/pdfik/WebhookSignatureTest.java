package net.pdfik;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PdfikClient#verifyWebhookSignature}: pure computation, so no WireMock here. */
public class WebhookSignatureTest {

    private static final String BODY = "{\"job_id\":\"j-1\",\"status\":\"done\",\"test\":false}";
    private static final String CURRENT = "whsec_current";
    private static final String PREVIOUS = "whsec_previous";

    private static String sign(String secret, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal((timestamp + "." + BODY).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static Map<String, String> headers(String signature, String timestamp) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-PDFik-Signature", signature);
        headers.put("X-PDFik-Timestamp", timestamp);
        return headers;
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000);
    }

    /** What a delivery carries while the previous secret is inside its overlap window:
     * one v1 per secret, the current secret's first. */
    private static String rotationSignature(String timestamp) throws Exception {
        return "t=" + timestamp + ",v1=" + sign(CURRENT, timestamp) + ",v1=" + sign(PREVIOUS, timestamp);
    }

    @Test
    public void singleSignatureVerifiesWithItsOwnSecretOnly() throws Exception {
        String t = now();
        Map<String, String> headers = headers("t=" + t + ",v1=" + sign(CURRENT, t), t);

        assertTrue(PdfikClient.verifyWebhookSignature(BODY, headers, CURRENT));
        assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers, PREVIOUS));
        assertFalse(PdfikClient.verifyWebhookSignature(BODY, new HashMap<>(), CURRENT));
    }

    @Test
    public void twoSignaturesReceiverOnTheCurrentSecretMatchesTheFirst() throws Exception {
        // The helper used to keep only the LAST v1 (the previous secret's), so a
        // receiver already switched to the new secret rejected every delivery.
        String t = now();
        assertTrue(PdfikClient.verifyWebhookSignature(BODY, headers(rotationSignature(t), t), CURRENT));
    }

    @Test
    public void twoSignaturesReceiverOnThePreviousSecretMatchesTheSecond() throws Exception {
        String t = now();
        assertTrue(PdfikClient.verifyWebhookSignature(BODY, headers(rotationSignature(t), t), PREVIOUS));
    }

    @Test
    public void twoSignaturesMatchingNeitherSecretAreRejected() throws Exception {
        String t = now();
        assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers(rotationSignature(t), t), "whsec_unrelated"));
    }

    @Test
    public void theThreeHundredSecondWindowAppliesToEverySignature() throws Exception {
        String stale = String.valueOf(System.currentTimeMillis() / 1000 - 400);
        Map<String, String> headers = headers(rotationSignature(stale), stale);

        assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers, CURRENT));
        assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers, PREVIOUS));
    }

    @Test
    public void anAbsurdTimestampDoesNotWrapIntoTheWindow() throws Exception {
        // The check used to be Math.abs(now - t) > 300. For t = now - 2^63 the difference
        // overflows to Long.MIN_VALUE, Math.abs leaves it negative, and the window passed.
        // Several seconds around now are tried, so the clock ticking over between here
        // and the helper's own reading cannot hide the case.
        long now = System.currentTimeMillis() / 1000;
        for (long second = now - 2; second <= now + 3; second++) {
            String t = String.valueOf(second + Long.MIN_VALUE);
            Map<String, String> headers = headers("t=" + t + ",v1=" + sign(CURRENT, t), t);
            assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers, CURRENT), t);
        }
    }

    @Test
    public void malformedSignatureHeadersAreRejected() throws Exception {
        String t = now();
        String[] malformed = {
                "v1=" + sign(CURRENT, t) + ",v1=" + sign(PREVIOUS, t), // no t
                "t=" + t, // no v1
                "t=" + t + ",v1=,v1=", // empty v1 values
                "garbage",
                "t=soon,v1=" + sign(CURRENT, "soon"), // signed, but t is not a number
        };
        for (String signature : malformed) {
            assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers(signature, t), CURRENT), signature);
        }
    }

    @Test
    public void anEmptySecretNeverVerifies() throws Exception {
        String t = now();
        assertFalse(PdfikClient.verifyWebhookSignature(BODY, headers("t=" + t + ",v1=" + sign(CURRENT, t), t), ""));
    }
}
