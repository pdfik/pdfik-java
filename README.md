# pdfik-client

> **Where this code lives:** extracted from the PDFik platform monorepo (last sync 2026-09-22).
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
  <version>0.3.0</version>
</dependency>
```

### Gradle

Add the following to your `build.gradle`:

```groovy
implementation 'net.pdfik:pdfik-client:0.3.0'
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
                    return client.waitForJobAsync(job.getJobId(), Duration.ofSeconds(120), Duration.ofSeconds(2))
                            .thenCompose(status -> {
                                System.out.println("Job complete. Downloading...");
                                return client.downloadPdfAsync(job.getJobId());
                            });
                })
                .thenAccept(pdfBytes -> {
                    System.out.println("Downloaded " + pdfBytes.length + " bytes.");
                    client.close();
                })
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    client.close();
                    return null;
                })
                .join(); // wait: the SDK's threads are daemons, so main() must not return first
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

### Markdown to PDF

`markdownToPdf` converts Markdown (CommonMark + GFM tables and strikethrough) with a built-in print stylesheet. Raw HTML inside the Markdown is escaped, not rendered — use `htmlToPdf` for full HTML control. The same `PdfOptions` (paper format, margins, header/footer, watermark, ...) and job flow apply. The Markdown may be up to 100,000 characters (longer input is rejected with `422`); Markdown whose converted document is too large for the processing queue is rejected with `413` ([payload-too-large-for-queue](https://docs.pdfik.net/error-codes#payload-too-large-for-queue)) and is not charged. Example:

```java
// Simplest form
JobCreatedResponse job = client.markdownToPdf("# Report\n\n| Item | Price |\n| --- | --- |\n| Render | $0.01 |");

// Full control via the request object (+ optional Idempotency-Key)
MarkdownToPdfRequest request = new MarkdownToPdfRequest("# Report\n\nHello **world**");
request.setOptions(PdfOptions.builder().format(PaperFormat.A4).build());
job = client.markdownToPdf(request, "report-2026-001");

JobStatusResponse result = client.waitForJob(job.getJobId());
byte[] pdfBytes = client.downloadPdf(job.getJobId());
```

### Screenshots

`urlToImage` / `htmlToImage` capture a page as a PNG (default) or JPEG instead of a PDF. `ImageOptions`: `format` (`"png"` | `"jpeg"`), `fullPage` (default `false` - the visible area only; with `true` the height follows the real page and is clipped at 8,192 px, which is a ceiling and not a target, so a 2,000 px page still gives a 2,000 px image, and horizontal overflow beyond the viewport width is never captured), `quality` (1-100, JPEG only) and `viewport` (`ImageViewport(width, height)`; you pick the window size and we capture exactly that - nothing is scaled or fitted - `width` 320-1920, `height` 320-8192, defaults to 1024x768). Polling is identical to the PDF endpoints, and `downloadPdf` returns the raw image bytes (`image/png` or `image/jpeg`, filename `{jobId}.png`/`.jpg`):

```java
UrlToImageRequest request = new UrlToImageRequest("https://example.com");
request.setOptions(ImageOptions.builder()
        .format("jpeg")
        .quality(80)
        .fullPage(true)
        .viewport(new ImageViewport(1280, 720))
        .build());
JobCreatedResponse job = client.urlToImage(request);

client.waitForJob(job.getJobId());
byte[] imageBytes = client.downloadPdf(job.getJobId()); // JPEG bytes

JobCreatedResponse shot = client.htmlToImage("<h1>Hello</h1>"); // 1024x768 PNG
```

`UrlToImageRequest` also accepts the Pro+ `auth` option (basic/bearer), exactly as `urlToPdf`. Passing `quality` together with PNG is rejected with `422`. Async variants: `markdownToPdfAsync`, `urlToImageAsync`, `htmlToImageAsync`.

### Deliver to your own bucket (BYOB)

Set `delivery` (Pro+, on every request object — `UrlToPdfRequest`, `HtmlToPdfRequest`, `MarkdownToPdfRequest`, `UrlToImageRequest`, `HtmlToImageRequest`, `EInvoiceToPdfRequest`) and the output is uploaded straight to your own bucket via a presigned PUT URL; nothing is stored on PDFik's side. The URL must be `https` on the standard port 443 (any other port is rejected with `422`); presign it for at least 15 minutes and without a Content-Type condition:

```java
UrlToPdfRequest request = new UrlToPdfRequest();
request.setUrl("https://example.com");
request.setDelivery(new DeliveryOptions(presignedPutUrl)); // mode "presigned_put" is the only mode
JobCreatedResponse job = client.urlToPdf(request);

client.waitForJob(job.getJobId()); // "done" means the PUT to your bucket succeeded
```

Once a delivered job is done, the `job.finished` webhook reports the outcome only: it carries neither `file_url` nor `expires_at`, because PDFik keeps no copy and never records where the file went (the presigned URL is a credential, so it is used once and forgotten). You already know the destination — you signed it. `downloadPdf` answers `404` ([output-delivered-externally](https://docs.pdfik.net/error-codes#output-delivered-externally)) — the file only exists in your bucket. `delivery` cannot be combined with test mode (`400`).

### Factur-X e-invoices

PDFik can produce hybrid e-invoices (Factur-X / ZUGFeRD): a PDF/A-3 file with your UN/CEFACT Cross-Industry-Invoice XML embedded as `factur-x.xml`. Two ways to get one:

**1. From the XML alone (`einvoiceToPdf`)** — PDFik builds the human-readable invoice from the XML with a block template (the account default, a saved dashboard template via `templateId`, or an inline `template`), renders it to PDF/A-3 and embeds the XML. The normal job flow applies afterwards (`waitForJob` + `downloadPdf`):

```java
// Simplest form: default template, profile defaults to en16931
JobCreatedResponse job = client.einvoiceToPdf(ciiXml);

// Full control via the request class (+ optional Idempotency-Key)
EInvoiceToPdfRequest request = new EInvoiceToPdfRequest(ciiXml);
request.setProfile("en16931");    // must match the XML's guideline ID: minimum | basicwl | basic | en16931 | extended
request.setTemplateId("tpl_..."); // saved dashboard template; mutually exclusive with setTemplate(...)
job = client.einvoiceToPdf(request, "invoice-2026-001");

JobStatusResponse result = client.waitForJob(job.getJobId());
byte[] pdfA3 = client.downloadPdf(job.getJobId());
```

**2. Attached to your own rendering (`einvoice` option on `urlToPdf` / `htmlToPdf`)** — the page you render becomes the visual half of the hybrid; the output is normalized to PDF/A-3 with the XML embedded:

```java
EInvoiceOptions einvoice = new EInvoiceOptions(ciiXml, "en16931");
JobCreatedResponse job = client.htmlToPdf(invoiceHtml, null, null, null, einvoice, null, null);
```

Notes:

- The XML (UTF-8, up to 1 MB) is validated against the official XSD of the declared profile before any quota is spent; its `GuidelineSpecifiedDocumentContextParameter` must match the profile. TypeCode 380 (invoice) and 381 (credit note) are supported. Invalid XML returns `422` ([einvoice-xml-invalid](https://docs.pdfik.net/error-codes#einvoice-xml-invalid)).
- The output is validated with veraPDF and Mustangproject. Schema-valid does not mean tax-compliant — the invoice content remains your responsibility.
- Profiles `minimum` and `basicwl` carry accompanying data only and are NOT a legally sufficient e-invoice; use `basic`, `en16931` or `extended` for a full invoice.
- The `einvoice` option is mutually exclusive with `userPassword` (PDF/A forbids encryption) and `compression` (re-saving breaks the PDF/A attributes).
- Available on every plan, Free included — the output is the same clean PDF/A-3.

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

