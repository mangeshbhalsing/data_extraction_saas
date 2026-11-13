package com.dataextraction.saas.extraction;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.dataextraction.saas.common.ApiException;

@Component
public class OfficeDocumentExtractionProvider implements ExtractionProvider {
    private static final String PDF = "application/pdf";
    private static final String DOC = "application/msword";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLS = "application/vnd.ms-excel";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Override public boolean supports(String contentType) {
        return List.of(PDF, DOC, DOCX, XLS, XLSX).contains(contentType.toLowerCase(java.util.Locale.ROOT));
    }

    @Override public Map<String, Object> extract(byte[] content, String contentType, String documentType) {
        try {
            return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
                case PDF -> extractPdf(content, documentType);
                case DOC -> extractDoc(content, documentType);
                case DOCX -> extractDocx(content, documentType);
                case XLS, XLSX -> extractExcel(content, documentType);
                default -> throw new IllegalStateException("Unsupported document content type");
            };
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_PARSE_FAILED",
                    "The document could not be read. Check that it is a valid, unencrypted file.");
        }
    }

    private Map<String, Object> extractPdf(byte[] content, String documentType) throws Exception {
        String text;
        try (var pdf = Loader.loadPDF(content)) { text = new PDFTextStripper().getText(pdf); }
        return DocumentAnalysis.build("PDF", documentType, text, parseTextTables(text));
    }

    private Map<String, Object> extractDoc(byte[] content, String documentType) throws Exception {
        try (var input = new HWPFDocument(new ByteArrayInputStream(content)); var extractor = new WordExtractor(input)) {
            return DocumentAnalysis.build("DOC", documentType, extractor.getText(), List.of());
        }
    }

    private Map<String, Object> extractDocx(byte[] content, String documentType) throws Exception {
        try (var input = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> text = new ArrayList<>();
            input.getParagraphs().stream().map(p -> p.getText()).filter(s -> !s.isBlank()).forEach(text::add);
            List<DocumentAnalysis.Table> tables = new ArrayList<>();
            int index = 1;
            for (var table : input.getTables()) {
                List<List<String>> rows = table.getRows().stream().map(row -> row.getTableCells().stream()
                        .map(cell -> cell.getText().trim()).toList()).filter(row -> row.stream().anyMatch(v -> !v.isBlank())).toList();
                DocumentAnalysis.Table parsed = toTable("Table " + index++, rows);
                if (parsed != null) tables.add(parsed);
                table.getRows().forEach(row -> row.getTableCells().forEach(cell -> text.add(cell.getText())));
            }
            return DocumentAnalysis.build("DOCX", documentType, String.join(". ", text), tables);
        }
    }

    private Map<String, Object> extractExcel(byte[] content, String documentType) throws Exception {
        List<DocumentAnalysis.Table> tables = new ArrayList<>();
        List<String> text = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (var sheet : workbook) {
                List<List<String>> rows = new ArrayList<>();
                for (var row : sheet) {
                    List<String> values = new ArrayList<>();
                    for (int col = 0; col < row.getLastCellNum(); col++) values.add(formatter.formatCellValue(row.getCell(col), evaluator).trim());
                    if (values.stream().anyMatch(value -> !value.isBlank())) { rows.add(values); text.add(String.join(" ", values)); }
                }
                DocumentAnalysis.Table table = toTable(sheet.getSheetName(), rows);
                if (table != null) tables.add(table);
            }
        }
        return DocumentAnalysis.build("EXCEL", documentType, String.join(". ", text), tables);
    }

    private List<DocumentAnalysis.Table> parseTextTables(String text) {
        List<List<String>> candidates = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.contains("\t")) candidates.add(List.of(trimmed.split("\\t", -1)));
            else if (trimmed.matches(".*\\S\\s{2,}\\S.*")) candidates.add(List.of(trimmed.split("\\s{2,}")));
        }
        if (candidates.size() < 2) return List.of();
        DocumentAnalysis.Table table = toTable("PDF table", candidates);
        return table == null ? List.of() : List.of(table);
    }

    private DocumentAnalysis.Table toTable(String name, List<List<String>> rows) {
        if (rows.size() < 2) return null;
        List<String> headers = new ArrayList<>();
        List<String> headerRow = rows.getFirst();
        for (int i = 0; i < headerRow.size(); i++) {
            String value = headerRow.get(i).trim();
            if (value.isBlank()) value = "Column " + (i + 1);
            String unique = value;
            int suffix = 2;
            while (headers.contains(unique)) unique = value + " " + suffix++;
            headers.add(unique);
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() != headers.size()) continue;
            Map<String, Object> record = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) record.put(headers.get(i), row.get(i).trim());
            records.add(record);
        }
        return records.isEmpty() ? null : new DocumentAnalysis.Table(name, List.copyOf(headers), List.copyOf(records));
    }
}
