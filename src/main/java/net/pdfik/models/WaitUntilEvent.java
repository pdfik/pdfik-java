package net.pdfik.models;

import com.fasterxml.jackson.annotation.JsonValue;

public enum WaitUntilEvent {
    LOAD("load"),
    DOM_CONTENT_LOADED("domcontentloaded"),
    NETWORK_IDLE("networkidle"),
    COMMIT("commit");

    private final String value;

    WaitUntilEvent(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
