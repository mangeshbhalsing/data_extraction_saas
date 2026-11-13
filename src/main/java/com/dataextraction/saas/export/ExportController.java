package com.dataextraction.saas.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dataextraction.saas.common.ApiException;
import com.dataextraction.saas.extraction.ExtractionResult;
import com.dataextraction.saas.extraction.ExtractionService;
import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {
    private final ExtractionService extractions;
    private final ExportStore exports;
    public ExportController(ExtractionService extractions, ExportStore exports) { this.extractions = extractions; this.exports = exports; }

    @PostMapping
    public ResponseEntity<ExportResponse> create(@RequestBody(required = false) CreateExportRequest request,
                                                  @AuthenticationPrincipal AppUserPrincipal user) {
        String format = request == null || request.format() == null ? "CSV" : request.format().toUpperCase(java.util.Locale.ROOT);
        if (!"CSV".equals(format)) throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_EXPORT_FORMAT", "Only CSV exports are currently supported");
        List<ExtractionResult> results = extractions.listForCurrentOrganization().stream()
                .filter(result -> result.reviewStatus() == com.dataextraction.saas.extraction.ReviewStatus.APPROVED).toList();
        byte[] csv = createCsv(results).getBytes(StandardCharsets.UTF_8);
        var documentIds = results.stream().map(ExtractionResult::documentId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        ExportStore.ExportFile file = new ExportStore.ExportFile(UUID.randomUUID(), user.organizationId(), documentIds, "COMPLETED",
                "extractiq-export-" + Instant.now().toEpochMilli() + ".csv", csv, Instant.now());
        exports.save(file);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response(file));
    }

    @GetMapping("/{id}")
    public ExportResponse get(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        return response(owned(id, user));
    }

    @GetMapping
    public List<ExportResponse> list(@AuthenticationPrincipal AppUserPrincipal user) {
        return exports.all().stream().filter(file -> file.organizationId().equals(user.organizationId()))
                .map(this::response).toList();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @AuthenticationPrincipal AppUserPrincipal user) {
        ExportStore.ExportFile file = owned(id, user);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(file.content().clone());
    }

    private ExportStore.ExportFile owned(UUID id, AppUserPrincipal user) {
        ExportStore.ExportFile file = exports.find(id);
        if (file == null || !file.organizationId().equals(user.organizationId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Export was not found");
        }
        return file;
    }

    private ExportResponse response(ExportStore.ExportFile file) {
        return new ExportResponse(file.id(), file.status(), file.filename(), "/api/v1/exports/" + file.id(), file.createdAt());
    }

    private String createCsv(List<ExtractionResult> results) {
        StringBuilder csv = new StringBuilder("extraction_id,document_id,field,value\r\n");
        for (ExtractionResult result : results) {
            Object values = result.data().get("records");
            if (values instanceof List<?> rows) for (Object value : rows) if (value instanceof Map<?, ?> row) {
                row.forEach((key, fieldValue) -> csv.append(escape(result.id().toString())).append(',')
                        .append(escape(result.documentId().toString())).append(',').append(escape(String.valueOf(key)))
                        .append(',').append(escape(String.valueOf(fieldValue))).append("\r\n"));
            }
        }
        return csv.toString();
    }

    private String escape(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record CreateExportRequest(String format) { }
    public record ExportResponse(UUID exportId, String status, String filename, String statusUrl, Instant createdAt) { }
}
