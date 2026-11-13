package com.dataextraction.saas.audit;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditStore {
    private static final int MAX_EVENTS = 10_000;
    private final ConcurrentLinkedDeque<AuditEvent> events = new ConcurrentLinkedDeque<>();
    private final JdbcTemplate jdbc;
    public AuditStore() { this.jdbc = null; }
    @Autowired public AuditStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void append(AuditEvent event) {
        if (jdbc != null) {
            jdbc.update("INSERT INTO audit_events (event_id, user_id, organization_id, action, resource, response_status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    event.eventId().toString(), event.userId().toString(), event.organizationId().toString(), event.action(), event.resource(),
                    event.responseStatus(), event.createdAt().toEpochMilli());
            jdbc.update("DELETE FROM audit_events WHERE event_id NOT IN (SELECT event_id FROM audit_events ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY)", MAX_EVENTS);
            return;
        }
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) events.pollLast();
    }
    public List<AuditEvent> latest(int limit) {
        if (jdbc == null) return events.stream().limit(limit).toList();
        return jdbc.query("SELECT * FROM audit_events ORDER BY created_at DESC FETCH FIRST ? ROWS ONLY", (row, ignored) ->
                new AuditEvent(java.util.UUID.fromString(row.getString("event_id")), java.util.UUID.fromString(row.getString("user_id")),
                        java.util.UUID.fromString(row.getString("organization_id")), row.getString("action"), row.getString("resource"),
                        row.getInt("response_status"), java.time.Instant.ofEpochMilli(row.getLong("created_at"))), limit);
    }
}
