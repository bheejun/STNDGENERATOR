package kr.wise.csr.importfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class WisedqReportMetadataExtractor {
    private static final String RESULT_SHEET = "(진단결과)값진단결과";
    private static final Pattern VERSION = Pattern.compile("(?i)WISE\\s*DQ.*?V(\\d+(?:\\.\\d+)*)");
    private static final Pattern OUTPUT_AT = Pattern.compile("출력일\\s*:\\s*(.+)");

    public WisedqReportMetadata extract(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return extract(input);
        } catch (IOException e) {
            throw new IllegalArgumentException("결과보고서 파일을 읽을 수 없습니다: " + path.getFileName(), e);
        }
    }

    public WisedqReportMetadata extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("결과보고서 파일이 필요합니다");
        try (InputStream input = file.getInputStream()) {
            return extract(input);
        } catch (IOException e) {
            throw new IllegalArgumentException("결과보고서 파일을 읽을 수 없습니다: " + file.getOriginalFilename(), e);
        }
    }

    private WisedqReportMetadata extract(InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet(RESULT_SHEET);
            if (sheet == null) throw new IllegalArgumentException("WISE DQ 결과보고서 시트를 찾을 수 없습니다: " + RESULT_SHEET);

            DataFormatter formatter = new DataFormatter(Locale.KOREA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, String> labels = new LinkedHashMap<>();
            String reportVersion = "";
            String outputAt = "";

            for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 14); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                for (int columnIndex = 0; columnIndex < row.getLastCellNum(); columnIndex++) {
                    String value = value(row, columnIndex, formatter, evaluator);
                    if (value.isBlank()) continue;
                    Matcher versionMatcher = VERSION.matcher(value);
                    if (versionMatcher.find()) reportVersion = versionMatcher.group(1);
                    Matcher outputMatcher = OUTPUT_AT.matcher(value);
                    if (outputMatcher.find()) outputAt = outputMatcher.group(1).trim();
                    String label = CellReader.key(value);
                    if (isMetadataLabel(label)) labels.putIfAbsent(label, nextValue(row, columnIndex + 1, formatter, evaluator));
                }
            }

            String summarySchema = labels.getOrDefault(CellReader.key("DBMS서비스(스키마)명"), "");
            String detailSchema = WisedqSchemaResolver.resolve(workbook);
            String actualSchema = detailSchema.isBlank() ? summarySchema : detailSchema;
            if (actualSchema.isBlank())
                throw new IllegalArgumentException("결과보고서 상세 시트에서 실제 스키마명을 찾을 수 없습니다");

            WisedqReportMetadata metadata = new WisedqReportMetadata(
                    labels.getOrDefault(CellReader.key("기관명"), ""),
                    required(labels, "정보시스템명"),
                    required(labels, "DBMS명"),
                    required(labels, "DBMS종류"),
                    actualSchema,
                    labels.getOrDefault(CellReader.key("DBMS버전"), ""),
                    labels.getOrDefault(CellReader.key("IP"), ""),
                    labels.getOrDefault(CellReader.key("Port"), ""),
                    reportVersion,
                    outputAt);
            if (metadata.reportVersion().isBlank()) throw new IllegalArgumentException("WISE DQ 보고서 버전을 찾을 수 없습니다");
            return metadata;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("WISE DQ 결과보고서 메타정보 추출에 실패했습니다: " + e.getMessage(), e);
        }
    }

    private boolean isMetadataLabel(String value) {
        return value.equals(CellReader.key("기관명")) || value.equals(CellReader.key("정보시스템명"))
                || value.equals(CellReader.key("DBMS명")) || value.equals(CellReader.key("DBMS종류"))
                || value.equals(CellReader.key("DBMS서비스(스키마)명")) || value.equals(CellReader.key("DBMS버전"))
                || value.equals(CellReader.key("IP")) || value.equals(CellReader.key("Port"));
    }

    private String required(Map<String, String> labels, String name) {
        String value = labels.getOrDefault(CellReader.key(name), "");
        if (value.isBlank()) throw new IllegalArgumentException("결과보고서에서 필수 항목을 찾을 수 없습니다: " + name);
        return value;
    }

    private String nextValue(Row row, int start, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int columnIndex = start; columnIndex < row.getLastCellNum(); columnIndex++) {
            String value = value(row, columnIndex, formatter, evaluator);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private String value(Row row, int columnIndex, DataFormatter formatter, FormulaEvaluator evaluator) {
        var cell = row.getCell(columnIndex);
        return cell == null ? "" : formatter.formatCellValue(cell, evaluator).replace("\r\n", "\n").trim();
    }
}
