package com.dataextraction.saas.analytics;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.dataextraction.saas.document.DocumentService;
import com.dataextraction.saas.document.DocumentStatus;
import com.dataextraction.saas.extraction.ExtractionService;
import com.dataextraction.saas.extraction.ReviewStatus;
import com.dataextraction.saas.processing.ExtractionJobService;
import com.dataextraction.saas.processing.JobStatus;
import com.dataextraction.saas.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final DocumentService documents;
    private final ExtractionJobService jobs;
    private final ExtractionService extractions;
    public AnalyticsController(DocumentService documents, ExtractionJobService jobs, ExtractionService extractions) {
        this.documents = documents; this.jobs = jobs; this.extractions = extractions;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        var docs = documents.list();
        var jobList = jobs.listForOrganization(orgId());
        var results = extractions.listForCurrentOrganization();
        long completed = jobList.stream().filter(job -> job.status() == JobStatus.COMPLETED).count();
        long failed = jobList.stream().filter(job -> job.status() == JobStatus.FAILED).count();
        return Map.of("documentCount", docs.size(), "jobCount", jobList.size(), "completedJobs", completed,
                "failedJobs", failed, "storageBytes", docs.stream().mapToLong(doc -> doc.sizeBytes()).sum(),
                "pendingReviewCount", results.stream().filter(result -> result.reviewStatus() == ReviewStatus.PENDING_REVIEW).count(),
                "approvedCount", results.stream().filter(result -> result.reviewStatus() == ReviewStatus.APPROVED).count());
    }

    @GetMapping("/charts")
    public Map<String, Object> charts() {
        Map<String, Long> documentStatuses = documents.list().stream().collect(Collectors.groupingBy(
                doc -> doc.status().name(), Collectors.counting()));
        Map<String, Long> jobStatuses = jobs.listForOrganization(orgId()).stream().collect(Collectors.groupingBy(
                job -> job.status().name(), Collectors.counting()));
        Map<String, Long> reviews = extractions.listForCurrentOrganization().stream().collect(Collectors.groupingBy(
                result -> result.reviewStatus().name(), Collectors.counting()));
        return Map.of("documentsByStatus", documentStatuses, "jobsByStatus", jobStatuses, "reviewsByStatus", reviews);
    }

    private UUID orgId() {
        return ((AppUserPrincipal) org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal()).organizationId();
    }
}
