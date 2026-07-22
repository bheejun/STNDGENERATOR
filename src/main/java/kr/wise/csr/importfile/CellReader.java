package kr.wise.csr.importfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

public final class CellReader {
    private final DataFormatter formatter = new DataFormatter(Locale.KOREA);
    private final FormulaEvaluator evaluator;

    public CellReader(Workbook workbook) {
        evaluator = workbook.getCreationHelper().createFormulaEvaluator();
    }

    public List<SourceRow> rows(Sheet sheet, String... requiredHeaders) {
        if (sheet == null) throw new IllegalArgumentException("필수 시트가 없습니다");
        Set<String> required = new LinkedHashSet<>();
        for (String header : requiredHeaders) required.add(key(header));

        int headerIndex = -1;
        Map<Integer, String> columns = Map.of();
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 9); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<Integer, String> found = new LinkedHashMap<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                String value = value(sheet, r, c);
                if (!value.isBlank()) found.put(c, key(value));
            }
            if (found.values().containsAll(required)) {
                headerIndex = r;
                columns = found;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new MissingHeadersException(sheet.getSheetName(), List.copyOf(required));
        }

        List<SourceRow> result = new ArrayList<>();
        for (int r = headerIndex + 1; r <= sheet.getLastRowNum(); r++) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean nonBlank = false;
            for (Map.Entry<Integer, String> column : columns.entrySet()) {
                String value = value(sheet, r, column.getKey());
                values.put(column.getValue(), value);
                nonBlank |= !value.isBlank();
            }
            if (nonBlank) result.add(new SourceRow(r + 1, values));
        }
        return result;
    }

    private String value(Sheet sheet, int rowIndex, int columnIndex) {
        Cell cell = null;
        Row row = sheet.getRow(rowIndex);
        if (row != null) cell = row.getCell(columnIndex);
        if (cell == null) {
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                if (region.isInRange(rowIndex, columnIndex)) {
                    Row firstRow = sheet.getRow(region.getFirstRow());
                    if (firstRow != null) cell = firstRow.getCell(region.getFirstColumn());
                    break;
                }
            }
        }
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).replace("\r\n", "\n").trim();
    }

    public static String key(String value) {
        return value == null ? "" : value.replace("(*)", "").replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT);
    }

    public record SourceRow(int rowNumber, Map<String, String> values) {
        public String get(String header) { return values.getOrDefault(key(header), ""); }
        public String first(String... headers) {
            for (String header : headers) {
                String value = get(header);
                if (!value.isBlank()) return value;
            }
            return "";
        }
    }

    public static class MissingHeadersException extends RuntimeException {
        public MissingHeadersException(String sheet, List<String> headers) {
            super(sheet + " 필수 헤더 누락: " + String.join(", ", headers));
        }
    }
}
