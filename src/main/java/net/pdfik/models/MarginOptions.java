package net.pdfik.models;

public class MarginOptions {
    private String top;
    private String right;
    private String bottom;
    private String left;

    public MarginOptions() {}

    public MarginOptions(String top, String right, String bottom, String left) {
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.left = left;
    }

    public String getTop() { return top; }
    public void setTop(String top) { this.top = top; }

    public String getRight() { return right; }
    public void setRight(String right) { this.right = right; }

    public String getBottom() { return bottom; }
    public void setBottom(String bottom) { this.bottom = bottom; }

    public String getLeft() { return left; }
    public void setLeft(String left) { this.left = left; }
}
