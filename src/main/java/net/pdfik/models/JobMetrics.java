package net.pdfik.models;

public class JobMetrics {
    private Long fileSizeBytes;
    private String fileSizeHuman;
    private Integer pageLoadMs;
    private Integer processingMs;
    private Integer totalDurationMs;
    private Integer pageCount;

    public JobMetrics() {}

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getFileSizeHuman() { return fileSizeHuman; }
    public void setFileSizeHuman(String fileSizeHuman) { this.fileSizeHuman = fileSizeHuman; }

    public Integer getPageLoadMs() { return pageLoadMs; }
    public void setPageLoadMs(Integer pageLoadMs) { this.pageLoadMs = pageLoadMs; }

    /** PDFik processing time (PDF generation, watermark, compression, encryption, upload) — excludes page load and client-requested waits. */
    public Integer getProcessingMs() { return processingMs; }
    public void setProcessingMs(Integer processingMs) { this.processingMs = processingMs; }

    public Integer getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(Integer totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
}
