package net.pdfik.models;

public class JobCreatedResponse {
    private String jobId;
    private String status;
    private String detail;

    public JobCreatedResponse() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
