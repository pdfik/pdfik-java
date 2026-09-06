package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Factur-X e-invoicing option for {@code urlToPdf} / {@code htmlToPdf}: the rendered
 * page becomes the human-readable half of a Factur-X / ZUGFeRD hybrid — the output is
 * normalized to PDF/A-3 and the XML is embedded as {@code factur-x.xml}.
 *
 * <p>Mutually exclusive with {@code PdfOptions.userPassword} (PDF/A forbids encryption)
 * and {@code PdfOptions.compression} (re-saving breaks the PDF/A attributes). Available
 * on every plan, Free included — the output is the same clean PDF/A-3.</p>
 *
 * <p>The XML (UN/CEFACT Cross-Industry-Invoice, UTF-8, up to 1 MB) is validated against
 * the official XSD of the declared profile; its GuidelineSpecifiedDocumentContextParameter
 * must match the profile. Schema-valid does not mean tax-compliant — the invoice content
 * is the caller's responsibility. Profiles {@code minimum} and {@code basicwl} are
 * accompanying data only and are NOT a legally sufficient e-invoice.</p>
 */
public class EInvoiceOptions {
    /** E-invoice container format; only "factur-x" is supported in v1. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String format = "factur-x";
    /**
     * Factur-X conformance profile the XML declares: "minimum", "basicwl", "basic",
     * "en16931" or "extended". Omitted from the request body when null (the server
     * defaults to "en16931").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String profile;
    private String xml;

    public EInvoiceOptions() {}

    public EInvoiceOptions(String xml) {
        this.xml = xml;
    }

    public EInvoiceOptions(String xml, String profile) {
        this.xml = xml;
        this.profile = profile;
    }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public String getXml() { return xml; }
    public void setXml(String xml) { this.xml = xml; }
}
