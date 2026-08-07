package net.pdfik.models;

public class RenderOptions {
    private Integer pageLoadTimeoutMs;
    private WaitUntilEvent waitUntil;
    private String waitForSelector;
    private Integer waitAfterLoadMs;

    public RenderOptions() {}

    public static Builder builder() {
        return new Builder();
    }

    public Integer getPageLoadTimeoutMs() { return pageLoadTimeoutMs; }
    public void setPageLoadTimeoutMs(Integer pageLoadTimeoutMs) { this.pageLoadTimeoutMs = pageLoadTimeoutMs; }

    public WaitUntilEvent getWaitUntil() { return waitUntil; }
    public void setWaitUntil(WaitUntilEvent waitUntil) { this.waitUntil = waitUntil; }

    public String getWaitForSelector() { return waitForSelector; }
    public void setWaitForSelector(String waitForSelector) { this.waitForSelector = waitForSelector; }

    public Integer getWaitAfterLoadMs() { return waitAfterLoadMs; }
    public void setWaitAfterLoadMs(Integer waitAfterLoadMs) {
        if (waitAfterLoadMs != null && (waitAfterLoadMs < 0 || waitAfterLoadMs > 10000)) {
            throw new IllegalArgumentException("waitAfterLoadMs must be between 0 and 10000 ms");
        }
        this.waitAfterLoadMs = waitAfterLoadMs;
    }

    public static class Builder {
        private final RenderOptions options = new RenderOptions();

        public Builder pageLoadTimeoutMs(Integer pageLoadTimeoutMs) {
            options.setPageLoadTimeoutMs(pageLoadTimeoutMs);
            return this;
        }

        public Builder waitUntil(WaitUntilEvent waitUntil) {
            options.setWaitUntil(waitUntil);
            return this;
        }

        public Builder waitForSelector(String waitForSelector) {
            options.setWaitForSelector(waitForSelector);
            return this;
        }

        public Builder waitAfterLoadMs(Integer waitAfterLoadMs) {
            options.setWaitAfterLoadMs(waitAfterLoadMs);
            return this;
        }

        public RenderOptions build() {
            return options;
        }
    }
}
