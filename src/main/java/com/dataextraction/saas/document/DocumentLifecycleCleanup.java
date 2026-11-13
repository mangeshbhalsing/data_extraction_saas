package com.dataextraction.saas.document;

import java.util.UUID;

import com.dataextraction.saas.export.ExportStore;
import com.dataextraction.saas.extraction.ExtractionStore;
import com.dataextraction.saas.processing.ExtractionJobStore;
import com.dataextraction.saas.processing.ExtractionFailureStore;
import org.springframework.stereotype.Service;

@Service
public class DocumentLifecycleCleanup {
    private final ExtractionStore extractions;
    private final ExtractionJobStore jobs;
    private final ExtractionFailureStore failures;
    private final ExportStore exports;
    public DocumentLifecycleCleanup(ExtractionStore extractions, ExtractionJobStore jobs, ExtractionFailureStore failures, ExportStore exports) {
        this.extractions = extractions; this.jobs = jobs; this.failures = failures; this.exports = exports;
    }
    public void deleteRelatedData(UUID documentId) {
        extractions.deleteByDocument(documentId);
        failures.deleteByDocument(documentId);
        jobs.deleteByDocument(documentId);
        exports.deleteByDocument(documentId);
    }
}
