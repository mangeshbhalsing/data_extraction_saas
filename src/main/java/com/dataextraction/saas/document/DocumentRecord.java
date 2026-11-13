package com.dataextraction.saas.document;

import java.time.Instant;
import java.util.UUID;

public record DocumentRecord(
        UUID id,
        UUID organizationId,
        UUID uploadedBy,
        String filename,
        String contentType,
        long sizeBytes,
        DocumentStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant updatedAt,
        String failureReason,
        String failureCode,
        UUID failureJobId,
        Integer failureAttempt,
        Instant failedAt) {

    public DocumentRecord(UUID id, UUID organizationId, UUID uploadedBy, String filename, String contentType,
                          long sizeBytes, DocumentStatus status, Instant createdAt, Instant expiresAt, Instant updatedAt,
                          String failureReason) {
        this(id, organizationId, uploadedBy, filename, contentType, sizeBytes, status, createdAt, expiresAt, updatedAt,
                failureReason, null, null, null, null);
    }

    public DocumentRecord(UUID id, UUID organizationId, UUID uploadedBy, String filename, String contentType,
                          long sizeBytes, DocumentStatus status, Instant createdAt, Instant expiresAt, Instant updatedAt) {
        this(id, organizationId, uploadedBy, filename, contentType, sizeBytes, status, createdAt, expiresAt, updatedAt, null, null, null, null, null);
    }
}
