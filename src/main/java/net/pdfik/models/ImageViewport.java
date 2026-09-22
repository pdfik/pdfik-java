package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The browser window a screenshot job opens the page in, in CSS pixels. You
 * choose the size and the server captures exactly that window — nothing is
 * scaled or fitted. Width must be 320-1920 and height 320-8192; outside that
 * range the server answers 422. Defaults to 1024x768 when omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageViewport {
    private Integer width;
    private Integer height;

    public ImageViewport() {}

    public ImageViewport(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
}
