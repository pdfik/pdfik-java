# Changelog

All notable changes to `net.pdfik:pdfik-client` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [0.3.0] — 2026-09-19

The screenshots + Markdown + BYOB delivery release.

### Added
- `markdownToPdf(String markdown)`, `markdownToPdf(MarkdownToPdfRequest request[, String
  idempotencyKey])` and the asynchronous twins — call `POST /markdown-to-pdf`. CommonMark
  plus GFM tables and strikethrough; raw HTML inside the Markdown is escaped by the API,
  never rendered (use `htmlToPdf` for full HTML control), and a built-in print stylesheet
  is applied. `MarkdownToPdfRequest` carries the same `options`/`render`/`delivery`/
  `webhookUrl`/`test` surface as `HtmlToPdfRequest` (minus `einvoice`). Available on
  every plan, Free included.
- `urlToImage(String url)` / `urlToImage(UrlToImageRequest request[, String
  idempotencyKey])`, `htmlToImage(String html)` / `htmlToImage(HtmlToImageRequest
  request[, String idempotencyKey])` and the asynchronous twins — call
  `POST /url-to-image` / `POST /html-to-image` and produce PNG or JPEG screenshots
  through the same job flow. New `ImageOptions` (builder): `format` (`"png"` default |
  `"jpeg"`; the API also accepts `"jpg"` as an alias), `fullPage` (server default
  `false` — the visible area only; `true` follows the real page, clipped at 8,192 px),
  `quality` (1–100, jpeg only — the API answers `422` when sent with png), `viewport`
  (`ImageViewport`, width 320–1920, height 320–8192 CSS px, server default 1024×768).
  `UrlToImageRequest` supports the Pro+ `auth` option like `urlToPdf`. `downloadPdf`
  returns the image bytes unchanged (`Content-Type: image/png` / `image/jpeg`, filename
  `{job_id}.png` / `.jpg`); test mode returns the bundled sample PNG for either format.
  One screenshot consumes one render unit plus its bytes, same quotas as PDFs.
- `DeliveryOptions` (`url` — an https presigned PUT URL for your own bucket; `mode`
  defaults to `"presigned_put"`; Pro+ plans) as a new `delivery` field on
  `UrlToPdfRequest`, `HtmlToPdfRequest`, `EInvoiceToPdfRequest`, `MarkdownToPdfRequest`,
  `UrlToImageRequest` and `HtmlToImageRequest`. The rendered output is uploaded straight
  to your bucket and nothing is stored on PDFik's side: the `job.finished` webhook
  reports the outcome only — no `file_url` and no `expires_at`, because the presigned
  URL is a credential and is never recorded; `GET /jobs/{id}/download` answers
  `404 #output-delivered-externally`. Presign for at least 15 minutes and without a
  Content-Type condition. Not combinable with `test` (`400`); on Free/Starter the API
  answers `402 PLAN_UPGRADE_REQUIRED` with `feature: "delivery"`.
- Request-object overloads `urlToPdf(UrlToPdfRequest[, String idempotencyKey])` and
  `htmlToPdf(HtmlToPdfRequest[, String idempotencyKey])` (+ async twins) — the einvoice
  precedent style; the existing flat overloads delegate to them unchanged. This is the
  reachable path to the new `delivery` field on those requests.
- README: "Markdown to PDF", "Screenshots" and "Deliver to your own bucket (BYOB)"
  sections; the new methods in the API reference.
- Tests: WireMock coverage of the three new endpoints' posted JSON (including
  `full_page`/`viewport` snake_case mapping and absent-when-unset optionals) and the
  `delivery` object on the creation requests.

### Changed
- Version `0.2.0` → `0.3.0` (`pom.xml`, `build.gradle`, README coordinates).

### Fixed
- `waitForJob` / `waitForJobAsync` wait out an HTTP 429 instead of failing: they sleep the
  API's `retry_after_seconds` (capped at 60 s) and keep polling within the timeout. On
  Free (10 requests/min) a render longer than ~20 s used to end the wait with an error.
- The async request-object methods (`urlToPdfAsync`, `htmlToPdfAsync`, `markdownToPdfAsync`,
  `urlToImageAsync`, `htmlToImageAsync`, `einvoiceToPdfAsync`) gained an
  `(request, idempotencyKey)` overload, like their sync twins always had — there was no way
  to send an `Idempotency-Key` asynchronously.
- **Java 11 really works now.** The async retry path called
  `CompletableFuture.exceptionallyCompose`, which exists only from Java 12: the SDK did
  not compile on JDK 11, and a jar built on a newer JDK failed with `NoSuchMethodError`
  on the first async call under Java 11. The build now uses `--release 11`, so the
  compiler rejects any newer API.
- `getJob` / `waitForJob` (and their async twins) no longer fail with "Failed to parse
  response body": the API printed `created_at` / `finished_at` without a time zone,
  which the default `Instant` deserializer rejects. Zone-less timestamps are now read as
  UTC (the API itself now sends them with `Z`).
- Request-validation errors (HTTP 422) now carry a readable message such as
  `options.viewport.width: Input should be less than or equal to 1920`; the message used
  to be empty because `detail` is an array there.
- Javadoc of `DeliveryOptions` no longer claims that the `job.finished` webhook's
  `file_url` points into your bucket: for a delivered job the webhook carries neither
  `file_url` nor `expires_at`.
