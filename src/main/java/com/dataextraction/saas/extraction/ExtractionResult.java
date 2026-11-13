package com.dataextraction.saas.extraction;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExtractionResult(UUID id, UUID jobId, UUID documentId, UUID organizationId,
                               String documentType, String outputFormat, Map<String, Object> data,
                               ReviewStatus reviewStatus, int version, List<ValidationIssue> validationErrors,
                               Instant createdAt, Instant updatedAt, Instant approvedAt) { }
