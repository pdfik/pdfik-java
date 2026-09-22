package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /html-to-image}: captures raw HTML markup as
 * a PNG (default) or JPEG screenshot. Job polling is identical to the PDF
 * endpoints; the download returns the raw {@code image/png} or
 * {@code image/jpeg} bytes (filename {@code {jobId}.png}/{@code .jpg}).
 *
 * <p>{@code delivery} (Pro+) uploads the output straight to your own bucket
 * via a presigned PUT URL; {@code test} runs the full pipeline without real
 * rendering. The two cannot be combined.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HtmlToImageRequest {
    private String html;
    private String webhookUrl;
    private ImageOptions options;
    private RenderOptions render;
    private DeliveryOptions delivery;
    private Boolean test;

    public HtmlToImageRequest() {}

    public HtmlToImageRequest(String html) {
        this.html = html;
    }

    public HtmlToImageRequest(String html, String webhookUrl, ImageOptions options, RenderOptions render, DeliveryOptions delivery, Boolean test) {
        this.html = html;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.delivery = delivery;
        this.test = test;
    }

    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public ImageOptions getOptions() { return options; }
    public void setOptions(ImageOptions options) { this.options = options; }

    public RenderOptions getRender() { return render; }
    public void setRender(RenderOptions render) { this.render = render; }

    public DeliveryOptions getDelivery() { return delivery; }
    public void setDelivery(DeliveryOptions delivery) { this.delivery = delivery; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
