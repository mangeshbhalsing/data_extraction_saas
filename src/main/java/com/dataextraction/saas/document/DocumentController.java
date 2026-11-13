package com.dataextraction.saas.document;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.LocalDate;

import com.dataextraction.saas.processing.ExtractionJobService;
import com.dataextraction.saas.security.AccountStore;
import com.dataextraction.saas.security.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documents;
    private final ExtractionJobService jobs;
    private final DocumentLifecycleCleanup cleanup;
    private final AccountStore accounts;

    public DocumentController(DocumentService documents, ExtractionJobService jobs, DocumentLifecycleCleanup cleanup, AccountStore accounts) {
        this.documents = documents;
        this.jobs = jobs;
        this.cleanup = cleanup;
        this.accounts = accounts;
    }

    @PostMapping
    public ResponseEntity<UploadSessionResponse> createUploadSession(@RequestBody CreateUploadSessionRequest request) {
        DocumentRecord document = documents.createUploadSession(request.filename(), request.contentType(), request.sizeBytes());
        String uploadUrl = "/api/v1/documents/" + document.id() + "/content";
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadSessionResponse(
                document.id(), uploadUrl, (int) (document.expiresAt().toEpochMilli() - document.createdAt().toEpochMilli()) / 1000));
    }

    @PutMapping("/{documentId}/content")
    public DocumentRecord uploadContent(@PathVariable UUID documentId,
                                        @RequestHeader("Content-Type") String contentType,
                                        @RequestBody byte[] content) {
        return documents.saveContent(documentId, contentType, content);
    }

    @PostMapping("/{documentId}/confirm-upload")
    public DocumentRecord confirmUpload(@PathVariable UUID documentId) { return documents.confirmUpload(documentId); }

    @GetMapping
    public DocumentPageView listDocuments(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(required = false) String filename,
                                          @RequestParam(required = false) DocumentStatus status,
                                          @RequestParam(required = false) LocalDate fromDate,
                                          @RequestParam(required = false) LocalDate toDate,
                                          @RequestParam(defaultValue = "date") String sortBy,
                                          @RequestParam(defaultValue = "desc") String direction) {
        Map<UUID, UserAccount> uploaders = accounts.all().stream().collect(Collectors.toMap(UserAccount::id, user -> user));
        DocumentService.DocumentPage result = documents.list(page, filename, status, fromDate, toDate, sortBy, direction);
        return new DocumentPageView(result.content().stream().map(document -> view(document, uploaders.get(document.uploadedBy()))).toList(),
                result.totalElements(), result.totalPages(), result.page(), result.size());
    }

    @GetMapping("/{documentId}")
    public DocumentView getDocument(@PathVariable UUID documentId) {
        DocumentRecord document = documents.get(documentId);
        return view(document, accounts.findById(document.uploadedBy()).orElse(null));
    }

    @GetMapping("/{documentId}/status")
    public Map<String, Object> getStatus(@PathVariable UUID documentId) {
        DocumentRecord document = documents.get(documentId);
        return Map.of("documentId", document.id(), "status", document.status());
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID documentId) {
        documents.delete(documentId);
        cleanup.deleteRelatedData(documentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{documentId}/extractions")
    public ResponseEntity<ExtractionJobService.JobResponse> startExtraction(
            @PathVariable UUID documentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) StartExtractionRequest request) {
        String documentType = request == null || request.documentType() == null ? "INVOICE" : request.documentType();
        String outputFormat = request == null || request.outputFormat() == null ? "STRUCTURED_JSON" : request.outputFormat();
        ExtractionJobService.JobResponse job = jobs.start(documentId, documentType, outputFormat, idempotencyKey);
        return ResponseEntity.accepted().body(job);
    }

    public record CreateUploadSessionRequest(String filename, String contentType, long sizeBytes) { }
    public record UploadSessionResponse(UUID documentId, String uploadUrl, int expiresInSeconds) { }
    public record StartExtractionRequest(String documentType, String outputFormat) { }
    public record DocumentPageView(List<DocumentView> content, long totalElements, int totalPages, int page, int size) { }

    private DocumentView view(DocumentRecord document, UserAccount uploader) {
        return new DocumentView(document.id(), document.organizationId(), document.uploadedBy(),
                uploader == null ? "Deleted account" : uploader.displayName(), uploader == null ? null : uploader.email(),
                document.filename(), document.contentType(), document.sizeBytes(), document.status(), document.createdAt(),
                document.expiresAt(), document.updatedAt(), document.failureReason(), document.failureCode(),
                document.failureJobId(), document.failureAttempt(), document.failedAt());
    }

    public record DocumentView(UUID id, UUID organizationId, UUID uploadedBy, String uploadedByName, String uploadedByEmail,
                               String filename, String contentType, long sizeBytes, DocumentStatus status,
                               java.time.Instant createdAt, java.time.Instant expiresAt, java.time.Instant updatedAt,
                               String failureReason, String failureCode, UUID failureJobId, Integer failureAttempt,
                               java.time.Instant failedAt) { }
}
