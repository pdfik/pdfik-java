package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

public class UrlToPdfRequest {
    private String url;
    private String webhookUrl;
    private PdfOptions options;
    private RenderOptions render;
    private JobAuthOptions auth;
    private EInvoiceOptions einvoice;
    /**
     * BYOB delivery (Pro+): upload the output straight to your own bucket via
     * a presigned PUT URL. Omitted from the request body when null. Not
     * combinable with test mode.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeliveryOptions delivery;
    /** Test mode flag; omitted from the request body when null (defaults to a live job). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean test;

    public UrlToPdfRequest() {}

    public UrlToPdfRequest(String url, String webhookUrl, PdfOptions options, RenderOptions render) {
        this.url = url;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
    }

    public UrlToPdfRequest(String url, String webhookUrl, PdfOptions options, RenderOptions render, JobAuthOptions auth) {
        this.url = url;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.auth = auth;
    }

    public UrlToPdfRequest(String url, String webhookUrl, PdfOptions options, RenderOptions render, JobAuthOptions auth, Boolean test) {
        this.url = url;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.auth = auth;
        this.test = test;
    }

    public UrlToPdfRequest(String url, String webhookUrl, PdfOptions options, RenderOptions render, JobAuthOptions auth, EInvoiceOptions einvoice, Boolean test) {
        this.url = url;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.auth = auth;
        this.einvoice = einvoice;
        this.test = test;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public PdfOptions getOptions() { return options; }
    public void setOptions(PdfOptions options) { this.options = options; }

    public RenderOptions getRender() { return render; }
    public void setRender(RenderOptions render) { this.render = render; }

    public JobAuthOptions getAuth() { return auth; }
    public void setAuth(JobAuthOptions auth) { this.auth = auth; }

    public EInvoiceOptions getEinvoice() { return einvoice; }
    public void setEinvoice(EInvoiceOptions einvoice) { this.einvoice = einvoice; }

    public DeliveryOptions getDelivery() { return delivery; }
    public void setDelivery(DeliveryOptions delivery) { this.delivery = delivery; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
