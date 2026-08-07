package net.pdfik.models;

public class WatermarkOptions {
    private String text;
    private String color;
    private String fontSize;
    private Integer rotationDegrees;

    public WatermarkOptions() {}

    public WatermarkOptions(String text) {
        this.text = text;
    }

    public WatermarkOptions(String text, String color, String fontSize, Integer rotationDegrees) {
        this.text = text;
        this.color = color;
        this.fontSize = fontSize;
        this.rotationDegrees = rotationDegrees;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public String getFontSize() { return fontSize; }
    public void setFontSize(String fontSize) { this.fontSize = fontSize; }

    public Integer getRotationDegrees() { return rotationDegrees; }
    public void setRotationDegrees(Integer rotationDegrees) { this.rotationDegrees = rotationDegrees; }
}
