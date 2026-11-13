package com.dataextraction.saas.processing;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.document.DocumentService;
import com.dataextraction.saas.document.DocumentStatus;
import com.dataextraction.saas.document.DocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractionJobServiceTest {
    private DocumentService documents;
    private ExtractionJobService jobs;

    @BeforeEach
    void setUp() {
        com.dataextraction.saas.TestIdentity.install();
        documents = new DocumentService(new DocumentStore(), 100, 300);
        jobs = new ExtractionJobService(documents, new ExtractionJobStore());
    }

    @org.junit.jupiter.api.AfterEach void clearIdentity() { com.dataextraction.saas.TestIdentity.clear(); }

    @Test
    void queuesJobForUploadedDocument() {
        var document = uploadedDocument();

        var job = jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", null);

        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals(0, job.progress());
        assertEquals("/api/v1/jobs/" + job.jobId(), job.statusUrl());
    }

    @Test
    void rejectsJobWhenDocumentHasNotBeenUploaded() {
        var document = documents.createUploadSession("invoice.pdf", "application/pdf", 5);

        ApiException exception = assertThrows(ApiException.class,
                () -> jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", null));

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
        assertEquals("DOCUMENT_NOT_UPLOADED", exception.getCode());
    }

    @Test
    void rejectsBlankDocumentTypeOrOutputFormat() {
        var document = uploadedDocument();

        assertJobRequestError(() -> jobs.start(document.id(), " ", "STRUCTURED_JSON", null));
        assertJobRequestError(() -> jobs.start(document.id(), "INVOICE", " ", null));
    }

    @Test
    void returnsSameJobForRepeatedIdempotencyKeyOnOneDocument() {
        var document = uploadedDocument();

        var first = jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", "request-1");
        var retry = jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", "request-1");

        assertEquals(first.jobId(), retry.jobId());
    }

    @Test
    void createsDifferentJobsForDifferentIdempotencyKeys() {
        var document = uploadedDocument();

        var first = jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", "request-1");
        var second = jobs.start(document.id(), "INVOICE", "STRUCTURED_JSON", "request-2");

        assertNotEquals(first.jobId(), second.jobId());
    }

    @Test
    void returnsNotFoundForUnknownJob() {
        ApiException exception = assertThrows(ApiException.class,
                () -> jobs.get(java.util.UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("JOB_NOT_FOUND", exception.getCode());
    }

    private com.dataextraction.saas.document.DocumentRecord uploadedDocument() {
        var pending = documents.createUploadSession("invoice.pdf", "application/pdf", 5);
        var uploaded = documents.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes());
        assertEquals(DocumentStatus.UPLOADED, uploaded.status());
        return uploaded;
    }

    private void assertJobRequestError(Runnable request) {
        ApiException exception = assertThrows(ApiException.class, request::run);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertEquals("INVALID_EXTRACTION_REQUEST", exception.getCode());
    }
}
