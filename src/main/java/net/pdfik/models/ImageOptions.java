package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Screenshot output options for {@code urlToImage} / {@code htmlToImage}.
 *
 * <p>{@code format} is "png" (the server default) or "jpeg". {@code fullPage}
 * captures the whole scrollable page instead of just the visible area; the
 * server default is {@code false}, so the image is exactly your viewport. With
 * {@code true} the height follows the real page and is clipped at 8192 px — a
 * ceiling, not a target, so a 2,000 px page still gives a 2,000 px image.
 * {@code quality} (1-100) applies to JPEG only — the server rejects quality
 * together with png (422). {@code viewport} defaults to 1024x768 server-side;
 * it sets the image width, and with {@code fullPage=false} (the default) also
 * its height. Unset fields are omitted from the request body and the server
 * defaults apply.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageOptions {
    /** Image format: "png" (server default) or "jpeg". */
    private String format;
    private Boolean fullPage;
    /** JPEG quality 1-100 (jpeg only; rejected for png). */
    private Integer quality;
    private ImageViewport viewport;

    public ImageOptions() {}

    public static Builder builder() {
        return new Builder();
    }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public Boolean getFullPage() { return fullPage; }
    public void setFullPage(Boolean fullPage) { this.fullPage = fullPage; }

    public Integer getQuality() { return quality; }
    public void setQuality(Integer quality) { this.quality = quality; }

    public ImageViewport getViewport() { return viewport; }
    public void setViewport(ImageViewport viewport) { this.viewport = viewport; }

    public static class Builder {
        private final ImageOptions options = new ImageOptions();

        public Builder format(String format) {
            options.setFormat(format);
            return this;
        }

        public Builder fullPage(Boolean fullPage) {
            options.setFullPage(fullPage);
            return this;
        }

        public Builder quality(Integer quality) {
            options.setQuality(quality);
            return this;
        }

        public Builder viewport(ImageViewport viewport) {
            options.setViewport(viewport);
            return this;
        }

        public ImageOptions build() {
            return options;
        }
    }
}
