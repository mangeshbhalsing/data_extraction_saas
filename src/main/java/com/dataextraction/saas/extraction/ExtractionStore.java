package com.dataextraction.saas.extraction;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExtractionStore {
    private final Map<UUID, ExtractionResult> results = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> extractionIdsByJob = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ExtractionStore() { this.jdbc = null; this.json = new ObjectMapper(); }
    @Autowired public ExtractionStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public ExtractionResult find(UUID id) {
        if (jdbc == null) return results.get(id);
        return jdbc.query("SELECT * FROM extraction_results WHERE extraction_id = ?", this::map, id.toString()).stream().findFirst().orElse(null);
    }
    public ExtractionResult findByJob(UUID jobId) {
        if (jdbc != null) return jdbc.query("SELECT * FROM extraction_results WHERE job_id = ?", this::map, jobId.toString()).stream().findFirst().orElse(null);
        UUID id = extractionIdsByJob.get(jobId);
        return id == null ? null : results.get(id);
    }
    public Collection<ExtractionResult> all() { return jdbc == null ? results.values() : jdbc.query("SELECT * FROM extraction_results", this::map); }
    public void save(ExtractionResult result) {
        if (jdbc != null) {
            jdbc.update("MERGE INTO extraction_results (extraction_id, job_id, document_id, organization_id, document_type, output_format, data_json, review_status, version, validation_json, created_at, updated_at, approved_at) KEY(extraction_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    result.id().toString(), result.jobId().toString(), result.documentId().toString(), result.organizationId().toString(),
                    result.documentType(), result.outputFormat(), write(result.data()), result.reviewStatus().name(), result.version(),
                    write(result.validationErrors()), result.createdAt().toEpochMilli(), result.updatedAt().toEpochMilli(),
                    result.approvedAt() == null ? null : result.approvedAt().toEpochMilli());
            return;
        }
        results.put(result.id(), result);
        extractionIdsByJob.put(result.jobId(), result.id());
    }
    public void deleteByDocument(UUID documentId) {
        if (jdbc != null) { jdbc.update("DELETE FROM extraction_results WHERE document_id = ?", documentId.toString()); return; }
        results.values().stream().filter(result -> result.documentId().equals(documentId)).map(ExtractionResult::id)
                .toList().forEach(results::remove);
        extractionIdsByJob.entrySet().removeIf(entry -> !results.containsKey(entry.getValue()));
    }
    public void clear() {
        if (jdbc == null) { results.clear(); extractionIdsByJob.clear(); }
        else jdbc.update("DELETE FROM extraction_results");
    }

    private ExtractionResult map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        long approvedAt = row.getLong("approved_at");
        java.time.Instant approved = row.wasNull() ? null : java.time.Instant.ofEpochMilli(approvedAt);
        return new ExtractionResult(UUID.fromString(row.getString("extraction_id")), UUID.fromString(row.getString("job_id")),
                UUID.fromString(row.getString("document_id")), UUID.fromString(row.getString("organization_id")),
                row.getString("document_type"), row.getString("output_format"), readData(row.getString("data_json")),
                ReviewStatus.valueOf(row.getString("review_status")), row.getInt("version"), readValidation(row.getString("validation_json")),
                java.time.Instant.ofEpochMilli(row.getLong("created_at")), java.time.Instant.ofEpochMilli(row.getLong("updated_at")), approved);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not serialize extraction result", exception); }
    }
    private Map<String, Object> readData(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not read extraction result", exception); }
    }
    private java.util.List<ValidationIssue> readValidation(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not read extraction validation", exception); }
    }
}
