package com.dataextraction.saas.organization;

import java.time.Instant;
import java.util.UUID;

public record WorkspaceOrganization(UUID id, String name, UUID createdBy, Instant createdAt) { }