- README: the async example called `getJobId()` on `JobStatusResponse` (does not
  compile) and returned from `main` before the request finished — the SDK's threads are
  daemons, so the program exited having printed nothing. It now `join()`s.

## [0.2.0] — 2026-09-06

The e-invoicing (Factur-X) release. Everything below is what changed in the SDK
after 0.1.5 was published, according to git.


### Added
- `einvoiceToPdf(String xml)`, `einvoiceToPdf(String xml, boolean test)`,
  `einvoiceToPdf(String xml, String profile)`, `einvoiceToPdf(EInvoiceToPdfRequest request)`
  and `einvoiceToPdf(EInvoiceToPdfRequest request, String idempotencyKey)`, plus the
  asynchronous `einvoiceToPdfAsync(String)`, `(String, boolean)`, `(String, String)` and
  `(EInvoiceToPdfRequest)` returning `CompletableFuture<JobCreatedResponse>` — call
  `POST /einvoice-to-pdf`. Send UN/CEFACT Cross-Industry Invoice XML (the Factur-X
  payload, UTF-8, up to 1 MB) and get the usual `JobCreatedResponse` back (`202`,
  status `queued`); the finished job is a PDF/A-3 with the XML embedded as
  `factur-x.xml` (Factur-X / ZUGFeRD), fetched with `waitForJob` + `downloadPdf` as for
  any other job. Available on every plan, Free included.
- `EInvoiceToPdfRequest` model: `xml`, `profile` (`"minimum"`, `"basicwl"`, `"basic"`,
  `"en16931"`, `"extended"`), `templateId` (a template saved on the dashboard),
  `template` (`Map<String, Object>`, an inline block-template definition), `webhookUrl`,
  `test`; constructors `(xml)`, `(xml, profile)` and the full six-argument one. `profile`
  and `test` are omitted from the JSON when null (server defaults: `en16931`, live job).
  `templateId` and `template` are mutually exclusive; omit both to use the account's
  default template.
- `EInvoiceOptions` model (`xml`, `profile`, `format` preset to `"factur-x"`; constructors
  `(xml)`, `(xml, profile)`) and the overloads that carry it:
  `urlToPdf(url, options, render, webhookUrl, auth, einvoice, idempotencyKey, test)`,
  `htmlToPdf(html, options, render, webhookUrl, einvoice, idempotencyKey, test)`,
  `urlToPdfAsync(url, options, render, webhookUrl, auth, einvoice, test)` and
  `htmlToPdfAsync(html, options, render, webhookUrl, einvoice, test)` — wraps the
  caller's own page: the render becomes the visual half and the output is normalized to
  PDF/A-3 with the XML embedded. Not combinable with `PdfOptions.userPassword` or
  `PdfOptions.compression` (PDF/A forbids encryption, and re-saving breaks PDF/A attributes).
  `UrlToPdfRequest` / `HtmlToPdfRequest` gained an `einvoice` field and a matching
  constructor; the existing overloads delegate to the new ones unchanged.
- New error responses, all thrown as `PdfikException` (`getStatusCode()`) and never
  retried: `422 EINVOICE_XML_INVALID` (malformed or unsafe XML, a guideline URN that does not match the declared profile, an XSD failure, or XML over 1 MB),
  `422 EINVOICE_OPTIONS_CONFLICT` (`templateId` together with `template`, or `einvoice`
  with `userPassword` / `compression`), `404 EINVOICE_TEMPLATE_NOT_FOUND` (unknown `templateId`).
- `PaperFormat` now lists all 11 formats the renderer accepts: `A0`–`A6`, `LETTER`,
  `LEGAL`, `TABLOID`, `LEDGER` (new constants: `A0`, `A1`, `A2`, `A5`, `A6`, `LEDGER`).
- README: "Factur-X e-invoices" section covering both flows.
- WireMock tests: `einvoiceToPdf` with the profile omitted, the full request with
  `Idempotency-Key`, no retry on `422`, the async variant, and the `einvoice` option on
  `urlToPdf` and `htmlToPdf`.

### Fixed
- `PdfikException.getErrorCode()` was always `null` for API errors: pdf-api's RFC 7807
  bodies carry the code in `error` (`QUOTA_EXCEEDED`, `PLAN_UPGRADE_REQUIRED`,
  `EINVOICE_XML_INVALID`, …) while the client read `error_code`. It now reads `error`
  first and falls back to `error_code`.

### Changed
- Request validation on the API side (no client code change, but new exceptions to
  expect): an unknown `PdfOptions.format`, a margin that is not exactly one CSS length
  per side (shorthands such as `"10mm 5mm"`), or an `auth.type` other than `basic` /
  `bearer` (and a `basic` value without `username:password`) are rejected with
  `422 VALIDATION_ERROR` before a job is created or any quota is charged. The SDK does
  not retry `4xx`.
- README intro now describes PDFik as the asynchronous URL/HTML-to-PDF API.
- Version `0.1.5` → `0.2.0` (`pom.xml`, README Maven/Gradle snippets, and `build.gradle`,
  which had been left at `0.1.2`).

## [0.1.5] — 2026-08-07

Published to Maven Central 2026-08-07: test-mode overloads `urlToPdf(url, test)` /
`htmlToPdf(html, test)` and their async twins; publishing notes moved out of the public
README. Earlier changes live in git history.
