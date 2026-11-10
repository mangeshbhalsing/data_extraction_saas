package com.dataextraction.saas.security;

import java.time.Instant;
import java.util.UUID;

public record UserAccount(UUID id, UUID organizationId, String email, String passwordHash,
                          String displayName, String role, boolean enabled, boolean protectedAdmin, Instant createdAt) {
    public UserAccount(UUID id, UUID organizationId, String email, String passwordHash,
                       String displayName, String role, boolean enabled, Instant createdAt) {
        this(id, organizationId, email, passwordHash, displayName, role, enabled, false, createdAt);
    }
    public UserAccount(UUID id, UUID organizationId, String email, String passwordHash,
                       String displayName, String role, Instant createdAt) {
        this(id, organizationId, email, passwordHash, displayName, role, true, false, createdAt);
    }
}
