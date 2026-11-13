package com.dataextraction.saas.processing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persists every failed attempt, while extraction_jobs keeps the job's current state. */
@Component
public class ExtractionFailureStore implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public ExtractionFailureStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(ExtractionFailure failure) {
        jdbc.update("MERGE INTO extraction_failures (failure_id, job_id, document_id, organization_id, attempt, error_code, error_message, exception_type, occurred_at) "
                        + "KEY(job_id, attempt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                failure.id().toString(), failure.jobId().toString(), failure.documentId().toString(), failure.organizationId().toString(),
                failure.attempt(), failure.errorCode(), failure.errorMessage(), failure.exceptionType(), failure.occurredAt().toEpochMilli());
    }

    public List<ExtractionFailure> forDocument(UUID documentId) {
        return jdbc.query("SELECT * FROM extraction_failures WHERE document_id = ? ORDER BY occurred_at DESC",
                this::map, documentId.toString());
    }

    public void deleteByDocument(UUID documentId) {
        jdbc.update("DELETE FROM extraction_failures WHERE document_id = ?", documentId.toString());
    }

    @Override
    public void run(ApplicationArguments args) {
        backfillFailedJobs();
        populateDocumentFailureSummary();
    }

    private void backfillFailedJobs() {
        List<ExtractionFailure> missing = jdbc.query("SELECT j.job_id, j.document_id, j.organization_id, j.attempt, j.error_message, j.updated_at "
                        + "FROM extraction_jobs j LEFT JOIN extraction_failures f ON f.job_id = j.job_id AND f.attempt = j.attempt "
                        + "WHERE j.status = 'FAILED' AND f.failure_id IS NULL",
                (row, ignored) -> {
                    String stored = row.getString("error_message");
                    int separator = stored == null ? -1 : stored.indexOf(": ");
                    String code = separator > 0 ? stored.substring(0, separator) : "EXTRACTION_FAILED";
                    String message = separator > 0 ? stored.substring(separator + 2) : (stored == null ? "Extraction failed" : stored);
                    return new ExtractionFailure(UUID.randomUUID(), UUID.fromString(row.getString("job_id")),
                            UUID.fromString(row.getString("document_id")), UUID.fromString(row.getString("organization_id")),
                            row.getInt("attempt"), code, message, null, Instant.ofEpochMilli(row.getLong("updated_at")));
                });
        missing.forEach(this::record);
    }

    private void populateDocumentFailureSummary() {
        List<FailureSummary> summaries = jdbc.query("SELECT d.document_id, f.job_id, f.attempt, f.error_code, f.error_message, f.occurred_at "
                        + "FROM documents d JOIN extraction_failures f ON f.document_id = d.document_id "
                        + "WHERE d.status = 'FAILED' ORDER BY d.document_id, f.occurred_at DESC",
                (row, ignored) -> new FailureSummary(row.getString("document_id"), row.getString("job_id"), row.getInt("attempt"),
                        row.getString("error_code"), row.getString("error_message"), row.getLong("occurred_at")));
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (FailureSummary summary : summaries) {
            if (!seen.add(summary.documentId())) continue;
            jdbc.update("UPDATE documents SET failure_reason = ?, failure_code = ?, failure_job_id = ?, failure_attempt = ?, failed_at = ? "
                            + "WHERE document_id = ? AND failure_code IS NULL",
                    summary.message(), summary.code(), summary.jobId(), summary.attempt(), summary.occurredAt(), summary.documentId());
        }
    }

    private ExtractionFailure map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        return new ExtractionFailure(UUID.fromString(row.getString("failure_id")), UUID.fromString(row.getString("job_id")),
                UUID.fromString(row.getString("document_id")), UUID.fromString(row.getString("organization_id")), row.getInt("attempt"),
                row.getString("error_code"), row.getString("error_message"), row.getString("exception_type"),
                Instant.ofEpochMilli(row.getLong("occurred_at")));
    }

    private record FailureSummary(String documentId, String jobId, int attempt, String code, String message, long occurredAt) { }
}
