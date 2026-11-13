package com.dataextraction.saas.document;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dataextraction.saas.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final DocumentStore store;
    private final long maxFileSizeBytes;
    private final int uploadSessionTtlSeconds;
    private final int maxDocumentsPerOrganization;
    private final long maxStorageBytesPerOrganization;

    public DocumentService(DocumentStore store, long maxFileSizeBytes, int uploadSessionTtlSeconds) {
        this(store, maxFileSizeBytes, uploadSessionTtlSeconds, 100, 1_073_741_824L);
    }

    @Autowired
    public DocumentService(DocumentStore store,
                           @Value("${app.upload.max-file-size-bytes:20971520}") long maxFileSizeBytes,
                           @Value("${app.upload.session-ttl-seconds:300}") int uploadSessionTtlSeconds,
                           @Value("${app.quotas.max-documents:100}") int maxDocumentsPerOrganization,
                           @Value("${app.quotas.max-storage-bytes:1073741824}") long maxStorageBytesPerOrganization) {
        this.store = store;
        this.maxFileSizeBytes = maxFileSizeBytes;
        this.uploadSessionTtlSeconds = uploadSessionTtlSeconds;
        this.maxDocumentsPerOrganization = maxDocumentsPerOrganization;
        this.maxStorageBytesPerOrganization = maxStorageBytesPerOrganization;
    }

    public DocumentRecord createUploadSession(String filename, String contentType, long sizeBytes) {
        AppUserPrincipal user = currentUser();
        var organizationDocuments = store.findAll().stream()
                .filter(document -> document.organizationId().equals(user.organizationId())).toList();
        if (organizationDocuments.size() >= maxDocumentsPerOrganization) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DOCUMENT_QUOTA_EXCEEDED", "Workspace document quota has been reached");
        }
        long allocatedBytes = organizationDocuments.stream().mapToLong(DocumentRecord::sizeBytes).sum();
        if (sizeBytes > 0 && allocatedBytes + sizeBytes > maxStorageBytesPerOrganization) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "STORAGE_QUOTA_EXCEEDED", "Workspace storage quota has been reached");
        }
        if (filename == null || filename.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "filename is required");
        }
        if (filename.length() > 255 || filename.contains("/") || filename.contains("\\")
                || filename.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "filename must be a plain name without path separators or control characters");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONTENT_TYPE", "contentType is required");
        }
        if (!isSupportedContentType(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "Supported content types are PDF, DOC, DOCX, XLS, XLSX, JPEG, PNG, and CSV");
        }
        if (sizeBytes <= 0 || sizeBytes > maxFileSizeBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE",
                    "sizeBytes must be between 1 and " + maxFileSizeBytes);
        }

        Instant now = Instant.now();
        DocumentRecord document = new DocumentRecord(UUID.randomUUID(), user.organizationId(), user.userId(), filename, contentType, sizeBytes,
                DocumentStatus.UPLOAD_PENDING, now, now.plusSeconds(uploadSessionTtlSeconds), now);
        store.save(document);
        return document;
    }

    public DocumentRecord saveContent(UUID id, String contentType, byte[] bytes) {
        DocumentRecord document = get(id);
        if (document.status() != DocumentStatus.UPLOAD_PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_PENDING", "Document is not awaiting upload");
        }
        if (document.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "UPLOAD_SESSION_EXPIRED", "Upload session has expired");
        }
        if (contentType == null || !document.contentType().equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE_MISMATCH",
                    "Uploaded content type does not match the upload session");
        }
        if (bytes != null && bytes.length > maxFileSizeBytes) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Uploaded file exceeds the configured size limit");
        }
        if (bytes == null || bytes.length != document.sizeBytes()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONTENT_SIZE_MISMATCH",
                    "Uploaded content size does not match sizeBytes");
        }
        validateFileSignature(document.contentType(), bytes);
        store.saveContent(id, bytes);
        DocumentRecord uploaded = new DocumentRecord(document.id(), document.organizationId(), document.uploadedBy(), document.filename(), document.contentType(),
                document.sizeBytes(), DocumentStatus.UPLOADED, document.createdAt(), document.expiresAt(), Instant.now());
        store.save(uploaded);
        return uploaded;
    }

    public DocumentRecord confirmUpload(UUID id) {
        DocumentRecord document = get(id);
        if (document.status() == DocumentStatus.UPLOADED) return document;
        if (document.status() != DocumentStatus.UPLOAD_PENDING || !store.hasContent(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_COMPLETE", "Upload the document bytes before confirming the upload");
        }
        throw new ApiException(HttpStatus.CONFLICT, "UPLOAD_NOT_COMPLETE", "The upload has not been finalized");
    }

    public DocumentRecord get(UUID id) {
        DocumentRecord document = store.find(id);
        AppUserPrincipal user = currentUser();
        if (document == null || !document.organizationId().equals(user.organizationId())
                || ("USER".equals(user.role()) && !document.uploadedBy().equals(user.userId()))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document was not found");
        }
        return document;
    }

    public List<DocumentRecord> list() {
        AppUserPrincipal user = currentUser();
        return store.findAll().stream().filter(document -> document.organizationId().equals(user.organizationId()))
                .filter(document -> !"USER".equals(user.role()) || document.uploadedBy().equals(user.userId()))
                .sorted(Comparator.comparing(DocumentRecord::createdAt).reversed()).toList();
    }

    public DocumentPage list(int page, String filename, DocumentStatus status, LocalDate fromDate,
                             LocalDate toDate, String sortBy, String direction) {
        if (page < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "page must be zero or greater");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "fromDate must be on or before toDate");
        }
        String sortField = sortBy == null || sortBy.isBlank() ? "date" : sortBy.trim().toLowerCase(java.util.Locale.ROOT);
        Comparator<DocumentRecord> comparator = switch (sortField) {
            case "date", "createdat" -> Comparator.comparing(DocumentRecord::createdAt);
            case "name", "filename" -> Comparator.comparing(DocumentRecord::filename, String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparing(document -> document.status().name());
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT_FIELD", "sortBy must be date, name, or status");
        };
        if ("desc".equalsIgnoreCase(direction)) comparator = comparator.reversed();
        else if (direction != null && !direction.isBlank() && !"asc".equalsIgnoreCase(direction)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT_DIRECTION", "direction must be asc or desc");
        }
        AppUserPrincipal user = currentUser();
        Instant from = fromDate == null ? null : fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = toDate == null ? null : toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        String term = filename == null ? "" : filename.trim().toLowerCase(java.util.Locale.ROOT);
        List<DocumentRecord> filtered = store.findAll().stream()
                .filter(document -> document.organizationId().equals(user.organizationId()))
                .filter(document -> !"USER".equals(user.role()) || document.uploadedBy().equals(user.userId()))
                .filter(document -> term.isEmpty() || document.filename().toLowerCase(java.util.Locale.ROOT).contains(term))
                .filter(document -> status == null || document.status() == status)
                .filter(document -> from == null || !document.createdAt().isBefore(from))
                .filter(document -> until == null || document.createdAt().isBefore(until))
                .sorted(comparator.thenComparing(DocumentRecord::id)).toList();
        int size = 50;
        long total = filtered.size();
        int start = (int) Math.min((long) page * size, total);
        List<DocumentRecord> content = filtered.subList(start, Math.min(start + size, filtered.size()));
        return new DocumentPage(content, total, (int) Math.ceil(total / (double) size), page, size);
    }

    public record DocumentPage(List<DocumentRecord> content, long totalElements, int totalPages, int page, int size) { }

    public void delete(UUID id) {
        get(id);
        store.delete(id);
    }

    public void updateStatus(UUID id, DocumentStatus status) {
        updateStatus(id, status, null, null, null, null);
    }

    public void updateStatus(UUID id, DocumentStatus status, String failureReason) {
        updateStatus(id, status, failureReason, null, null, null);
    }

    public void updateStatus(UUID id, DocumentStatus status, String failureCode, String failureReason, UUID failureJobId, Integer failureAttempt) {
        DocumentRecord document = store.find(id);
        if (document == null) return;
        Instant now = Instant.now();
        boolean failed = status == DocumentStatus.FAILED;
        store.save(new DocumentRecord(document.id(), document.organizationId(), document.uploadedBy(), document.filename(),
                document.contentType(), document.sizeBytes(), status, document.createdAt(), document.expiresAt(), now,
                failed ? failureReason : null, failed ? failureCode : null, failed ? failureJobId : null,
                failed ? failureAttempt : null, failed ? now : null));
    }

    private boolean isSupportedContentType(String contentType) {
        return List.of("application/pdf", "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "image/jpeg", "image/png", "text/csv")
                .contains(contentType.toLowerCase());
    }

    private void validateFileSignature(String contentType, byte[] bytes) {
        boolean valid = switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
            case "application/pdf" -> startsWith(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
            case "application/msword", "application/vnd.ms-excel" -> startsWith(bytes,
                    new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1});
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> startsWith(bytes, new byte[]{'P', 'K', 3, 4});
            case "image/png" -> startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "text/csv" -> java.util.Arrays.stream(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\\R", -1))
                    .noneMatch(line -> line.indexOf('\0') >= 0);
            default -> false;
        };
        if (!valid) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_CONTENT_MISMATCH",
                "The uploaded bytes do not match the declared file type");
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private AppUserPrincipal currentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AppUserPrincipal user) return user;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sign in is required");
    }
}
