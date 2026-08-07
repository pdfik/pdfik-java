package net.pdfik.models;

public class CompressionOptions {
    private Integer level;
    private Integer imageQuality;

    public CompressionOptions() {}

    public CompressionOptions(Integer level, Integer imageQuality) {
        this.level = level;
        this.imageQuality = imageQuality;
    }

    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }

    public Integer getImageQuality() { return imageQuality; }
    public void setImageQuality(Integer imageQuality) { this.imageQuality = imageQuality; }
}
