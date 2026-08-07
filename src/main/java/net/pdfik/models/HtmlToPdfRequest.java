package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

public class HtmlToPdfRequest {
    private String html;
    private String webhookUrl;
    private PdfOptions options;
    private RenderOptions render;
    /** Test mode flag; omitted from the request body when null (defaults to a live job). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean test;

    public HtmlToPdfRequest() {}

    public HtmlToPdfRequest(String html, String webhookUrl, PdfOptions options, RenderOptions render) {
        this.html = html;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
    }

    public HtmlToPdfRequest(String html, String webhookUrl, PdfOptions options, RenderOptions render, Boolean test) {
        this.html = html;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.test = test;
    }

    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public PdfOptions getOptions() { return options; }
    public void setOptions(PdfOptions options) { this.options = options; }

    public RenderOptions getRender() { return render; }
    public void setRender(RenderOptions render) { this.render = render; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
