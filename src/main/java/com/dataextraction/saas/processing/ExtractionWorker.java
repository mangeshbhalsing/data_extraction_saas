package com.dataextraction.saas.processing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dataextraction.saas.document.DocumentRecord;
import com.dataextraction.saas.document.DocumentService;
import com.dataextraction.saas.document.DocumentStatus;
import com.dataextraction.saas.document.DocumentStore;
import com.dataextraction.saas.extraction.ExtractionProvider;
import com.dataextraction.saas.extraction.ExtractionResult;
import com.dataextraction.saas.extraction.ExtractionStore;
import com.dataextraction.saas.extraction.ExtractionService;
import com.dataextraction.saas.extraction.ReviewStatus;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ExtractionWorker {
    private final ExtractionJobStore jobs;
    private final DocumentStore documents;
    private final DocumentService documentService;
    private final ExtractionStore results;
    private final ExtractionService extractionService;
    private final ExtractionFailureStore failures;
    private final List<ExtractionProvider> providers;

    public ExtractionWorker(ExtractionJobStore jobs, DocumentStore documents, DocumentService documentService,
                           ExtractionStore results, ExtractionService extractionService,
                           ExtractionFailureStore failures, List<ExtractionProvider> providers) {
        this.jobs = jobs;
        this.documents = documents;
        this.documentService = documentService;
        this.results = results;
        this.extractionService = extractionService;
        this.failures = failures;
        this.providers = providers;
    }

    @Async
    public void process(UUID jobId) {
        ExtractionJob job = jobs.find(jobId);
        if (job == null || job.status() != JobStatus.QUEUED) return;
        jobs.save(copy(job, JobStatus.PROCESSING, 10, job.attempt(), null, null));
        DocumentRecord document = documents.find(job.documentId());
        byte[] bytes = documents.getContent(job.documentId());
        if (document == null || bytes == null) {
            fail(job, "Document content is no longer available", "DOCUMENT_CONTENT_MISSING");
            return;
        }
        ExtractionProvider provider = providers.stream().filter(candidate -> candidate.supports(document.contentType())).findFirst().orElse(null);
        if (provider == null) {
            fail(job, "No OCR/AI extraction provider is configured for " + document.contentType(), "PROCESSOR_UNAVAILABLE");
            return;
        }
        documentService.updateStatus(job.documentId(), DocumentStatus.PROCESSING);
        try {
            Map<String, Object> data = provider.extract(bytes, document.contentType(), job.documentType());
            UUID resultId = UUID.randomUUID();
            Instant now = Instant.now();
            ExtractionResult result = new ExtractionResult(resultId, job.id(), job.documentId(), job.organizationId(),
                    job.documentType(), job.outputFormat(), data, ReviewStatus.PENDING_REVIEW, 1,
                    extractionService.validateData(data), now, now, null);
            results.save(result);
            jobs.save(copy(job, JobStatus.COMPLETED, 100, job.attempt(), resultId, null));
            documentService.updateStatus(job.documentId(), DocumentStatus.COMPLETED);
        } catch (RuntimeException exception) {
            String code = exception instanceof com.dataextraction.saas.common.ApiException api ? api.getCode() : "EXTRACTION_FAILED";
            fail(job, exception.getMessage() == null ? "Extraction could not be completed" : exception.getMessage(), code,
                    exception.getClass().getName());
        }
    }

    private void fail(ExtractionJob job, String message, String code) {
        fail(job, message, code, null);
    }

    private void fail(ExtractionJob job, String message, String code, String exceptionType) {
        String safeMessage = message == null || message.isBlank() ? "Extraction failed" : message;
        if (safeMessage.length() > 1900) safeMessage = safeMessage.substring(0, 1900);
        String reason = code + ": " + safeMessage;
        jobs.save(copy(job, JobStatus.FAILED, 100, job.attempt(), null, reason));
        failures.record(new ExtractionFailure(UUID.randomUUID(), job.id(), job.documentId(), job.organizationId(), job.attempt(),
                code, safeMessage, exceptionType, Instant.now()));
        documentService.updateStatus(job.documentId(), DocumentStatus.FAILED, code, safeMessage, job.id(), job.attempt());
    }

    private ExtractionJob copy(ExtractionJob job, JobStatus status, int progress, int attempt, UUID resultId, String error) {
        return new ExtractionJob(job.id(), job.documentId(), job.organizationId(), job.documentType(), job.outputFormat(),
                status, progress, attempt, job.createdAt(), Instant.now(), resultId, error);
    }
}
