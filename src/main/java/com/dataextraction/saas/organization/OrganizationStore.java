package com.dataextraction.saas.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrganizationStore {
    private final JdbcTemplate jdbc;

    @Autowired public OrganizationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<WorkspaceOrganization> findById(UUID id) {
        return jdbc.query("SELECT * FROM organizations WHERE organization_id = ?", this::map, id.toString()).stream().findFirst();
    }

    public WorkspaceOrganization save(WorkspaceOrganization organization) {
        jdbc.update("MERGE INTO organizations (organization_id, name, created_by, created_at) KEY(organization_id) VALUES (?, ?, ?, ?)",
                organization.id().toString(), organization.name(), organization.createdBy() == null ? null : organization.createdBy().toString(),
                organization.createdAt().toEpochMilli());
        return organization;
    }

    public List<WorkspaceOrganization> all() {
        return jdbc.query("SELECT * FROM organizations ORDER BY name", this::map);
    }

    private WorkspaceOrganization map(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        String createdBy = row.getString("created_by");
        return new WorkspaceOrganization(UUID.fromString(row.getString("organization_id")), row.getString("name"),
                createdBy == null ? null : UUID.fromString(createdBy), java.time.Instant.ofEpochMilli(row.getLong("created_at")));
    }
}
