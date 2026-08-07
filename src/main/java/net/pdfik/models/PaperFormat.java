package net.pdfik.models;

public enum PaperFormat {
    A4("A4"),
    A3("A3"),
    LETTER("Letter"),
    LEGAL("Legal"),
    TABLOID("Tabloid");

    private final String value;

    PaperFormat(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
