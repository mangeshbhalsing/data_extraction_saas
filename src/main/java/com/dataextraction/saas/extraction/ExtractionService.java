package com.dataextraction.saas.extraction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.document.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ExtractionService {
    private final ExtractionStore store;
    private final DocumentService documents;

    public ExtractionService(ExtractionStore store, DocumentService documents) {
        this.store = store;
        this.documents = documents;
    }

    public ExtractionResult get(UUID id) {
        ExtractionResult result = store.find(id);
        if (result == null) throw notFound();
        documents.get(result.documentId()); // Enforce the authenticated organization boundary.
        return result;
    }

    public ExtractionResult byJob(UUID jobId) {
        ExtractionResult result = store.findByJob(jobId);
        if (result == null) throw notFound();
        return get(result.id());
    }

    public ExtractionResult saveCorrections(UUID id, Map<String, Object> changes) {
        ExtractionResult current = get(id);
        if (current.reviewStatus() == ReviewStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTRACTION_ALREADY_APPROVED", "Approved extraction results are read-only");
        }
        if (changes == null || changes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_CORRECTIONS", "Provide at least one field to update");
        }
        Map<String, Object> updated = new LinkedHashMap<>(current.data());
        changes.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 200) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD_NAME", "Field names must contain 1 to 200 characters");
            }
            updated.put(key, value);
        });
        ExtractionResult next = new ExtractionResult(current.id(), current.jobId(), current.documentId(),
                current.organizationId(), current.documentType(), current.outputFormat(), Map.copyOf(updated),
                ReviewStatus.PENDING_REVIEW, current.version() + 1, validate(updated), current.createdAt(), Instant.now(), null);
        store.save(next);
        return next;
    }

    public ExtractionResult validate(UUID id) {
        ExtractionResult current = get(id);
        ExtractionResult next = new ExtractionResult(current.id(), current.jobId(), current.documentId(),
                current.organizationId(), current.documentType(), current.outputFormat(), current.data(),
                current.reviewStatus(), current.version(), validate(current.data()), current.createdAt(), Instant.now(), current.approvedAt());
        store.save(next);
        return next;
    }

    public ExtractionResult approve(UUID id) {
        ExtractionResult validated = validate(id);
        if (!validated.validationErrors().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Correct validation issues before approval");
        }
        ExtractionResult approved = new ExtractionResult(validated.id(), validated.jobId(), validated.documentId(),
                validated.organizationId(), validated.documentType(), validated.outputFormat(), validated.data(),
                ReviewStatus.APPROVED, validated.version(), List.of(), validated.createdAt(), Instant.now(), Instant.now());
        store.save(approved);
        return approved;
    }

    public List<ExtractionResult> listForCurrentOrganization() {
        return store.all().stream().filter(result -> {
            try { documents.get(result.documentId()); return true; }
            catch (ApiException ignored) { return false; }
        }).toList();
    }

    public List<ValidationIssue> validateData(Map<String, Object> data) { return validate(data); }

    private List<ValidationIssue> validate(Map<String, Object> data) {
        List<ValidationIssue> issues = new ArrayList<>();
        Object columnsValue = data.get("columns");
        Object recordsValue = data.get("records");
        if (!(columnsValue instanceof List<?> columns) || columns.isEmpty()) {
            issues.add(new ValidationIssue("columns", "COLUMNS_REQUIRED", "At least one source column is required"));
        }
        if (!(recordsValue instanceof List<?> records) || records.isEmpty()) {
            issues.add(new ValidationIssue("records", "RECORDS_REQUIRED", "At least one data record is required"));
        }
        return List.copyOf(issues);
    }

    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "EXTRACTION_NOT_FOUND", "Extraction result was not found"); }
}
