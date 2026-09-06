# Changelog

All notable changes to `net.pdfik:pdfik-client` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

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
