package com.dataextraction.saas.processing;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
public class ExtractionJobController {
    private final ExtractionJobService jobs;

    public ExtractionJobController(ExtractionJobService jobs) { this.jobs = jobs; }

    @GetMapping("/{jobId}")
    public ExtractionJobService.JobResponse getJob(@PathVariable UUID jobId) { return jobs.get(jobId); }

    @org.springframework.web.bind.annotation.PostMapping("/{jobId}/retry")
    public ExtractionJobService.JobResponse retry(@PathVariable UUID jobId) { return jobs.retry(jobId); }
}
