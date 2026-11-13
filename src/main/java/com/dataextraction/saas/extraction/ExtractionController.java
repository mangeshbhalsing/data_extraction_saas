package com.dataextraction.saas.extraction;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/extractions")
public class ExtractionController {
    private final ExtractionService extractions;
    public ExtractionController(ExtractionService extractions) { this.extractions = extractions; }

    @GetMapping("/{id}/results") public ExtractionResult get(@PathVariable UUID id) { return extractions.get(id); }
    @GetMapping public java.util.List<ExtractionResult> list() { return extractions.listForCurrentOrganization(); }
    @PatchMapping("/{id}/results") public ExtractionResult correct(@PathVariable UUID id, @RequestBody Map<String, Object> changes) {
        return extractions.saveCorrections(id, changes);
    }
    @PostMapping("/{id}/validate") public ExtractionResult validate(@PathVariable UUID id) { return extractions.validate(id); }
    @PostMapping("/{id}/approve") public ExtractionResult approve(@PathVariable UUID id) { return extractions.approve(id); }
}
