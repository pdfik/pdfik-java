package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /url-to-image}: captures a public URL as a
 * PNG (default) or JPEG screenshot. Job polling is identical to the PDF
 * endpoints; the download returns the raw {@code image/png} or
 * {@code image/jpeg} bytes (filename {@code {jobId}.png}/{@code .jpg}).
 *
 * <p>{@code auth} (Pro+) attaches basic/bearer credentials exactly as on
 * {@code urlToPdf}. {@code delivery} (Pro+) uploads the output straight to
 * your own bucket via a presigned PUT URL; {@code test} runs the full
 * pipeline without real rendering. The two cannot be combined.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UrlToImageRequest {
    private String url;
    private String webhookUrl;
    private ImageOptions options;
    private RenderOptions render;
    private JobAuthOptions auth;
    private DeliveryOptions delivery;
    private Boolean test;

    public UrlToImageRequest() {}

    public UrlToImageRequest(String url) {
        this.url = url;
    }

    public UrlToImageRequest(String url, String webhookUrl, ImageOptions options, RenderOptions render, JobAuthOptions auth, DeliveryOptions delivery, Boolean test) {
        this.url = url;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.auth = auth;
        this.delivery = delivery;
        this.test = test;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public ImageOptions getOptions() { return options; }
    public void setOptions(ImageOptions options) { this.options = options; }

    public RenderOptions getRender() { return render; }
    public void setRender(RenderOptions render) { this.render = render; }

    public JobAuthOptions getAuth() { return auth; }
    public void setAuth(JobAuthOptions auth) { this.auth = auth; }

    public DeliveryOptions getDelivery() { return delivery; }
    public void setDelivery(DeliveryOptions delivery) { this.delivery = delivery; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
