package com.dataextraction.saas.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(UUID eventId, UUID userId, UUID organizationId, String action,
                         String resource, int responseStatus, Instant createdAt) { }
