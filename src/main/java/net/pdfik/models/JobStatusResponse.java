package net.pdfik.models;

import java.time.Instant;

public class JobStatusResponse {
    private JobStatus status;
    private Instant createdAt;
    private Instant finishedAt;
    /** Timestamp after which the file can no longer be downloaded (finishedAt + retention window, currently 24h); null until finished. */
    private Instant expiresAt;
    private Integer pagesCount;
    private String errorCode;
    private JobMetrics metrics;
    /** True when the job was submitted in test mode (no real rendering; download returns a sample PDF). */
    private Boolean test;

    public JobStatusResponse() {}

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Integer getPagesCount() { return pagesCount; }
    public void setPagesCount(Integer pagesCount) { this.pagesCount = pagesCount; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public JobMetrics getMetrics() { return metrics; }
    public void setMetrics(JobMetrics metrics) { this.metrics = metrics; }

    public Boolean getTest() { return test; }
    public void setTest(Boolean test) { this.test = test; }
}
