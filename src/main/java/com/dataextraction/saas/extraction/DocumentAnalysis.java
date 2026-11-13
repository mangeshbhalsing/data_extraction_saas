package com.dataextraction.saas.extraction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds deterministic report content from values parsed directly from a document. */
public final class DocumentAnalysis {
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(?:[.,]\\d+)?$");
    private DocumentAnalysis() { }

    public record Table(String name, List<String> columns, List<Map<String, Object>> records) { }

    public static Map<String, Object> build(String sourceType, String documentType, String text, List<Table> tables) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank() && tables.stream().allMatch(table -> table.records().isEmpty())) {
            throw new com.dataextraction.saas.common.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "NO_EXTRACTABLE_CONTENT", "No readable text or tabular data was found in this document. Scanned PDFs require OCR.");
        }
        List<Map<String, Object>> tableData = tables.stream().map(table -> Map.<String, Object>of(
                "name", table.name(), "columns", table.columns(), "records", table.records(), "recordCount", table.records().size())).toList();
        List<Map<String, Object>> charts = new ArrayList<>();
        for (Table table : tables) {
            if (table.records().isEmpty() || table.columns().size() < 2) continue;
            String category = table.columns().getFirst();
            for (String numeric : table.columns().stream().skip(1).toList()) {
                List<String> labels = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (Map<String, Object> row : table.records()) {
                    Object raw = row.get(numeric);
                    if (raw == null || !NUMBER.matcher(raw.toString().trim()).matches()) continue;
                    labels.add(String.valueOf(row.getOrDefault(category, "Row " + (labels.size() + 1))));
                    try { values.add(new BigDecimal(raw.toString().trim().replace(',', '.')).doubleValue()); }
                    catch (NumberFormatException ignored) { /* skip values that cannot be represented */ }
                    if (labels.size() == 20) break;
                }
                if (values.size() >= 2) charts.add(Map.of("title", table.name() + " · " + numeric,
                        "type", "bar", "seriesLabel", numeric, "labels", labels.subList(0, values.size()), "values", values));
                if (charts.size() == 6) break;
            }
            if (charts.size() == 6) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", sourceType);
        result.put("documentType", documentType);
        result.put("summary", summarize(normalized, tables));
        result.put("wordCount", normalized.isBlank() ? 0 : normalized.split("\\s+").length);
        result.put("characterCount", normalized.length());
        result.put("tables", tableData);
        result.put("charts", charts);
        if (!tables.isEmpty()) {
            Table first = tables.getFirst();
            result.put("columns", first.columns());
            result.put("records", first.records());
            result.put("recordCount", tables.stream().mapToInt(table -> table.records().size()).sum());
        } else result.put("recordCount", 0);
        return result;
    }

    private static String summarize(String text, List<Table> tables) {
        List<String> sentences = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?])\\s+|\\R+")) {
            String trimmed = sentence.trim();
            if (trimmed.length() >= 25 && !sentences.contains(trimmed)) sentences.add(trimmed);
            if (sentences.size() == 3) break;
        }
        List<String> parts = new ArrayList<>(sentences);
        int rows = tables.stream().mapToInt(table -> table.records().size()).sum();
        if (rows > 0) {
            for (Table table : tables) {
                if (table.records().isEmpty()) continue;
                String columns = String.join(", ", table.columns().stream().limit(6).toList());
                parts.add(table.name() + " contains " + table.records().size() + " rows and " + table.columns().size()
                        + " columns (" + columns + (table.columns().size() > 6 ? ", …" : "") + ").");
                for (String column : table.columns().stream().skip(1).toList()) {
                    List<BigDecimal> numbers = table.records().stream().map(row -> row.get(column)).filter(java.util.Objects::nonNull)
                            .map(Object::toString).filter(value -> NUMBER.matcher(value.trim()).matches())
                            .map(value -> { try { return new BigDecimal(value.trim().replace(',', '.')); }
                                catch (NumberFormatException ignored) { return null; } })
                            .filter(java.util.Objects::nonNull).toList();
                    if (numbers.size() >= 2) {
                        BigDecimal min = numbers.stream().min(BigDecimal::compareTo).orElseThrow();
                        BigDecimal max = numbers.stream().max(BigDecimal::compareTo).orElseThrow();
                        parts.add(column + " ranges from " + min.stripTrailingZeros().toPlainString() + " to "
                                + max.stripTrailingZeros().toPlainString() + ".");
                    }
                }
                break;
            }
            if (tables.size() > 1) parts.add("The document contains " + rows + " data rows across " + tables.size() + " tables.");
        }
        if (parts.isEmpty()) parts.add("Extracted tabular content from the document.");
        return String.join(" ", parts);
    }
}
