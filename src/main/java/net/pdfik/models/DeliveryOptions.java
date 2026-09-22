package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * BYOB delivery (Pro+): the rendered output is uploaded straight to your own
 * bucket via a presigned PUT URL; nothing is stored on PDFik's side.
 *
 * <p>Presign for at least 15 minutes and without a Content-Type condition
 * (the upload sends none). For delivered jobs the {@code job.finished}
 * webhook carries neither {@code file_url} nor {@code expires_at} (PDFik does
 * not record the destination), and the download endpoint answers 404. Not
 * combinable with test mode (400).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeliveryOptions {
    /** Delivery mode; "presigned_put" is the only value and is sent explicitly. */
    private String mode = "presigned_put";
    /** Presigned HTTPS PUT URL for your own bucket. */
    private String url;

    public DeliveryOptions() {}

    public DeliveryOptions(String url) {
        this.url = url;
    }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
