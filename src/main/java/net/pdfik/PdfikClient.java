package net.pdfik;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.pdfik.models.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class PdfikClient implements AutoCloseable {
    private final String apiKey;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private PdfikClient(Builder builder) {
        if (builder.apiKey == null || builder.apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key is required");
        }
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl.replaceAll("/+$", "");
        this.requestTimeout = builder.requestTimeout;
        
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .build();

        this.objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule())
                .registerModule(LenientInstant.module())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    private <T> HttpResponse<T> executeWithRetry(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        int attempt = 0;
        while (true) {
            attempt++;
            HttpRequest request = requestSupplier.get();
            try {
                HttpResponse<T> response = httpClient.send(request, responseBodyHandler);
                int status = response.statusCode();
                if ((status >= 500 || status == 429) && attempt < 3) {
                    long delay = (long) (Math.pow(2, attempt - 1) * 1000);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new PdfikException("Request interrupted during retry delay", status, null, null);
                    }
                    continue;
                }
                return response;
            } catch (java.io.IOException e) {
                if (attempt < 3) {
                    long delay = (long) (Math.pow(2, attempt - 1) * 1000);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PdfikException("Request interrupted during retry delay", 503, null, null);
                    }
                    continue;
                }
                throw new PdfikException("Connection failed: " + e.getMessage(), 503, null, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PdfikException("Request interrupted", 500, null, null);
            }
        }
    }

    private <T> CompletableFuture<HttpResponse<T>> executeWithRetryAsync(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler) {
        return executeWithRetryAsync(requestSupplier, responseBodyHandler, 1);
    }

    private <T> CompletableFuture<HttpResponse<T>> executeWithRetryAsync(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            int attempt) {
        HttpRequest request = requestSupplier.get();
        return httpClient.sendAsync(request, responseBodyHandler)
                .thenCompose(response -> {
                    int status = response.statusCode();
                    if ((status >= 500 || status == 429) && attempt < 3) {
                        long delay = (long) (Math.pow(2, attempt - 1) * 1000);
                        CompletableFuture<HttpResponse<T>> delayedFuture = new CompletableFuture<>();
                        scheduler.schedule(() -> {
                            executeWithRetryAsync(requestSupplier, responseBodyHandler, attempt + 1)
                                    .whenComplete((res, ex) -> {
                                        if (ex != null) delayedFuture.completeExceptionally(ex);
                                        else delayedFuture.complete(res);
                                    });
                        }, delay, TimeUnit.MILLISECONDS);
                        return delayedFuture;
                    }
                    return CompletableFuture.completedFuture(response);
                })
                // Not exceptionallyCompose(): that is Java 12+, and this SDK promises Java 11.
                // handle() + thenCompose() is the Java 11 spelling of the same thing.
                .handle((response, ex) -> ex == null
                        ? CompletableFuture.completedFuture(response)
                        : retryAfterFailure(requestSupplier, responseBodyHandler, attempt, ex))
                .thenCompose(f -> f);
    }

    private <T> CompletableFuture<HttpResponse<T>> retryAfterFailure(
            Supplier<HttpRequest> requestSupplier,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            int attempt,
            Throwable ex) {
        if (attempt < 3) {
            long delay = (long) (Math.pow(2, attempt - 1) * 1000);
            CompletableFuture<HttpResponse<T>> delayedFuture = new CompletableFuture<>();
            scheduler.schedule(() -> {
                executeWithRetryAsync(requestSupplier, responseBodyHandler, attempt + 1)
                        .whenComplete((res, ex2) -> {
                            if (ex2 != null) delayedFuture.completeExceptionally(ex2);
                            else delayedFuture.complete(res);
                        });
            }, delay, TimeUnit.MILLISECONDS);
            return delayedFuture;
        }
        Throwable actualEx = (ex instanceof CompletionException) ? ex.getCause() : ex;
        // An API error (4xx after retries) is already a PdfikException: keep it.
        if (actualEx instanceof PdfikException) return CompletableFuture.failedFuture(actualEx);
        return CompletableFuture.failedFuture(new PdfikException("Connection failed: " + actualEx.getMessage(), 503, null, null));
    }

    /**
     * Readable text for an error body's {@code detail}. Request-validation errors (422)
     * carry an ARRAY of {@code {loc, msg}} items; {@code asText()} on an array is "",
     * so the message used to be empty. They read as
     * "options.viewport.width: Input should be less than or equal to 1920".
     */
    /**
     * Milliseconds to wait after a 429 while polling: the API's {@code retry_after_seconds}
     * (capped at 60 s so the server cannot park the caller), else {@code fallbackMs}.
     */
    long throttleDelayMs(PdfikException e, long fallbackMs) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBody() == null ? "{}" : e.getResponseBody());
            long seconds = node.path("retry_after_seconds").asLong(0);
            if (seconds > 0) return Math.min(seconds, 60) * 1000L;
        } catch (Exception ignored) {
            // not JSON — fall back
        }
        return fallbackMs;
    }

    static String errorDetail(JsonNode detail) {
        if (detail == null || detail.isNull()) return null;
        if (detail.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : detail) {
                StringBuilder loc = new StringBuilder();
                for (JsonNode part : item.path("loc")) {
                    if ("body".equals(part.asText())) continue;
                    if (loc.length() > 0) loc.append('.');
                    loc.append(part.asText());
                }
                String msg = item.hasNonNull("msg") ? item.get("msg").asText() : item.toString();
                if (sb.length() > 0) sb.append("; ");
                sb.append(loc.length() > 0 ? loc + ": " + msg : msg);
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        String text = detail.isTextual() ? detail.asText() : detail.toString();
        return text.isEmpty() ? null : text;
    }

    private <T> void checkResponse(HttpResponse<T> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = "";
            if (response.body() != null) {
                body = response.body().toString();
            }
            String message = "API request failed with status " + status;
            String errorCode = null;
            try {
                JsonNode node = objectMapper.readTree(body);
                String detail = errorDetail(node.get("detail"));
                if (detail != null) {
                    message = detail;
                }
                // pdf-api's RFC 7807 bodies carry the code in "error";
                // "error_code" is a fallback for any body that still uses it.
                if (node.hasNonNull("error")) {
                    errorCode = node.get("error").asText();
                } else if (node.hasNonNull("error_code")) {
                    errorCode = node.get("error_code").asText();
                }
            } catch (Exception ignored) {}
            throw new PdfikException(message, status, errorCode, body);
        }
    }

    private Supplier<HttpRequest> createPostRequest(String path, Object body) {
        return createPostRequest(path, body, null);
    }

    private Supplier<HttpRequest> createPostRequest(String path, Object body, String idempotencyKey) {
        return () -> {
            try {
                String json = objectMapper.writeValueAsString(body);
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path))
                        .timeout(requestTimeout)
                        .header("X-API-Key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
                if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                    builder.header("Idempotency-Key", idempotencyKey);
                }
                return builder.build();
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize request body", e);
            }
        };
    }

    private Supplier<HttpRequest> createGetRequest(String path) {
        return () -> HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("X-API-Key", apiKey)
                .GET()
                .build();
    }

    // Synchronous API

    public JobCreatedResponse urlToPdf(String url) {
        return urlToPdf(url, null, null, null, null);
    }

    public JobCreatedResponse urlToPdf(String url, PdfOptions options) {
        return urlToPdf(url, options, null, null, null);
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public JobCreatedResponse urlToPdf(String url, boolean test) {
        return urlToPdf(url, null, null, null, null, null, test);
    }

    public JobCreatedResponse urlToPdf(String url, PdfOptions options, RenderOptions render, String webhookUrl) {
        return urlToPdf(url, options, render, webhookUrl, null);
    }

    public JobCreatedResponse urlToPdf(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth) {
        return urlToPdf(url, options, render, webhookUrl, auth, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse urlToPdf(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth, String idempotencyKey) {
        return urlToPdf(url, options, render, webhookUrl, auth, idempotencyKey, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     * @param test           optional test-mode flag: when {@code true} the job goes
     *                       through the full pipeline (statuses, webhook, download)
     *                       without real rendering, and the download returns a sample
     *                       PDF. Quotas are not debited — test jobs are free and
     *                       rate-limited instead (60/min, 2,000/day). Job status and webhook
     *                       payloads always carry {@code test: true|false}.
     */
    public JobCreatedResponse urlToPdf(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth, String idempotencyKey, Boolean test) {
        return urlToPdf(url, options, render, webhookUrl, auth, null, idempotencyKey, test);
    }

    /**
     * @param einvoice       optional Factur-X e-invoicing: the rendered page becomes the
     *                       human-readable half of a hybrid e-invoice — the output is
     *                       normalized to PDF/A-3 with the XML embedded as
     *                       {@code factur-x.xml}. Mutually exclusive with
     *                       {@code options.userPassword} and {@code options.compression}.
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     * @param test           optional test-mode flag, see
     *                       {@link #urlToPdf(String, PdfOptions, RenderOptions, String, JobAuthOptions, String, Boolean)}
     */
    public JobCreatedResponse urlToPdf(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth, EInvoiceOptions einvoice, String idempotencyKey, Boolean test) {
        UrlToPdfRequest body = new UrlToPdfRequest(url, webhookUrl, options, render, auth, einvoice, test);
        return urlToPdf(body, idempotencyKey);
    }

    /**
     * Request-object variant of {@code urlToPdf} — gives access to every field,
     * including {@code delivery} (BYOB: the output is uploaded straight to your
     * own bucket via a presigned PUT URL, nothing is stored on PDFik's side;
     * not combinable with test mode).
     */
    public JobCreatedResponse urlToPdf(UrlToPdfRequest request) {
        return urlToPdf(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse urlToPdf(UrlToPdfRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/url-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    public JobCreatedResponse htmlToPdf(String html) {
        return htmlToPdf(html, null, null, null);
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public JobCreatedResponse htmlToPdf(String html, boolean test) {
        return htmlToPdf(html, null, null, null, null, test);
    }

    public JobCreatedResponse htmlToPdf(String html, PdfOptions options) {
        return htmlToPdf(html, options, null, null);
    }

    public JobCreatedResponse htmlToPdf(String html, PdfOptions options, RenderOptions render, String webhookUrl) {
        return htmlToPdf(html, options, render, webhookUrl, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse htmlToPdf(String html, PdfOptions options, RenderOptions render, String webhookUrl, String idempotencyKey) {
        return htmlToPdf(html, options, render, webhookUrl, idempotencyKey, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     * @param test           optional test-mode flag: when {@code true} the job goes
     *                       through the full pipeline (statuses, webhook, download)
     *                       without real rendering, and the download returns a sample
     *                       PDF. Quotas are not debited — test jobs are free and
     *                       rate-limited instead (60/min, 2,000/day). Job status and webhook
     *                       payloads always carry {@code test: true|false}.
     */
    public JobCreatedResponse htmlToPdf(String html, PdfOptions options, RenderOptions render, String webhookUrl, String idempotencyKey, Boolean test) {
        return htmlToPdf(html, options, render, webhookUrl, null, idempotencyKey, test);
    }

    /**
     * @param einvoice       optional Factur-X e-invoicing: the rendered page becomes the
     *                       human-readable half of a hybrid e-invoice — the output is
     *                       normalized to PDF/A-3 with the XML embedded as
     *                       {@code factur-x.xml}. Mutually exclusive with
     *                       {@code options.userPassword} and {@code options.compression}.
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     * @param test           optional test-mode flag, see
     *                       {@link #htmlToPdf(String, PdfOptions, RenderOptions, String, String, Boolean)}
     */
    public JobCreatedResponse htmlToPdf(String html, PdfOptions options, RenderOptions render, String webhookUrl, EInvoiceOptions einvoice, String idempotencyKey, Boolean test) {
        HtmlToPdfRequest body = new HtmlToPdfRequest(html, webhookUrl, options, render, einvoice, test);
        return htmlToPdf(body, idempotencyKey);
    }

    /**
     * Request-object variant of {@code htmlToPdf} — gives access to every field,
     * including {@code delivery} (BYOB: the output is uploaded straight to your
     * own bucket via a presigned PUT URL, nothing is stored on PDFik's side;
     * not combinable with test mode).
     */
    public JobCreatedResponse htmlToPdf(HtmlToPdfRequest request) {
        return htmlToPdf(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse htmlToPdf(HtmlToPdfRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/html-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    /**
     * Generates a Factur-X e-invoice PDF from UN/CEFACT Cross-Industry-Invoice XML
     * using the account default block template and the default "en16931" profile.
     * PDFik builds the human-readable invoice, renders it to PDF/A-3 and embeds the
     * XML as {@code factur-x.xml}; then poll {@code getJob}/{@code waitForJob} and
     * download as usual. The output is the same clean PDF/A-3 on every plan.
     */
    public JobCreatedResponse einvoiceToPdf(String xml) {
        return einvoiceToPdf(new EInvoiceToPdfRequest(xml), null);
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public JobCreatedResponse einvoiceToPdf(String xml, boolean test) {
        EInvoiceToPdfRequest body = new EInvoiceToPdfRequest(xml);
        body.setTest(test);
        return einvoiceToPdf(body, null);
    }

    /**
     * @param profile Factur-X conformance profile the XML declares: "minimum",
     *                "basicwl", "basic", "en16931" or "extended". "minimum" and
     *                "basicwl" are accompanying data only and are NOT a legally
     *                sufficient e-invoice.
     */
    public JobCreatedResponse einvoiceToPdf(String xml, String profile) {
        return einvoiceToPdf(new EInvoiceToPdfRequest(xml, profile), null);
    }

    public JobCreatedResponse einvoiceToPdf(EInvoiceToPdfRequest request) {
        return einvoiceToPdf(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse einvoiceToPdf(EInvoiceToPdfRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/einvoice-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    /**
     * Converts Markdown (CommonMark + GFM tables and strikethrough) to a PDF
     * with a built-in print stylesheet. Raw HTML inside the Markdown is
     * escaped, not rendered — use {@code htmlToPdf} for full HTML control.
     * Job polling and download work exactly as on the other PDF endpoints.
     */
    public JobCreatedResponse markdownToPdf(String markdown) {
        return markdownToPdf(new MarkdownToPdfRequest(markdown), null);
    }

    /**
     * Full-control variant: the request object carries {@code options} (paper
     * format, margins, header/footer, watermark, ... — same as
     * {@code htmlToPdf}), {@code render}, {@code webhookUrl},
     * {@code delivery} (BYOB) and {@code test}.
     */
    public JobCreatedResponse markdownToPdf(MarkdownToPdfRequest request) {
        return markdownToPdf(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse markdownToPdf(MarkdownToPdfRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/markdown-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    /**
     * Captures a public URL as a PNG screenshot of the full scrollable page
     * (the server defaults). Poll with {@code waitForJob} and fetch the bytes
     * with {@code downloadPdf} — for image jobs it returns the raw
     * {@code image/png} or {@code image/jpeg} bytes instead of a PDF.
     */
    public JobCreatedResponse urlToImage(String url) {
        return urlToImage(new UrlToImageRequest(url), null);
    }

    /**
     * Full-control variant: the request object carries {@code options}
     * (format png/jpeg, fullPage, quality, viewport), {@code render},
     * {@code webhookUrl}, {@code auth} (Pro+), {@code delivery} (BYOB) and
     * {@code test}.
     */
    public JobCreatedResponse urlToImage(UrlToImageRequest request) {
        return urlToImage(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse urlToImage(UrlToImageRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/url-to-image", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    /**
     * Captures raw HTML markup as a PNG screenshot of the full scrollable
     * page (the server defaults). Poll with {@code waitForJob} and fetch the
     * bytes with {@code downloadPdf} — for image jobs it returns the raw
     * {@code image/png} or {@code image/jpeg} bytes instead of a PDF.
     */
    public JobCreatedResponse htmlToImage(String html) {
        return htmlToImage(new HtmlToImageRequest(html), null);
    }

    /**
     * Full-control variant: the request object carries {@code options}
     * (format png/jpeg, fullPage, quality, viewport), {@code render},
     * {@code webhookUrl}, {@code delivery} (BYOB) and {@code test}.
     */
    public JobCreatedResponse htmlToImage(HtmlToImageRequest request) {
        return htmlToImage(request, null);
    }

    /**
     * @param idempotencyKey optional Idempotency-Key; retrying with the same key
     *                       returns the original job instead of creating a duplicate
     */
    public JobCreatedResponse htmlToImage(HtmlToImageRequest request, String idempotencyKey) {
        HttpResponse<String> response = executeWithRetry(createPostRequest("/html-to-image", request, idempotencyKey), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobCreatedResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    public JobStatusResponse getJob(String jobId) {
        HttpResponse<String> response = executeWithRetry(createGetRequest("/jobs/" + jobId), HttpResponse.BodyHandlers.ofString());
        checkResponse(response);
        try {
            return objectMapper.readValue(response.body(), JobStatusResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body", e);
        }
    }

    public JobStatusResponse waitForJob(String jobId) {
        return waitForJob(jobId, Duration.ofSeconds(120), Duration.ofSeconds(2));
    }

    public JobStatusResponse waitForJob(String jobId, Duration timeout, Duration pollInterval) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();
        while (true) {
            JobStatusResponse job;
            try {
                job = getJob(jobId);
            } catch (PdfikException e) {
                // A 429 while polling is a throttle, not a failure: on Free (10
                // requests/min) a render longer than ~20 s used to end the wait with
                // an error. Wait it out, within the same timeout.
                if (e.getStatusCode() != 429) throw e;
                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    throw new PdfikException("Job " + jobId + " timed out", 408, "TIMEOUT", null);
                }
                try {
                    Thread.sleep(throttleDelayMs(e, pollInterval.toMillis()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new PdfikException("Polling interrupted", 500, null, null);
                }
                continue;
            }
            if (job.getStatus() == JobStatus.DONE || job.getStatus() == JobStatus.FAILED) {
                if (job.getStatus() == JobStatus.FAILED) {
                    throw new PdfikException(
                            "Job " + jobId + " failed",
                            400,
                            job.getErrorCode() != null ? job.getErrorCode() : "JOB_FAILED",
                            null
                    );
                }
                return job;
            }
            if (System.currentTimeMillis() - startTime >= timeoutMs) {
                throw new PdfikException("Job " + jobId + " timed out", 408, "TIMEOUT", null);
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PdfikException("Polling interrupted", 500, null, null);
            }
        }
    }

    public JobFileResponse getFileUrl(String jobId) {
        JobFileResponse res = new JobFileResponse();
        res.setJobId(jobId);
        String cleanBaseUrl = this.baseUrl.replaceAll("/+$", "");
        res.setDownloadUrl(cleanBaseUrl + "/jobs/" + jobId + "/download");
        res.setExpiresInSeconds(-1);
        return res;
    }

    /**
     * Downloads the finished job's output bytes. Despite the name it works for
     * every job type: PDF jobs return the PDF, image jobs ({@code urlToImage} /
     * {@code htmlToImage}) return the raw PNG/JPEG bytes. Jobs submitted with
     * the {@code delivery} option have no download here — the file lives in
     * your own bucket (the {@code job.finished} webhook's {@code file_url})
     * and this endpoint answers 404.
     */
    public byte[] downloadPdf(String jobId) {
        HttpResponse<byte[]> response = executeWithRetry(createGetRequest("/jobs/" + jobId + "/download"), HttpResponse.BodyHandlers.ofByteArray());
        checkResponse(response);
        return response.body();
    }

    // Asynchronous API (CompletableFuture)

    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url) {
        return urlToPdfAsync(url, null, null, null, null);
    }

    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, PdfOptions options) {
        return urlToPdfAsync(url, options, null, null, null);
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, boolean test) {
        return urlToPdfAsync(url, null, null, null, null, test);
    }

    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, PdfOptions options, RenderOptions render, String webhookUrl) {
        return urlToPdfAsync(url, options, render, webhookUrl, null);
    }

    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth) {
        return urlToPdfAsync(url, options, render, webhookUrl, auth, null);
    }

    /**
     * @param test optional test-mode flag: when {@code true} the job goes through the
     *             full pipeline (statuses, webhook, download) without real rendering,
     *             and the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     *             Job status and webhook payloads always carry {@code test: true|false}.
     */
    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth, Boolean test) {
        return urlToPdfAsync(url, options, render, webhookUrl, auth, null, test);
    }

    /**
     * @param einvoice optional Factur-X e-invoicing: the rendered page becomes the
     *                 human-readable half of a hybrid e-invoice — the output is
     *                 normalized to PDF/A-3 with the XML embedded as
     *                 {@code factur-x.xml}. Mutually exclusive with
     *                 {@code options.userPassword} and {@code options.compression}.
     * @param test     optional test-mode flag, see
     *                 {@link #urlToPdfAsync(String, PdfOptions, RenderOptions, String, JobAuthOptions, Boolean)}
     */
    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(String url, PdfOptions options, RenderOptions render, String webhookUrl, JobAuthOptions auth, EInvoiceOptions einvoice, Boolean test) {
        UrlToPdfRequest body = new UrlToPdfRequest(url, webhookUrl, options, render, auth, einvoice, test);
        return urlToPdfAsync(body);
    }

    /**
     * Request-object variant of {@code urlToPdfAsync} — gives access to every
     * field, including {@code delivery} (BYOB: the output is uploaded straight
     * to your own bucket via a presigned PUT URL; not combinable with test mode).
     */
    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(UrlToPdfRequest request) {
        return urlToPdfAsync(request, null);
    }

    /** Async twin of {@code urlToPdf(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> urlToPdfAsync(UrlToPdfRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/url-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html) {
        return htmlToPdfAsync(html, null, null, null);
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html, boolean test) {
        return htmlToPdfAsync(html, null, null, null, test);
    }

    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html, PdfOptions options) {
        return htmlToPdfAsync(html, options, null, null);
    }

    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html, PdfOptions options, RenderOptions render, String webhookUrl) {
        return htmlToPdfAsync(html, options, render, webhookUrl, (Boolean) null);
    }

    /**
     * @param test optional test-mode flag: when {@code true} the job goes through the
     *             full pipeline (statuses, webhook, download) without real rendering,
     *             and the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     *             Job status and webhook payloads always carry {@code test: true|false}.
     */
    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html, PdfOptions options, RenderOptions render, String webhookUrl, Boolean test) {
        return htmlToPdfAsync(html, options, render, webhookUrl, null, test);
    }

    /**
     * @param einvoice optional Factur-X e-invoicing: the rendered page becomes the
     *                 human-readable half of a hybrid e-invoice — the output is
     *                 normalized to PDF/A-3 with the XML embedded as
     *                 {@code factur-x.xml}. Mutually exclusive with
     *                 {@code options.userPassword} and {@code options.compression}.
     * @param test     optional test-mode flag, see
     *                 {@link #htmlToPdfAsync(String, PdfOptions, RenderOptions, String, Boolean)}
     */
    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(String html, PdfOptions options, RenderOptions render, String webhookUrl, EInvoiceOptions einvoice, Boolean test) {
        HtmlToPdfRequest body = new HtmlToPdfRequest(html, webhookUrl, options, render, einvoice, test);
        return htmlToPdfAsync(body);
    }

    /**
     * Request-object variant of {@code htmlToPdfAsync} — gives access to every
     * field, including {@code delivery} (BYOB: the output is uploaded straight
     * to your own bucket via a presigned PUT URL; not combinable with test mode).
     */
    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(HtmlToPdfRequest request) {
        return htmlToPdfAsync(request, null);
    }

    /** Async twin of {@code htmlToPdf(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> htmlToPdfAsync(HtmlToPdfRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/html-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    /**
     * Asynchronous variant of {@link #einvoiceToPdf(String)}: generates a Factur-X
     * e-invoice PDF from UN/CEFACT Cross-Industry-Invoice XML using the account
     * default block template and the default "en16931" profile.
     */
    public CompletableFuture<JobCreatedResponse> einvoiceToPdfAsync(String xml) {
        return einvoiceToPdfAsync(new EInvoiceToPdfRequest(xml));
    }

    /**
     * @param test test-mode flag: when {@code true} the job goes through the full
     *             pipeline (statuses, webhook, download) without real rendering, and
     *             the download returns a sample PDF. Quotas are not debited — test
     *             jobs are free and rate-limited instead (60/min, 2,000/day).
     */
    public CompletableFuture<JobCreatedResponse> einvoiceToPdfAsync(String xml, boolean test) {
        EInvoiceToPdfRequest body = new EInvoiceToPdfRequest(xml);
        body.setTest(test);
        return einvoiceToPdfAsync(body);
    }

    /**
     * @param profile Factur-X conformance profile the XML declares: "minimum",
     *                "basicwl", "basic", "en16931" or "extended". "minimum" and
     *                "basicwl" are accompanying data only and are NOT a legally
     *                sufficient e-invoice.
     */
    public CompletableFuture<JobCreatedResponse> einvoiceToPdfAsync(String xml, String profile) {
        return einvoiceToPdfAsync(new EInvoiceToPdfRequest(xml, profile));
    }

    public CompletableFuture<JobCreatedResponse> einvoiceToPdfAsync(EInvoiceToPdfRequest request) {
        return einvoiceToPdfAsync(request, null);
    }

    /** Async twin of {@code einvoiceToPdf(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> einvoiceToPdfAsync(EInvoiceToPdfRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/einvoice-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    /**
     * Asynchronous variant of {@link #markdownToPdf(String)}: converts Markdown
     * (CommonMark + GFM tables and strikethrough) to a PDF with a built-in
     * print stylesheet.
     */
    public CompletableFuture<JobCreatedResponse> markdownToPdfAsync(String markdown) {
        return markdownToPdfAsync(new MarkdownToPdfRequest(markdown));
    }

    /**
     * Asynchronous variant of {@link #markdownToPdf(MarkdownToPdfRequest)}.
     */
    public CompletableFuture<JobCreatedResponse> markdownToPdfAsync(MarkdownToPdfRequest request) {
        return markdownToPdfAsync(request, null);
    }

    /** Async twin of {@code markdownToPdf(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> markdownToPdfAsync(MarkdownToPdfRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/markdown-to-pdf", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    /**
     * Asynchronous variant of {@link #urlToImage(String)}: captures a public
     * URL as a PNG screenshot of the full scrollable page (the server defaults).
     */
    public CompletableFuture<JobCreatedResponse> urlToImageAsync(String url) {
        return urlToImageAsync(new UrlToImageRequest(url));
    }

    /**
     * Asynchronous variant of {@link #urlToImage(UrlToImageRequest)}.
     */
    public CompletableFuture<JobCreatedResponse> urlToImageAsync(UrlToImageRequest request) {
        return urlToImageAsync(request, null);
    }

    /** Async twin of {@code urlToImage(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> urlToImageAsync(UrlToImageRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/url-to-image", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    /**
     * Asynchronous variant of {@link #htmlToImage(String)}: captures raw HTML
     * markup as a PNG screenshot of the full scrollable page (the server defaults).
     */
    public CompletableFuture<JobCreatedResponse> htmlToImageAsync(String html) {
        return htmlToImageAsync(new HtmlToImageRequest(html));
    }

    /**
     * Asynchronous variant of {@link #htmlToImage(HtmlToImageRequest)}.
     */
    public CompletableFuture<JobCreatedResponse> htmlToImageAsync(HtmlToImageRequest request) {
        return htmlToImageAsync(request, null);
    }

    /** Async twin of {@code htmlToImage(request, idempotencyKey)}: a resend with the same key returns the same job. */
    public CompletableFuture<JobCreatedResponse> htmlToImageAsync(HtmlToImageRequest request, String idempotencyKey) {
        return executeWithRetryAsync(createPostRequest("/html-to-image", request, idempotencyKey), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobCreatedResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    public CompletableFuture<JobStatusResponse> getJobAsync(String jobId) {
        return executeWithRetryAsync(createGetRequest("/jobs/" + jobId), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    checkResponse(response);
                    try {
                        return objectMapper.readValue(response.body(), JobStatusResponse.class);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to parse response body", e);
                    }
                });
    }

    public CompletableFuture<JobStatusResponse> waitForJobAsync(String jobId) {
        return waitForJobAsync(jobId, Duration.ofSeconds(120), Duration.ofSeconds(2));
    }

    public CompletableFuture<JobStatusResponse> waitForJobAsync(String jobId, Duration timeout, Duration pollInterval) {
        CompletableFuture<JobStatusResponse> resultFuture = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeout.toMillis();
        waitForJobAsyncInternal(jobId, startTime, timeoutMs, pollInterval, resultFuture);
        return resultFuture;
    }

    private void waitForJobAsyncInternal(
            String jobId,
            long startTime,
            long timeoutMs,
            Duration pollInterval,
            CompletableFuture<JobStatusResponse> resultFuture) {

        if (System.currentTimeMillis() - startTime >= timeoutMs) {
            resultFuture.completeExceptionally(new PdfikException("Job " + jobId + " timed out", 408, "TIMEOUT", null));
            return;
        }

        getJobAsync(jobId).whenComplete((job, ex) -> {
            if (ex != null) {
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                // Same as the sync wait: a 429 while polling is waited out.
                if (cause instanceof PdfikException && ((PdfikException) cause).getStatusCode() == 429) {
                    scheduler.schedule(() -> waitForJobAsyncInternal(jobId, startTime, timeoutMs, pollInterval, resultFuture),
                            throttleDelayMs((PdfikException) cause, pollInterval.toMillis()), TimeUnit.MILLISECONDS);
                    return;
                }
                resultFuture.completeExceptionally(ex);
                return;
            }

            if (job.getStatus() == JobStatus.DONE || job.getStatus() == JobStatus.FAILED) {
                if (job.getStatus() == JobStatus.FAILED) {
                    resultFuture.completeExceptionally(new PdfikException(
                            "Job " + jobId + " failed",
                            400,
                            job.getErrorCode() != null ? job.getErrorCode() : "JOB_FAILED",
                            null
                    ));
                } else {
                    resultFuture.complete(job);
                }
                return;
            }

            scheduler.schedule(() -> {
                waitForJobAsyncInternal(jobId, startTime, timeoutMs, pollInterval, resultFuture);
            }, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    public CompletableFuture<JobFileResponse> getFileUrlAsync(String jobId) {
        JobFileResponse res = new JobFileResponse();
        res.setJobId(jobId);
        String cleanBaseUrl = this.baseUrl.replaceAll("/+$", "");
        res.setDownloadUrl(cleanBaseUrl + "/jobs/" + jobId + "/download");
        res.setExpiresInSeconds(-1);
        return CompletableFuture.completedFuture(res);
    }

    public CompletableFuture<byte[]> downloadPdfAsync(String jobId) {
        return executeWithRetryAsync(createGetRequest("/jobs/" + jobId + "/download"), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    checkResponse(response);
                    return response.body();
                });
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl = "https://api.pdfik.net";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public PdfikClient build() {
            return new PdfikClient(this);
        }
    }

    /**
     * Verifies the authenticity of an incoming PDFik webhook request by validating
     * the HMAC-SHA256 signature in the {@code X-PDFik-Signature} header.
     *
     * <p>PDFik signs every webhook delivery using the secret stored in your dashboard.
     * Always verify the signature <strong>before</strong> processing the event payload.</p>
     *
     * <b>Spring Boot Example</b>
     * <pre>{@code
     * import org.springframework.web.bind.annotation.*;
     * import org.springframework.http.ResponseEntity;
     * import jakarta.servlet.http.HttpServletRequest;
     * import net.pdfik.PdfikClient;
     * import java.util.*;
     * import java.nio.charset.StandardCharsets;
     *
     * @RestController
     * public class WebhookController {
     *
     *     private static final String WEBHOOK_SECRET = "whsec_..."; // From PDFik dashboard
     *
     *     @PostMapping("/webhook")
     *     public ResponseEntity<Map<String, Object>> handleWebhook(
     *             @RequestBody byte[] rawBody,
     *             @RequestHeader Map<String, String> headers) {
     *
     *         boolean valid = PdfikClient.verifyWebhookSignature(
     *                 new String(rawBody, StandardCharsets.UTF_8),
     *                 headers,
     *                 WEBHOOK_SECRET
     *         );
     *
     *         if (!valid) {
     *             return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
     *         }
     *
     *         // Parse and handle the event
     *         // ObjectMapper om = new ObjectMapper();
     *         // Map<String, Object> event = om.readValue(rawBody, Map.class);
     *         // String eventType = (String) event.get("event_type");
     *         // String jobId    = (String) event.get("job_id");
     *
     *         return ResponseEntity.ok(Map.of("received", true));
     *     }
     * }
     * }</pre>
     *
     * @param rawBody       the raw UTF-8 body string (must NOT be pre-parsed JSON)
     * @param headers       map of all HTTP request headers (case-insensitive lookup performed internally)
     * @param webhookSecret the secret token from your PDFik dashboard (starts with {@code whsec_})
     * @return {@code true} if the signature is valid; {@code false} otherwise
     */
    public static boolean verifyWebhookSignature(String rawBody, java.util.Map<String, String> headers, String webhookSecret) {
        if (rawBody == null || headers == null || webhookSecret == null) {
            return false;
        }

        String signatureHeader = null;
        String timestampHeader = null;
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            if ("x-pdfik-signature".equalsIgnoreCase(entry.getKey())) {
                signatureHeader = entry.getValue();
            } else if ("x-pdfik-timestamp".equalsIgnoreCase(entry.getKey())) {
                timestampHeader = entry.getValue();
            }
        }

        if (signatureHeader == null || timestampHeader == null) {
            return false;
        }

        try {
            String timestamp = null;
            String receivedSig = null;
            String[] parts = signatureHeader.split(",");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim();
                    String val = kv[1].trim();
                    if ("t".equals(key)) {
                        timestamp = val;
                    } else if ("v1".equals(key)) {
                        receivedSig = val;
                    }
                }
            }

            if (timestamp == null || receivedSig == null) {
                return false;
            }

            long now = System.currentTimeMillis() / 1000;
            long ts = Long.parseLong(timestamp);
            if (Math.abs(now - ts) > 300) {
                return false;
            }

            String signedPayload = timestamp + "." + rawBody;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(
                    webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
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
            String expectedSig = hexString.toString();

            return java.security.MessageDigest.isEqual(
                    expectedSig.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    receivedSig.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
