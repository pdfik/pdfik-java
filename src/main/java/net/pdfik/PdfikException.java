package net.pdfik;

public class PdfikException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;
    private final String responseBody;

    public PdfikException(String message, int statusCode) {
        this(message, statusCode, null, null);
    }

    public PdfikException(String message, int statusCode, String errorCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getErrorCode() { return errorCode; }
    public String getResponseBody() { return responseBody; }

    @Override
    public String toString() {
        String codeStr = (errorCode != null) ? " [" + errorCode + "]" : "";
        return getMessage() + " (HTTP " + statusCode + ")" + codeStr;
    }
}
