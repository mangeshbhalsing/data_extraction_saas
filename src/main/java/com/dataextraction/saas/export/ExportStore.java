package com.dataextraction.saas.export;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ExportStore {
    public record ExportFile(UUID id, UUID organizationId, Set<UUID> documentIds,
                             String status, String filename, byte[] content, Instant createdAt) { }
    private final Map<UUID, ExportFile> exports = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public ExportStore() { this.jdbc = null; this.json = new ObjectMapper(); }
    @Autowired public ExportStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public void save(ExportFile file) {
        if (jdbc == null) { exports.put(file.id(), file); return; }
        jdbc.update("MERGE INTO exports (export_id, organization_id, document_ids_json, status, filename, content, created_at) KEY(export_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                file.id().toString(), file.organizationId().toString(), write(file.documentIds().stream().map(UUID::toString).toList()),
                file.status(), file.filename(), file.content().clone(), file.createdAt().toEpochMilli());
    }
    public ExportFile find(UUID id) {
        if (jdbc == null) return exports.get(id);
        return jdbc.query("SELECT * FROM exports WHERE export_id = ?", this::map, id.toString()).stream().findFirst().orElse(null);
    }
    public Collection<ExportFile> all() { return jdbc == null ? exports.values() : jdbc.query("SELECT * FROM exports", this::map); }
    public void deleteByDocument(UUID documentId) {
        if (jdbc == null) { exports.entrySet().removeIf(entry -> entry.getValue().documentIds().contains(documentId)); return; }
        all().stream().filter(file -> file.documentIds().contains(documentId)).forEach(file -> jdbc.update("DELETE FROM exports WHERE export_id = ?", file.id().toString()));
    }
    private ExportFile map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        List<String> ids;
        try { ids = json.readValue(row.getString("document_ids_json"), new TypeReference<>() { }); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not read export document list", exception); }
        return new ExportFile(UUID.fromString(row.getString("export_id")), UUID.fromString(row.getString("organization_id")),
                ids.stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet()), row.getString("status"),
                row.getString("filename"), row.getBytes("content"), Instant.ofEpochMilli(row.getLong("created_at")));
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException exception) { throw new IllegalStateException("Could not serialize export", exception); }
    }
}
