package com.dataextraction.saas.processing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.document.DocumentService;
import com.dataextraction.saas.document.DocumentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ExtractionJobService {
    private final DocumentService documents;
    private final ExtractionJobStore store;
    private final ExtractionWorker worker;

    public ExtractionJobService(DocumentService documents, ExtractionJobStore store) {
        this(documents, store, null);
    }

    @Autowired
    public ExtractionJobService(DocumentService documents, ExtractionJobStore store, ExtractionWorker worker) {
        this.documents = documents;
        this.store = store;
        this.worker = worker;
    }

    public synchronized JobResponse start(UUID documentId, String documentType, String outputFormat, String idempotencyKey) {
        var document = documents.get(documentId);
        if (document.status() != DocumentStatus.UPLOADED) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_UPLOADED", "Upload the document before starting extraction");
        }
        if (documentType.isBlank() || outputFormat.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EXTRACTION_REQUEST", "documentType and outputFormat are required");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            UUID previousId = store.findByIdempotencyKey(documentId + ":" + idempotencyKey);
            if (previousId != null) return response(store.find(previousId));
        }

        ExtractionJob job = new ExtractionJob(UUID.randomUUID(), documentId, document.organizationId(), documentType,
                outputFormat, JobStatus.QUEUED, 0, 1, Instant.now(), Instant.now(), null, null);
        store.save(job);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            store.rememberIdempotencyKey(documentId + ":" + idempotencyKey, job.id());
        }
        if (worker != null) worker.process(job.id());
        return response(job);
    }

    public JobResponse get(UUID jobId) {
        ExtractionJob job = store.find(jobId);
        if (job == null) throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Extraction job was not found");
        documents.get(job.documentId());
        return response(job);
    }

    public synchronized JobResponse retry(UUID jobId) {
        ExtractionJob current = store.find(jobId);
        if (current == null) throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Extraction job was not found");
        documents.get(current.documentId());
        if (current.status() != JobStatus.FAILED || current.attempt() >= 3) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_RETRYABLE", "Only failed jobs with fewer than three attempts can be retried");
        }
        ExtractionJob retried = new ExtractionJob(current.id(), current.documentId(), current.organizationId(),
                current.documentType(), current.outputFormat(), JobStatus.QUEUED, 0, current.attempt() + 1,
                current.createdAt(), Instant.now(), null, null);
        store.save(retried);
        if (worker != null) worker.process(retried.id());
        return response(retried);
    }

    public List<ExtractionJob> listForOrganization(UUID organizationId) {
        return store.all().stream().filter(job -> job.organizationId().equals(organizationId)).toList();
    }

    private JobResponse response(ExtractionJob job) {
        return new JobResponse(job.id(), job.status(), job.extractionId(), job.progress(), job.attempt(),
                job.errorMessage(), job.createdAt(), job.updatedAt(), "/api/v1/jobs/" + job.id());
    }

    public record JobResponse(UUID jobId, JobStatus status, UUID extractionId, int progress, int attempt,
                              String errorMessage, Instant createdAt, Instant updatedAt, String statusUrl) { }
}
