package com.dataextraction.saas.usage;

import java.util.Map;

import com.dataextraction.saas.document.DocumentService;
import com.dataextraction.saas.processing.ExtractionJobService;
import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {
    private final DocumentService documents;
    private final ExtractionJobService jobs;
    public UsageController(DocumentService documents, ExtractionJobService jobs) { this.documents = documents; this.jobs = jobs; }

    @GetMapping
    public Map<String, Object> usage(@AuthenticationPrincipal AppUserPrincipal user) {
        var docs = documents.list();
        return Map.of("documents", docs.size(), "documentLimit", 100, "storageBytes",
                docs.stream().mapToLong(document -> document.sizeBytes()).sum(), "storageLimitBytes", 1073741824L,
                "extractionJobs", jobs.listForOrganization(user.organizationId()).size(), "period", "all-time (local H2 workspace)");
    }
}
