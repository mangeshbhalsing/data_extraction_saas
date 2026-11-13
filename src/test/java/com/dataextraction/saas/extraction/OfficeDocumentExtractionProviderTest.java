package com.dataextraction.saas.extraction;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import com.dataextraction.saas.common.ApiException;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeDocumentExtractionProviderTest {
    private final OfficeDocumentExtractionProvider provider = new OfficeDocumentExtractionProvider();

    @Test
    void parsesWorkbookRowsAndBuildsChartFromNumericData() throws Exception {
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sales");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("Month"); header.createCell(1).setCellValue("Revenue");
            var first = sheet.createRow(1); first.createCell(0).setCellValue("Jan"); first.createCell(1).setCellValue(120.0);
            var second = sheet.createRow(2); second.createCell(0).setCellValue("Feb"); second.createCell(1).setCellValue(150.0);
            workbook.write(out); bytes = out.toByteArray();
        }

        Map<String, Object> report = provider.extract(bytes,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "DOCUMENT_ANALYSIS");

        assertEquals("EXCEL", report.get("sourceType"));
        assertTrue(String.valueOf(report.get("summary")).contains("2 rows"));
        assertEquals(1, ((List<?>) report.get("tables")).size());
        assertEquals(1, ((List<?>) report.get("charts")).size());
        assertEquals(2, report.get("recordCount"));
    }

    @Test
    void parsesWordTableIntoStructuredRows() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var table = document.createTable(3, 2);
            table.getRow(0).getCell(0).setText("Item"); table.getRow(0).getCell(1).setText("Quantity");
            table.getRow(1).getCell(0).setText("Pens"); table.getRow(1).getCell(1).setText("12");
            table.getRow(2).getCell(0).setText("Books"); table.getRow(2).getCell(1).setText("8");
            document.write(out); bytes = out.toByteArray();
        }

        Map<String, Object> report = provider.extract(bytes,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCUMENT_ANALYSIS");

        assertEquals("DOCX", report.get("sourceType"));
        assertEquals(2, report.get("recordCount"));
        assertFalse(((List<?>) report.get("tables")).isEmpty());
        assertFalse(((List<?>) report.get("charts")).isEmpty());
    }

    @Test
    void reportsScannedPdfWithoutPretendingToReadIt() {
        ApiException error = assertThrows(ApiException.class, () -> DocumentAnalysis.build("PDF", "DOCUMENT_ANALYSIS", "", List.of()));
        assertEquals("NO_EXTRACTABLE_CONTENT", error.getCode());
    }
}
