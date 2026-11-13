package com.dataextraction.saas.document;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DocumentStore {
    private final Map<UUID, DocumentRecord> documents = new ConcurrentHashMap<>();
    private final Map<UUID, byte[]> content = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public DocumentStore() { this.jdbc = null; }
    @Autowired public DocumentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(DocumentRecord document) {
        if (jdbc == null) { documents.put(document.id(), document); return; }
        jdbc.update("MERGE INTO documents (document_id, organization_id, uploaded_by, filename, content_type, size_bytes, status, created_at, expires_at, updated_at, failure_reason, failure_code, failure_job_id, failure_attempt, failed_at) KEY(document_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                document.id().toString(), document.organizationId().toString(), document.uploadedBy().toString(), document.filename(), document.contentType(),
                document.sizeBytes(), document.status().name(), document.createdAt().toEpochMilli(), document.expiresAt().toEpochMilli(), document.updatedAt().toEpochMilli(),
                document.failureReason(), document.failureCode(), document.failureJobId() == null ? null : document.failureJobId().toString(),
                document.failureAttempt(), document.failedAt() == null ? null : document.failedAt().toEpochMilli());
    }
    public DocumentRecord find(UUID id) {
        if (jdbc == null) return documents.get(id);
        return jdbc.query("SELECT * FROM documents WHERE document_id = ?", this::map, id.toString()).stream().findFirst().orElse(null);
    }
    public Collection<DocumentRecord> findAll() {
        return jdbc == null ? documents.values() : jdbc.query("SELECT * FROM documents", this::map);
    }
    public void saveContent(UUID id, byte[] bytes) {
        if (jdbc == null) { content.put(id, bytes.clone()); return; }
        jdbc.update("UPDATE documents SET content = ? WHERE document_id = ?", bytes.clone(), id.toString());
    }
    public boolean hasContent(UUID id) {
        if (jdbc == null) return content.containsKey(id);
        Boolean exists = jdbc.queryForObject("SELECT CASE WHEN content IS NULL THEN FALSE ELSE TRUE END FROM documents WHERE document_id = ?", Boolean.class, id.toString());
        return Boolean.TRUE.equals(exists);
    }
    public byte[] getContent(UUID id) {
        if (jdbc != null) return jdbc.query("SELECT content FROM documents WHERE document_id = ?", rs -> rs.next() ? rs.getBytes(1) : null, id.toString());
        byte[] bytes = content.get(id);
        return bytes == null ? null : bytes.clone();
    }
    public void delete(UUID id) {
        if (jdbc == null) { documents.remove(id); content.remove(id); }
        else jdbc.update("DELETE FROM documents WHERE document_id = ?", id.toString());
    }
    public void clear() {
        if (jdbc == null) { documents.clear(); content.clear(); }
        else jdbc.update("DELETE FROM documents");
    }

    private DocumentRecord map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        String failureJob = row.getString("failure_job_id");
        long failedAtMillis = row.getLong("failed_at");
        Instant failedAt = row.wasNull() ? null : Instant.ofEpochMilli(failedAtMillis);
        int failureAttempt = row.getInt("failure_attempt");
        Integer attempt = row.wasNull() ? null : failureAttempt;
        return new DocumentRecord(UUID.fromString(row.getString("document_id")), UUID.fromString(row.getString("organization_id")),
                UUID.fromString(row.getString("uploaded_by")), row.getString("filename"), row.getString("content_type"), row.getLong("size_bytes"),
                DocumentStatus.valueOf(row.getString("status")), java.time.Instant.ofEpochMilli(row.getLong("created_at")),
                java.time.Instant.ofEpochMilli(row.getLong("expires_at")), java.time.Instant.ofEpochMilli(row.getLong("updated_at")),
                row.getString("failure_reason"), row.getString("failure_code"), failureJob == null ? null : UUID.fromString(failureJob), attempt, failedAt);
    }
}
