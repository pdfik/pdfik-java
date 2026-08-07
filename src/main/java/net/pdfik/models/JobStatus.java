package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonValue;

public enum JobStatus {
    QUEUED("queued"),
    RENDERING("rendering"),
    UPLOADING("uploading"),
    DONE("done"),
    FAILED("failed");

    private final String value;

    JobStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
