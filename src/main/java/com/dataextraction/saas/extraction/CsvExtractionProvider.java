package com.dataextraction.saas.extraction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dataextraction.saas.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CsvExtractionProvider implements ExtractionProvider {
    @Override public boolean supports(String contentType) { return "text/csv".equalsIgnoreCase(contentType); }

    @Override
    public Map<String, Object> extract(byte[] content, String contentType, String documentType) {
        List<List<String>> rows = parse(new String(content, StandardCharsets.UTF_8));
        if (rows.size() < 2 || rows.getFirst().isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_HAS_NO_DATA", "CSV must contain a header and at least one data row");
        }
        List<String> headers = rows.getFirst().stream().map(String::trim).toList();
        if (headers.stream().anyMatch(String::isBlank) || headers.stream().distinct().count() != headers.size()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_INVALID_HEADERS", "CSV headers must be non-empty and unique");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> values = rows.get(rowIndex);
            if (values.size() != headers.size()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_COLUMN_COUNT_MISMATCH",
                        "CSV row " + (rowIndex + 1) + " has " + values.size() + " values; expected " + headers.size());
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) record.put(headers.get(column), values.get(column));
            records.add(record);
        }
        return DocumentAnalysis.build("CSV", documentType, String.join(". ", rows.stream()
                .map(values -> String.join(" ", values)).toList()),
                List.of(new DocumentAnalysis.Table("CSV data", headers, records)));
    }

    private List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"' && field.isEmpty()) quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(List.copyOf(row));
                row.clear();
            } else field.append(c);
        }
        if (quoted) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_INVALID_QUOTE", "CSV contains an unclosed quoted field");
        if (!field.isEmpty() || !row.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
}
