package net.pdfik.models;

/**
 * {@code GET /account}: the key's account — plan, current quota window, usage in it and
 * limits. Fields are read from snake_case JSON by the client's ObjectMapper.
 */
public class AccountResponse {
    private String plan;
    private Window window;
    private Usage usage;
    private Limits limits;
    private TestMode testMode;
    private Key key;

    public AccountResponse() {}

    /** free, starter, pro or business. */
    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    /**
     * The quota window limits are counted in: the UTC calendar month on the Free plan; on
     * paid plans monthly from the subscription's billing day.
     */
    public Window getWindow() { return window; }
    public void setWindow(Window window) { this.window = window; }

    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }

    public TestMode getTestMode() { return testMode; }
    public void setTestMode(TestMode testMode) { this.testMode = testMode; }

    public Key getKey() { return key; }
    public void setKey(Key key) { this.key = key; }

    public static class Window {
        private String start;
        private String end;

        public Window() {}

        /** RFC 3339, UTC, inclusive. */
        public String getStart() { return start; }
        public void setStart(String start) { this.start = start; }

        /** RFC 3339, UTC, exclusive: when the window resets. */
        public String getEnd() { return end; }
        public void setEnd(String end) { this.end = end; }
    }

    public static class Usage {
        private Long documents;
        private Long bytes;

        public Usage() {}

        /** Documents charged in this window (new jobs are refused at the limit); null if unknown right now. */
        public Long getDocuments() { return documents; }
        public void setDocuments(Long documents) { this.documents = documents; }

        /** Bytes of generated output finished in this window. */
        public Long getBytes() { return bytes; }
        public void setBytes(Long bytes) { this.bytes = bytes; }
    }

    public static class Limits {
        private Long documents;
        private Long bytes;
        private Integer requestsPerMinute;
        private Integer concurrentJobs;
        private Long maxFileSizeBytes;

        public Limits() {}

        /** Documents per window; null means unlimited. */
        public Long getDocuments() { return documents; }
        public void setDocuments(Long documents) { this.documents = documents; }

        /** Bytes of generated output per window; null means unlimited. */
        public Long getBytes() { return bytes; }
        public void setBytes(Long bytes) { this.bytes = bytes; }

        public Integer getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(Integer requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }

        public Integer getConcurrentJobs() { return concurrentJobs; }
        public void setConcurrentJobs(Integer concurrentJobs) { this.concurrentJobs = concurrentJobs; }

        public Long getMaxFileSizeBytes() { return maxFileSizeBytes; }
        public void setMaxFileSizeBytes(Long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    }

    public static class TestMode {
        private Integer perMinute;
        private Integer perDay;

        public TestMode() {}

        public Integer getPerMinute() { return perMinute; }
        public void setPerMinute(Integer perMinute) { this.perMinute = perMinute; }

        public Integer getPerDay() { return perDay; }
        public void setPerDay(Integer perDay) { this.perDay = perDay; }
    }

    public static class Key {
        private String name;

        public Key() {}

        /** The key's name in the dashboard; null if it has none. */
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
