# pdfik-client

> **Where this code lives:** extracted from the PDFik platform monorepo (last sync 2026-08-17).
> Releases to Maven Central are cut from the monorepo; issues and PRs are welcome here.

Official Java SDK for [PDFik](https://pdfik.net) — the asynchronous URL/HTML-to-PDF API.

Submit a public URL or raw HTML, get a job id back, and receive an HMAC-signed webhook (or poll) when the PDF is ready. Rendering runs on sandboxed headless Chromium, so modern CSS, web fonts and JavaScript-heavy pages come out the way they look in the browser.

## Features

- **Modern HTTP**: Built on `java.net.http.HttpClient` (Java 11+ built-in client) with zero extra HTTP library dependencies.
- **Fluent Builder**: Native Builder pattern for client and options creation.
- **Sync & Async**: Exposes both block-oriented synchronous methods and non-blocking asynchronous variants (via `CompletableFuture`).
- **Auto Retry**: Automatic exponential backoff for `5xx` and `429` (Rate Limit) errors.
- **Jackson Integrated**: Uses standard `jackson-databind` for serialization/deserialization.

## Installation

### Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>net.pdfik</groupId>
  <artifactId>pdfik-client</artifactId>
  <version>0.1.5</version>
</dependency>
```

### Gradle

Add the following to your `build.gradle`:

```groovy
implementation 'net.pdfik:pdfik-client:0.1.5'
```

## Quick Start

### Convert public URL to PDF (Sync)

```java
import net.pdfik.PdfikClient;
import net.pdfik.models.*;
import java.io.FileOutputStream;
import java.time.Duration;

public class Main {
    public static void main(String[] args) {
        // Initialize client using Builder
        try (PdfikClient client = PdfikClient.builder()
                .apiKey("sk_live_...") // Retrieve from your dashboard
                .build()) {

            // Configure PDF styling and margins
            PdfOptions options = PdfOptions.builder()
                    .format(PaperFormat.A4)
                    .landscape(false)
                    .printBackground(true)
                    .margin(new MarginOptions("10mm", "10mm", "10mm", "10mm"))
                    .build();

            // 1. Submit the URL to be rendered
            JobCreatedResponse job = client.urlToPdf("https://example.com", options);
            System.out.println("Job created: " + job.getJobId() + ". Processing...");

            // 2. Poll until the job completes
            JobStatusResponse result = client.waitForJob(
                    job.getJobId(),
                    Duration.ofSeconds(120), // timeout
                    Duration.ofSeconds(2)    // poll interval
            );
            System.out.println("Job finished! Pages: " + result.getPagesCount());

            // 3. Download the PDF bytes
            byte[] pdfBytes = client.downloadPdf(job.getJobId());

            try (FileOutputStream fos = new FileOutputStream("output.pdf")) {
                fos.write(pdfBytes);
            }
            System.out.println("PDF saved to output.pdf");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Convert HTML to PDF (Async)

```java
import net.pdfik.PdfikClient;
import net.pdfik.models.*;
import java.time.Duration;

public class Main {
    public static void main(String[] args) {
        PdfikClient client = PdfikClient.builder()
                .apiKey("sk_live_...")
                .build();

        client.htmlToPdfAsync("<h1>Hello World</h1><p>Sent from Java Async SDK</p>")
                .thenCompose(job -> {
                    System.out.println("Job created async: " + job.getJobId());
                    return client.waitForJobAsync(job.getJobId(), Duration.ofSeconds(120), Duration.ofSeconds(2));
                })
                .thenCompose(status -> {
                    System.out.println("Job complete. Downloading...");
                    return client.downloadPdfAsync(status.getJobId());
                })
                .thenAccept(pdfBytes -> {
                    System.out.println("Downloaded " + pdfBytes.length + " bytes.");
                    client.close();
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    client.close();
                    return null;
                });
    }
}
```

### Get the direct download URL

Get the direct API download URL for the generated PDF (requires the "X-API-Key" header to download):

```java
JobFileResponse fileInfo = client.getFileUrl(job.getJobId());
System.out.println("Direct Download URL: " + fileInfo.getDownloadUrl());
```

### Test mode

Pass `test = true` (dedicated `urlToPdf(url, test)` / `htmlToPdf(html, test)` overloads, also available on the full-parameter variants) to run a job through the full pipeline (statuses, webhook, download) without real rendering — the download returns a small sample PDF. Test jobs are free (no PDF or byte quota is debited) and rate-limited instead: 60 test calls per minute and 2,000 per day per account. Job status and webhook payloads always carry `test: true|false`, so you can safely exercise your integration end to end:

```java
JobCreatedResponse job = client.urlToPdf("https://example.com", true);

JobStatusResponse result = client.waitForJob(job.getJobId());
System.out.println(result.getTest());      // true
System.out.println(result.getExpiresAt()); // e.g. 2026-08-05T12:00:00Z

byte[] pdfBytes = client.downloadPdf(job.getJobId()); // sample PDF
```

## Limits, retention and error codes

- **Generated volume per month** (byte quota, counts rendered output only — downloads are free): Free 0.5 GB, Starter 10 GB, Pro 50 GB, Business 300 GB. Business plans can purchase additional +1 GB blocks from the dashboard. Exceeding the quota returns `429` ([quota-bytes-exceeded](https://docs.pdfik.net/error-codes#quota-bytes-exceeded)).
- **File size**: up to 150 MB per PDF on every plan — contact support if you need more. A larger render fails the job with `FILE_TOO_LARGE` ([file-too-large](https://docs.pdfik.net/error-codes#file-too-large)).
- **Retention**: each file is available for download for 24 hours after the job finishes; the exact deadline is `JobStatusResponse.getExpiresAt()` (`expires_at` in the webhook payload). After that the download returns `410` ([file-expired](https://docs.pdfik.net/error-codes#file-expired)).
- **Downloads**: at most 3 download attempts per file (counted when the stream starts); after that the API returns `429` ([download-attempts-exhausted](https://docs.pdfik.net/error-codes#download-attempts-exhausted)) — re-render the file or contact support. Only 1 download stream may be active at a time; a concurrent request returns `429` ([download-busy](https://docs.pdfik.net/error-codes#download-busy)) — wait ~10 seconds and retry, it does not burn an attempt.

### Handling Webhooks

If you specify `webhookUrl` in the options of `urlToPdf` or `htmlToPdf`, PDFik will send an HTTP `POST` request to your server when the rendering job is finished.

You should verify the authenticity of the webhook by validating the signature. We provide a static helper `verifyWebhookSignature` in `PdfikClient` for this purpose.

During a secret rotation both the new and the previous secret verify for the overlap
window you pick in the dashboard (1 hour to 5 days, or none at all), so check the new
one first and fall back to the old one while you roll your receivers out.

Custom delivery headers (e.g. a Cloudflare Access service token) are configured once on
the dashboard Webhooks page and attached to every delivery — nothing to pass per request.

#### Example (Spring Boot):

```java
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import net.pdfik.PdfikClient;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@RestController
public class WebhookController {

    private static final String WEBHOOK_SECRET = "whsec_..."; // From PDFik dashboard

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader Map<String, String> headers) {

        boolean valid = PdfikClient.verifyWebhookSignature(
                new String(rawBody, StandardCharsets.UTF_8),
                headers,
                WEBHOOK_SECRET
        );

        if (!valid) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid signature"));
        }

        // Signature is valid. Process the event payload...
        // System.out.println("Webhook validated successfully!");

        return ResponseEntity.ok(Map.of("received", true));
    }
}
```

## License

MIT License.

