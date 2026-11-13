package com.dataextraction.saas.processing;

import java.time.Instant;
import java.util.UUID;

public record ExtractionFailure(UUID id, UUID jobId, UUID documentId, UUID organizationId, int attempt,
                                String errorCode, String errorMessage, String exceptionType, Instant occurredAt) { }
