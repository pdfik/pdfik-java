package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for {@code POST /markdown-to-pdf}: converts Markdown
 * (CommonMark + GFM tables and strikethrough) to a PDF with a built-in print
 * stylesheet. Raw HTML inside the Markdown is escaped, not rendered — use
 * {@code htmlToPdf} for full HTML control.
 *
 * <p>{@code options} (paper format, margins, header/footer, watermark, ...)
 * works exactly as on {@code htmlToPdf}; job polling and download are
 * identical to the other PDF endpoints. {@code delivery} (Pro+) uploads the
 * output straight to your own bucket via a presigned PUT URL; {@code test}
 * runs the full pipeline without real rendering. The two cannot be
 * combined.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarkdownToPdfRequest {
    private String markdown;
    private String webhookUrl;
    private PdfOptions options;
    private RenderOptions render;
    private DeliveryOptions delivery;
    private Boolean test;

    public MarkdownToPdfRequest() {}

    public MarkdownToPdfRequest(String markdown) {
        this.markdown = markdown;
    }

    public MarkdownToPdfRequest(String markdown, String webhookUrl, PdfOptions options, RenderOptions render, DeliveryOptions delivery, Boolean test) {
        this.markdown = markdown;
        this.webhookUrl = webhookUrl;
        this.options = options;
        this.render = render;
        this.delivery = delivery;
        this.test = test;
    }

    public String getMarkdown() { return markdown; }
    public void setMarkdown(String markdown) { this.markdown = markdown; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public PdfOptions getOptions() { return options; }
    public void setOptions(PdfOptions options) { this.options = options; }

    public RenderOptions getRender() { return render; }
    public void setRender(RenderOptions render) { this.render = render; }

    public DeliveryOptions getDelivery() { return delivery; }
    public void setDelivery(DeliveryOptions delivery) { this.delivery = delivery; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
