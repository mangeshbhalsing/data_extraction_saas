package com.dataextraction.saas.processing;

import java.util.Map;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExtractionJobStore {
    private final Map<UUID, ExtractionJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, UUID> idempotencyKeys = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public ExtractionJobStore() { this.jdbc = null; }
    @Autowired public ExtractionJobStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ExtractionJob find(UUID id) {
        if (jdbc == null) return jobs.get(id);
        return jdbc.query("SELECT * FROM extraction_jobs WHERE job_id = ?", this::map, id.toString()).stream().findFirst().orElse(null);
    }
    public void save(ExtractionJob job) {
        if (jdbc == null) { jobs.put(job.id(), job); return; }
        jdbc.update("MERGE INTO extraction_jobs (job_id, document_id, organization_id, document_type, output_format, status, progress, attempt, created_at, updated_at, extraction_id, error_message) KEY(job_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                job.id().toString(), job.documentId().toString(), job.organizationId().toString(), job.documentType(), job.outputFormat(), job.status().name(),
                job.progress(), job.attempt(), job.createdAt().toEpochMilli(), job.updatedAt().toEpochMilli(),
                job.extractionId() == null ? null : job.extractionId().toString(), job.errorMessage());
    }
    public Collection<ExtractionJob> all() { return jdbc == null ? jobs.values() : jdbc.query("SELECT * FROM extraction_jobs", this::map); }
    public UUID findByIdempotencyKey(String key) {
        if (jdbc == null) return idempotencyKeys.get(key);
        return jdbc.query("SELECT job_id FROM extraction_idempotency WHERE scope_key = ?", rs -> rs.next() ? UUID.fromString(rs.getString(1)) : null, key);
    }
    public void rememberIdempotencyKey(String key, UUID jobId) {
        if (jdbc == null) idempotencyKeys.put(key, jobId);
        else jdbc.update("MERGE INTO extraction_idempotency (scope_key, job_id) KEY(scope_key) VALUES (?, ?)", key, jobId.toString());
    }
    public void deleteByDocument(UUID documentId) {
        if (jdbc != null) {
            jdbc.update("DELETE FROM extraction_idempotency WHERE job_id IN (SELECT job_id FROM extraction_jobs WHERE document_id = ?)", documentId.toString());
            jdbc.update("DELETE FROM extraction_jobs WHERE document_id = ?", documentId.toString());
            return;
        }
        java.util.Set<UUID> removed = jobs.values().stream().filter(job -> job.documentId().equals(documentId))
                .map(ExtractionJob::id).collect(java.util.stream.Collectors.toSet());
        removed.forEach(jobs::remove);
        idempotencyKeys.entrySet().removeIf(entry -> removed.contains(entry.getValue()));
    }
    public void clear() {
        if (jdbc == null) { jobs.clear(); idempotencyKeys.clear(); }
        else { jdbc.update("DELETE FROM extraction_idempotency"); jdbc.update("DELETE FROM extraction_jobs"); }
    }

    private ExtractionJob map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        String extractionId = row.getString("extraction_id");
        return new ExtractionJob(UUID.fromString(row.getString("job_id")), UUID.fromString(row.getString("document_id")),
                UUID.fromString(row.getString("organization_id")), row.getString("document_type"), row.getString("output_format"),
                JobStatus.valueOf(row.getString("status")), row.getInt("progress"), row.getInt("attempt"),
                java.time.Instant.ofEpochMilli(row.getLong("created_at")), java.time.Instant.ofEpochMilli(row.getLong("updated_at")),
                extractionId == null ? null : UUID.fromString(extractionId), row.getString("error_message"));
    }
}
