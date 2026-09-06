package net.pdfik;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import net.pdfik.models.*;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

public class PdfikClientTest {
    private static WireMockServer wireMockServer;
    private PdfikClient client;

    @BeforeAll
    public static void setUpServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    public static void tearDownServer() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    public void setUpClient() {
        wireMockServer.resetAll();
        client = PdfikClient.builder()
                .apiKey("sk_test_123")
                .baseUrl(wireMockServer.baseUrl())
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    public void tearDownClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    public void testUrlToPdfSuccess() {
        wireMockServer.stubFor(post(urlEqualTo("/url-to-pdf"))
                .withHeader("X-API-Key", equalTo("sk_test_123"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-123\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        PdfOptions options = PdfOptions.builder().format(PaperFormat.A4).landscape(true).build();
        JobCreatedResponse response = client.urlToPdf("https://example.com", options, null, null);

        assertNotNull(response);
        assertEquals("job-123", response.getJobId());
        assertEquals("queued", response.getStatus());
        assertEquals("Job queued", response.getDetail());
    }

    @Test
    public void testHtmlToPdfSuccess() {
        wireMockServer.stubFor(post(urlEqualTo("/html-to-pdf"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-456\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        JobCreatedResponse response = client.htmlToPdf("<h1>Hello</h1>", null, null, null);

        assertNotNull(response);
        assertEquals("job-456", response.getJobId());
    }

    @Test
    public void testUrlToPdfTestModeOverloadSendsTestFlag() {
        wireMockServer.stubFor(post(urlEqualTo("/url-to-pdf"))
                .withRequestBody(matchingJsonPath("$.test", equalTo("true")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-789\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        JobCreatedResponse response = client.urlToPdf("https://example.com", true);

        assertNotNull(response);
        assertEquals("job-789", response.getJobId());
    }

    @Test
    public void testEinvoiceToPdfSendsXmlAndOmitsUnsetProfile() {
        wireMockServer.stubFor(post(urlEqualTo("/einvoice-to-pdf"))
                .withHeader("X-API-Key", equalTo("sk_test_123"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.xml", equalTo("<rsm:CrossIndustryInvoice/>")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-einv\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        JobCreatedResponse response = client.einvoiceToPdf("<rsm:CrossIndustryInvoice/>");

        assertNotNull(response);
        assertEquals("job-einv", response.getJobId());
        assertEquals("queued", response.getStatus());
        // profile is omitted when unset (the server defaults to en16931)
        wireMockServer.verify(postRequestedFor(urlEqualTo("/einvoice-to-pdf"))
                .withRequestBody(notContaining("\"profile\"")));
    }

    @Test
    public void testEinvoiceToPdfFullRequestSendsProfileTemplateIdAndIdempotencyKey() {
        wireMockServer.stubFor(post(urlEqualTo("/einvoice-to-pdf"))
                .withHeader("Idempotency-Key", equalTo("einv-key-1"))
                .withRequestBody(matchingJsonPath("$.profile", equalTo("extended")))
                .withRequestBody(matchingJsonPath("$.template_id", equalTo("tpl-123")))
                .withRequestBody(matchingJsonPath("$.webhook_url", equalTo("https://example.com/hook")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-einv-2\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        EInvoiceToPdfRequest request = new EInvoiceToPdfRequest("<rsm:CrossIndustryInvoice/>");
        request.setProfile("extended");
        request.setTemplateId("tpl-123");
        request.setWebhookUrl("https://example.com/hook");
        JobCreatedResponse response = client.einvoiceToPdf(request, "einv-key-1");

        assertNotNull(response);
        assertEquals("job-einv-2", response.getJobId());
    }

    @Test
    public void testEinvoiceToPdfXmlInvalidNoRetry() {
        wireMockServer.stubFor(post(urlEqualTo("/einvoice-to-pdf"))
                .willReturn(aResponse()
                        .withStatus(422)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("{\"type\":\"https://docs.pdfik.net/error-codes#einvoice-xml-invalid\",\"title\":\"Unprocessable Entity - Invoice XML Invalid\",\"status\":422,\"error\":\"EINVOICE_XML_INVALID\",\"detail\":\"XML failed XSD validation\"}")));

        PdfikException exception = assertThrows(PdfikException.class, () -> {
            client.einvoiceToPdf("<broken/>");
        });

        assertEquals(422, exception.getStatusCode());
        assertEquals("XML failed XSD validation", exception.getMessage());
        assertTrue(exception.getResponseBody().contains("EINVOICE_XML_INVALID"));
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/einvoice-to-pdf")));
    }

    @Test
    public void testEinvoiceToPdfAsync() throws ExecutionException, InterruptedException {
        wireMockServer.stubFor(post(urlEqualTo("/einvoice-to-pdf"))
                .withRequestBody(matchingJsonPath("$.profile", equalTo("basic")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-einv-async\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        CompletableFuture<JobCreatedResponse> future = client.einvoiceToPdfAsync("<rsm:CrossIndustryInvoice/>", "basic");
        JobCreatedResponse response = future.get();

        assertNotNull(response);
        assertEquals("job-einv-async", response.getJobId());
    }

    @Test
    public void testUrlToPdfWithEinvoiceOptionSendsEinvoiceBlock() {
        wireMockServer.stubFor(post(urlEqualTo("/url-to-pdf"))
                .withRequestBody(matchingJsonPath("$.einvoice.format", equalTo("factur-x")))
                .withRequestBody(matchingJsonPath("$.einvoice.profile", equalTo("en16931")))
                .withRequestBody(matchingJsonPath("$.einvoice.xml", equalTo("<rsm:CrossIndustryInvoice/>")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-einv-url\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        EInvoiceOptions einvoice = new EInvoiceOptions("<rsm:CrossIndustryInvoice/>", "en16931");
        JobCreatedResponse response = client.urlToPdf("https://example.com/invoice", null, null, null, null, einvoice, null, null);

        assertNotNull(response);
        assertEquals("job-einv-url", response.getJobId());
    }

    @Test
    public void testHtmlToPdfWithEinvoiceOptionSendsEinvoiceBlock() {
        wireMockServer.stubFor(post(urlEqualTo("/html-to-pdf"))
                .withRequestBody(matchingJsonPath("$.einvoice.format", equalTo("factur-x")))
                .withRequestBody(matchingJsonPath("$.einvoice.xml", equalTo("<rsm:CrossIndustryInvoice/>")))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-einv-html\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        JobCreatedResponse response = client.htmlToPdf("<h1>Invoice</h1>", null, null, null,
                new EInvoiceOptions("<rsm:CrossIndustryInvoice/>"), null, null);

        assertNotNull(response);
        assertEquals("job-einv-html", response.getJobId());
    }

    @Test
    public void testWaitForJobPolls() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .inScenario("Polling")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"queued\"}"))
                .willSetStateTo("SecondCall"));

        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .inScenario("Polling")
                .whenScenarioStateIs("SecondCall")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"rendering\"}"))
                .willSetStateTo("ThirdCall"));

        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .inScenario("Polling")
                .whenScenarioStateIs("ThirdCall")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"done\",\"pages_count\":5}")));

        JobStatusResponse response = client.waitForJob("job-123", Duration.ofSeconds(5), Duration.ofMillis(10));
        assertNotNull(response);
        assertEquals(JobStatus.DONE, response.getStatus());
        assertEquals(5, response.getPagesCount());
    }

    @Test
    public void testWaitForJobThrowsOnFailed() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"failed\",\"error_code\":\"SSRF_BLOCKED\"}")));

        PdfikException exception = assertThrows(PdfikException.class, () -> {
            client.waitForJob("job-123", Duration.ofSeconds(5), Duration.ofMillis(10));
        });

        assertEquals(400, exception.getStatusCode());
        assertEquals("SSRF_BLOCKED", exception.getErrorCode());
    }

    @Test
    public void testWaitForJobTimeout() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"queued\"}")));

        PdfikException exception = assertThrows(PdfikException.class, () -> {
            client.waitForJob("job-123", Duration.ofMillis(50), Duration.ofMillis(10));
        });

        assertEquals(408, exception.getStatusCode());
        assertEquals("TIMEOUT", exception.getErrorCode());
    }

    @Test
    public void testRetryOn500() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withBody("Bad Gateway")));

        // Set connect timeout very short for fast test, and baseUrl points to wiremock
        // The retry will take 1s -> 2s (total 3s). To make tests faster, we accept it or we can stub retry delay.
        // Let's run it, 3s is acceptable in tests.
        PdfikException exception = assertThrows(PdfikException.class, () -> {
            client.getJob("job-123");
        });

        assertEquals(502, exception.getStatusCode());
        // Verify 3 requests were sent
        wireMockServer.verify(3, getRequestedFor(urlEqualTo("/jobs/job-123")));
    }

    @Test
    public void testRetryOn429ThenSuccess() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .inScenario("RetryRateLimit")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("Rate limit exceeded"))
                .willSetStateTo("SuccessCall"));

        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .inScenario("RetryRateLimit")
                .whenScenarioStateIs("SuccessCall")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"done\"}")));

        JobStatusResponse response = client.getJob("job-123");
        assertNotNull(response);
        assertEquals(JobStatus.DONE, response.getStatus());
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/jobs/job-123")));
    }

    @Test
    public void testNoRetryOn401() {
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withBody("{\"detail\":\"Invalid API key\"}")));

        PdfikException exception = assertThrows(PdfikException.class, () -> {
            client.getJob("job-123");
        });

        assertEquals(401, exception.getStatusCode());
        assertEquals("Invalid API key", exception.getMessage());
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/jobs/job-123")));
    }

    @Test
    public void testAsyncUrlToPdf() throws ExecutionException, InterruptedException {
        wireMockServer.stubFor(post(urlEqualTo("/url-to-pdf"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"job_id\":\"job-async\",\"status\":\"queued\",\"detail\":\"Job queued\"}")));

        CompletableFuture<JobCreatedResponse> future = client.urlToPdfAsync("https://example.com", null, null, null);
        JobCreatedResponse response = future.get();

        assertNotNull(response);
        assertEquals("job-async", response.getJobId());
    }

    @Test
    public void testDownloadPdfReturnsByteArray() {
        byte[] fakePdf = new byte[]{0, 1, 2, 3, 4};
        wireMockServer.stubFor(get(urlEqualTo("/jobs/job-123/download"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/pdf")
                        .withBody(fakePdf)));

        byte[] result = client.downloadPdf("job-123");
        assertNotNull(result);
        assertArrayEquals(fakePdf, result);
    }

    @Test
    public void testVerifyWebhookSignature() throws Exception {
        String rawBody = "{\"job_id\":\"123\",\"event_type\":\"job.completed\"}";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String secret = "whsec_testsecret";

        String signedPayload = timestamp + "." + rawBody;
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"
        );
        mac.init(secretKey);
        byte[] rawHmac = mac.doFinal(signedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : rawHmac) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String signature = hexString.toString();

        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-PDFik-Signature", "t=" + timestamp + ",v1=" + signature);
        headers.put("X-PDFik-Timestamp", timestamp);

        assertTrue(PdfikClient.verifyWebhookSignature(rawBody, headers, secret));
        assertFalse(PdfikClient.verifyWebhookSignature(rawBody, headers, "wrong_secret"));
        assertFalse(PdfikClient.verifyWebhookSignature(rawBody, new java.util.HashMap<>(), secret));
    }
}
