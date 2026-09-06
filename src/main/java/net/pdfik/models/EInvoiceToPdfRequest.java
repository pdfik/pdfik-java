package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Request body for {@code POST /einvoice-to-pdf}: PDFik builds the human-readable
 * invoice from your UN/CEFACT Cross-Industry-Invoice XML using a block template,
 * renders it to PDF/A-3 and embeds the XML as {@code factur-x.xml}
 * (Factur-X / ZUGFeRD hybrid e-invoice).
 *
 * <p>{@code templateId} (a saved dashboard template) and {@code template} (an inline
 * block-template definition) are mutually exclusive; omit both to use the account
 * default template (or the built-in one). Available on every plan; on the Free plan
 * the output carries a PDFik.net watermark.</p>
 *
 * <p>The XML (UTF-8, up to 1 MB) is validated against the official XSD of the declared
 * profile before any quota is spent; its GuidelineSpecifiedDocumentContextParameter
 * must match the profile. TypeCode 380 (invoice) and 381 (credit note) are supported.
 * Schema-valid does not mean tax-compliant — the invoice content is the caller's
 * responsibility. Profiles {@code minimum} and {@code basicwl} are accompanying data
 * only and are NOT a legally sufficient e-invoice.</p>
 */
public class EInvoiceToPdfRequest {
    private String xml;
    /**
     * Factur-X conformance profile the XML declares: "minimum", "basicwl", "basic",
     * "en16931" or "extended". Omitted from the request body when null (the server
     * defaults to "en16931").
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String profile;
    private String templateId;
    private Map<String, Object> template;
    private String webhookUrl;
    /** Test mode flag; omitted from the request body when null (defaults to a live job). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean test;

    public EInvoiceToPdfRequest() {}

    public EInvoiceToPdfRequest(String xml) {
        this.xml = xml;
    }

    public EInvoiceToPdfRequest(String xml, String profile) {
        this.xml = xml;
        this.profile = profile;
    }

    public EInvoiceToPdfRequest(String xml, String profile, String templateId, Map<String, Object> template, String webhookUrl, Boolean test) {
        this.xml = xml;
        this.profile = profile;
        this.templateId = templateId;
        this.template = template;
        this.webhookUrl = webhookUrl;
        this.test = test;
    }

    public String getXml() { return xml; }
    public void setXml(String xml) { this.xml = xml; }

    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }

    public Map<String, Object> getTemplate() { return template; }
    public void setTemplate(Map<String, Object> template) { this.template = template; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
