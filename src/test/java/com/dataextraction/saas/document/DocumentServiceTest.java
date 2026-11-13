package com.dataextraction.saas.document;

import com.dataextraction.saas.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentServiceTest {
    private DocumentStore store;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        com.dataextraction.saas.TestIdentity.install();
        store = new DocumentStore();
        service = new DocumentService(store, 20, 300);
    }

    @org.junit.jupiter.api.AfterEach void clearIdentity() { com.dataextraction.saas.TestIdentity.clear(); }

    @Test
    void createsUploadSessionForSupportedFile() {
        DocumentRecord document = service.createUploadSession("invoice.pdf", "application/pdf", 5);

        assertEquals(DocumentStatus.UPLOAD_PENDING, document.status());
        assertEquals(5, document.sizeBytes());
        assertNotNull(store.find(document.id()));
    }

    @Test
    void rejectsMissingFilename() {
        assertApiError(() -> service.createUploadSession("  ", "application/pdf", 5),
                HttpStatus.BAD_REQUEST, "INVALID_FILENAME");
    }

    @Test
    void rejectsPathTraversalNames() {
        assertApiError(() -> service.createUploadSession("..\\secret.pdf", "application/pdf", 5),
                HttpStatus.BAD_REQUEST, "INVALID_FILENAME");
    }

    @Test
    void rejectsSpoofedPdfBytes() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);
        assertApiError(() -> service.saveContent(pending.id(), "application/pdf", "hello".getBytes()),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_CONTENT_MISMATCH");
    }

    @Test
    void rejectsMissingContentType() {
        assertApiError(() -> service.createUploadSession("invoice.pdf", "", 5),
                HttpStatus.BAD_REQUEST, "INVALID_CONTENT_TYPE");
    }

    @Test
    void acceptsSupportedOfficeContentTypes() {
        assertEquals(DocumentStatus.UPLOAD_PENDING, service.createUploadSession("report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 5).status());
        assertEquals(DocumentStatus.UPLOAD_PENDING, service.createUploadSession("report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 5).status());
        assertEquals(DocumentStatus.UPLOAD_PENDING, service.createUploadSession("report.doc",
                "application/msword", 5).status());
        assertEquals(DocumentStatus.UPLOAD_PENDING, service.createUploadSession("report.xls",
                "application/vnd.ms-excel", 5).status());
    }

    @Test
    void rejectsZeroAndOversizedMetadata() {
        assertApiError(() -> service.createUploadSession("invoice.pdf", "application/pdf", 0),
                HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE");
        assertApiError(() -> service.createUploadSession("invoice.pdf", "application/pdf", 21),
                HttpStatus.BAD_REQUEST, "INVALID_FILE_SIZE");
    }

    @Test
    void marksDocumentUploadedAfterMatchingContentIsSaved() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);

        DocumentRecord uploaded = service.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes());

        assertEquals(DocumentStatus.UPLOADED, uploaded.status());
        assertEquals(1, service.list().size());
    }

    @Test
    void rejectsContentTypeMismatch() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);

        assertApiError(() -> service.saveContent(pending.id(), "image/png", new byte[5]),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE_MISMATCH");
    }

    @Test
    void rejectsContentLengthMismatch() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);

        assertApiError(() -> service.saveContent(pending.id(), "application/pdf", new byte[4]),
                HttpStatus.BAD_REQUEST, "CONTENT_SIZE_MISMATCH");
    }

    @Test
    void rejectsUploadedContentLargerThanConfiguredLimit() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 20);

        assertApiError(() -> service.saveContent(pending.id(), "application/pdf", new byte[21]),
                HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
    }

    @Test
    void rejectsExpiredUploadSession() throws InterruptedException {
        DocumentService expiringService = new DocumentService(new DocumentStore(), 20, 0);
        DocumentRecord pending = expiringService.createUploadSession("invoice.pdf", "application/pdf", 5);
        Thread.sleep(10);

        assertApiError(() -> expiringService.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes()),
                HttpStatus.GONE, "UPLOAD_SESSION_EXPIRED");
    }

    @Test
    void rejectsSecondUploadAfterCompletion() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);
        service.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes());

        assertApiError(() -> service.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes()),
                HttpStatus.CONFLICT, "UPLOAD_NOT_PENDING");
    }

    @Test
    void returnsNotFoundForUnknownDocumentAndDelete() {
        var id = java.util.UUID.randomUUID();
        assertApiError(() -> service.get(id), HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
        assertApiError(() -> service.delete(id), HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND");
    }

    @Test
    void deletesDocumentAndContentMetadata() {
        DocumentRecord pending = service.createUploadSession("invoice.pdf", "application/pdf", 5);
        service.saveContent(pending.id(), "application/pdf", "%PDF-".getBytes());

        service.delete(pending.id());

        assertEquals(0, service.list().size());
        assertEquals(null, store.find(pending.id()));
        assertFalse(store.hasContent(pending.id()));
    }

    private void assertApiError(Runnable action, HttpStatus status, String code) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status, exception.getStatus());
        assertEquals(code, exception.getCode());
    }
}
