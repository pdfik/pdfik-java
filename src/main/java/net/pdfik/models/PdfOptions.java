package net.pdfik.models;

public class PdfOptions {
    private PaperFormat format;
    private Boolean landscape;
    private MarginOptions margin;
    private Boolean printBackground;
    private Boolean displayHeaderFooter;
    private String headerTemplate;
    private String footerTemplate;
    private WatermarkOptions watermark;
    private String userPassword;
    private CompressionOptions compression;
    private Double scale;

    public PdfOptions() {}

    public static Builder builder() {
        return new Builder();
    }

    public PaperFormat getFormat() { return format; }
    public void setFormat(PaperFormat format) { this.format = format; }

    public Boolean getLandscape() { return landscape; }
    public void setLandscape(Boolean landscape) { this.landscape = landscape; }

    public MarginOptions getMargin() { return margin; }
    public void setMargin(MarginOptions margin) { this.margin = margin; }

    public Boolean getPrintBackground() { return printBackground; }
    public void setPrintBackground(Boolean printBackground) { this.printBackground = printBackground; }

    public Boolean getDisplayHeaderFooter() { return displayHeaderFooter; }
    public void setDisplayHeaderFooter(Boolean displayHeaderFooter) { this.displayHeaderFooter = displayHeaderFooter; }

    public String getHeaderTemplate() { return headerTemplate; }
    public void setHeaderTemplate(String headerTemplate) { this.headerTemplate = headerTemplate; }

    public String getFooterTemplate() { return footerTemplate; }
    public void setFooterTemplate(String footerTemplate) { this.footerTemplate = footerTemplate; }

    public WatermarkOptions getWatermark() { return watermark; }
    public void setWatermark(WatermarkOptions watermark) { this.watermark = watermark; }

    public String getUserPassword() { return userPassword; }
    public void setUserPassword(String userPassword) { this.userPassword = userPassword; }

    public CompressionOptions getCompression() { return compression; }
    public void setCompression(CompressionOptions compression) { this.compression = compression; }

    public Double getScale() { return scale; }
    public void setScale(Double scale) { this.scale = scale; }

    public static class Builder {
        private final PdfOptions options = new PdfOptions();

        public Builder format(PaperFormat format) {
            options.setFormat(format);
            return this;
        }

        public Builder landscape(Boolean landscape) {
            options.setLandscape(landscape);
            return this;
        }

        public Builder margin(MarginOptions margin) {
            options.setMargin(margin);
            return this;
        }

        public Builder printBackground(Boolean printBackground) {
            options.setPrintBackground(printBackground);
            return this;
        }

        public Builder displayHeaderFooter(Boolean displayHeaderFooter) {
            options.setDisplayHeaderFooter(displayHeaderFooter);
            return this;
        }

        public Builder headerTemplate(String headerTemplate) {
            options.setHeaderTemplate(headerTemplate);
            return this;
        }

        public Builder footerTemplate(String footerTemplate) {
            options.setFooterTemplate(footerTemplate);
            return this;
        }

        public Builder watermark(WatermarkOptions watermark) {
            options.setWatermark(watermark);
            return this;
        }

        public Builder userPassword(String userPassword) {
            options.setUserPassword(userPassword);
            return this;
        }

        public Builder compression(CompressionOptions compression) {
            options.setCompression(compression);
            return this;
        }

        public Builder scale(Double scale) {
            options.setScale(scale);
            return this;
        }

        public PdfOptions build() {
            return options;
        }
    }
}
