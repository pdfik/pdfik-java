# Changelog

All notable changes to `net.pdfik:pdfik-client` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [0.4.0] — 2026-10-20

The synchronous one-call release (`"wait": true`).

### Added
- `urlToPdfFile`, `htmlToPdfFile`, `markdownToPdfFile`, `urlToImageFile`, `htmlToImageFile`
  and `einvoiceToPdfFile` — each as `(String …)`, `(request)` and
  `(request, idempotencyKey)` — plus the asynchronous twins returning
  `CompletableFuture<byte[]>`. They send the creation request with `"wait": true`: the
  API holds it until the render finishes (up to 45 s) and streams the file itself back
  (`application/pdf`, `image/png` or `image/jpeg`), so there is no job to poll and
  nothing to download. The job's id rides the `X-PDFik-Job-Id` response header, the
  e-invoice `minimum`/`basicwl` profile note rides `X-PDFik-Warning`, and the file also
  stays downloadable for the normal 24 h. `wait` is injected at serialization time and
  is deliberately not a field on the request models — set there, it would reach the
  job-creating methods too, which parse the answer as `JobCreatedResponse` and would
  crash confusingly on file bytes.
- The `*File` requests run with their own per-request timeout: at least 70 s, or the
  configured `requestTimeout` when that is larger. The server answers by 45 s and the
  ingress cuts at 60 s, so the default `requestTimeout` (30 s) would cut a healthy wait.
- A render that does not finish in time answers `503` `SYNC_TIMEOUT`, thrown at once and
  **never resent**: the job was cancelled, NOT charged, and its `Idempotency-Key`
  released, so retrying is safe — but it is your call, because every automatic retry
  would be a full re-render. For the same reason the `*File` methods do not resend on
  any `5xx` or network failure either (the one attempt may already have rendered — and
  charged — the job); only the rate-limit and concurrency `429`s are waited out, as on
  every other call, under the one `Idempotency-Key` the call keeps.
- A failed render answers `422` `#job-failed`, thrown as a `PdfikException` whose
  `getErrorCode()` is the job's own error code (`PAGE_TIMEOUT`, `SSRF_BLOCKED`, …) and
  whose response body names the `job_id`.
- A server from before synchronous mode ignores `wait` silently and answers `202` with
  the usual JSON. The `*File` methods detect that (a `202`, or JSON on any `2xx`) and
  throw a `PdfikException` (`SYNC_MODE_UNSUPPORTED`) whose message names the job that was
  created and how to fetch it — the JSON is never returned as file bytes.
- `delivery` (BYOB) cannot be combined with `wait` — the file comes back on the response
  instead of going to a bucket — so a request object with it set is rejected with
  `IllegalArgumentException` before any HTTP.

### Changed
- README: the quick start opens with the one-call form; the asynchronous flow stays
  below for batches, webhook pipelines and renders longer than 45 s; the Auto Retry
  bullet carries the `*File` carve-out.
- Version `0.3.2` → `0.4.0` (`pom.xml`, `build.gradle`, README coordinates).

## [0.3.2] — 2026-10-12

### Fixed
- A retried create call can no longer produce a second job. Every create call —
  `urlToPdf`, `htmlToPdf`, `markdownToPdf`, `urlToImage`, `htmlToImage`, `einvoiceToPdf`
  and their async twins — now carries an `Idempotency-Key` header automatically: the key
  you pass, or else a random UUID generated once per call and sent unchanged every time
  that call is sent again (after a `5xx`, a network failure or a throttle wait). The API
  answers a repeated key with the original job instead of creating a new one. Before, a
  call made without a key was simply sent again, so when the first request had created
  the job and only its answer was lost, you got two jobs and were charged twice. Status
  and download requests do not carry the header.
- A rate-limit `429` on a create call or on a download is now waited out the way it
  already was while polling. Every request the client makes — `urlToPdf`, `htmlToPdf`,
  `markdownToPdf`, `urlToImage`, `htmlToImage`, `einvoiceToPdf`, `getJob`, `downloadPdf`
  and their async twins — sleeps for the time the API states (`retry_after_seconds`, else
  the `Retry-After` header, else 10 s; capped at 60 s) and sends the request again, up to
  5 waits per call; after that the `429` is thrown as before. The in-flight job cap
  (`CONCURRENCY_LIMIT_EXCEEDED`) is waited out the same way. The client used to retry
  three times within about three seconds and then throw, so on Free (10 requests/min) a
  program that created a job, polled and then downloaded could fail at the download, or
  at the create call, whenever the per-minute window was full.
- A busy download is waited out. While another download of the same file is still
  streaming, the API answers `429` `download-busy`; `downloadPdf` / `downloadPdfAsync` now
  sleep for the `Retry-After` it states (10 s) and ask again, within the same 5 waits per
  call. It clears by itself within seconds and costs no download attempt.
- Quota `429`s are no longer retried. The monthly byte quota, the test-mode caps, an
  exhausted download counter (`download-attempts-exhausted`) and any `429` the client does
  not recognise are thrown at once, without a sleep: waiting cannot help there, and the
  client used to spend three attempts on them.
- `waitForJob` / `waitForJobAsync`: one `429` while polling now costs one wait. The
  request used to be retried twice (after 1 s and 2 s) before the polling loop slept the
  stated time, so every throttled poll spent three requests of the very limit it was
  waiting for.
- A failed `downloadPdf` / `downloadPdfAsync` now throws a `PdfikException` with the API's
  message, `getErrorCode()` and the real response body. The error body of a download was
  read as bytes and never decoded, so the message was always
  `API request failed with status …` whatever the API had answered.

### Changed
- README: the retry line in "Features" and the downloads line in "Limits, retention and
  error codes" say the above.
- Version `0.3.1` → `0.3.2` (`pom.xml`, `build.gradle`, README coordinates).

## [0.3.1] — 2026-10-10

A documentation-only release: no API or behaviour change in the client. Everything below
is what changed in the SDK after 0.3.0 was published, according to git.

### Changed
- BYOB `delivery` is available on every plan since 2026-10-09 (a change on the API side,
  no client code change; it used to be Pro+). Free and Starter no longer get
  `402 PLAN_UPGRADE_REQUIRED` with `feature: "delivery"`, which the 0.3.0 notes below
  still describe as it was then.
- README, "Deliver to your own bucket (BYOB)": `delivery` is marked "every plan" instead
  of "Pro+".
- Javadoc: `DeliveryOptions` and the `delivery` field of `UrlToPdfRequest`,
  `HtmlToPdfRequest` and `EInvoiceToPdfRequest` say "every plan" instead of "Pro+"; the
  class comments of `MarkdownToPdfRequest`, `UrlToImageRequest` and `HtmlToImageRequest`
  no longer mark `delivery` as Pro+. `auth` is still Pro+.
- Version `0.3.0` → `0.3.1` (`pom.xml`, `build.gradle`, README coordinates).

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
