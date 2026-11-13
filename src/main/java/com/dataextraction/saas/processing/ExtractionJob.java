package com.dataextraction.saas.processing;

import java.time.Instant;
import java.util.UUID;

public record ExtractionJob(UUID id, UUID documentId, UUID organizationId, String documentType, String outputFormat,
                            JobStatus status, int progress, int attempt, Instant createdAt, Instant updatedAt,
                            UUID extractionId, String errorMessage) { }
