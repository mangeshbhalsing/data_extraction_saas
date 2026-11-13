package com.dataextraction.saas.records;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dataextraction.saas.extraction.ExtractionResult;
import com.dataextraction.saas.extraction.ExtractionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/records")
public class RecordsController {
    private final ExtractionService extractions;
    public RecordsController(ExtractionService extractions) { this.extractions = extractions; }

    @GetMapping
    public Map<String, Object> search(@RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("page must be >= 0 and size must be 1 to 200");
        String query = q == null ? "" : q.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> rows = extractions.listForCurrentOrganization().stream()
                .filter(result -> "APPROVED".equals(result.reviewStatus().name()))
                .flatMap(result -> records(result).stream())
                .filter(row -> query.isEmpty() || row.toString().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        int from = Math.min(page * size, rows.size());
        int to = Math.min(from + size, rows.size());
        return Map.of("items", rows.subList(from, to), "page", page, "size", size, "totalElements", rows.size(),
                "totalPages", rows.isEmpty() ? 0 : (int) Math.ceil((double) rows.size() / size));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(ExtractionResult result) {
        Object value = result.data().get("records");
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
