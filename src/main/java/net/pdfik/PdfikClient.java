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
                .exceptionallyCompose(ex -> {
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
                    return CompletableFuture.failedFuture(new PdfikException("Connection failed: " + actualEx.getMessage(), 503, null, null));
                });
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
                if (node.has("detail")) {
                    message = node.get("detail").asText();
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
        HttpResponse<String> response = executeWithRetry(createPostRequest("/url-to-pdf", body, idempotencyKey), HttpResponse.BodyHandlers.ofString());
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
        HttpResponse<String> response = executeWithRetry(createPostRequest("/html-to-pdf", body, idempotencyKey), HttpResponse.BodyHandlers.ofString());
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
            JobStatusResponse job = getJob(jobId);
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
        return executeWithRetryAsync(createPostRequest("/url-to-pdf", body), HttpResponse.BodyHandlers.ofString())
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
        return executeWithRetryAsync(createPostRequest("/html-to-pdf", body), HttpResponse.BodyHandlers.ofString())
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
        return executeWithRetryAsync(createPostRequest("/einvoice-to-pdf", request), HttpResponse.BodyHandlers.ofString())
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
