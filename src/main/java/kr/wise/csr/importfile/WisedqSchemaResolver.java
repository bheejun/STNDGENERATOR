package kr.wise.csr.importfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

public final class WisedqSchemaResolver {
    private static final List<String> DETAIL_SHEETS = List.of(
            "도메인", "진단항목실행정보", "진단대상테이블", "업무규칙");

    private WisedqSchemaResolver() {
    }

    public static String resolve(Workbook workbook) {
        CellReader reader = new CellReader(workbook);
        Map<String, SchemaCount> schemas = new LinkedHashMap<>();
        for (String expected : DETAIL_SHEETS) {
            for (Sheet sheet : workbook) {
                if (!compact(sheet.getSheetName()).contains(compact(expected))) continue;
                try {
                    for (CellReader.SourceRow row : reader.rows(sheet, "스키마명")) {
                        String value = row.get("스키마명").trim();
                        if (value.isBlank()) continue;
                        String key = value;
                        schemas.compute(key, (ignored, current) -> current == null
                                ? new SchemaCount(value, 1) : new SchemaCount(current.value(), current.count() + 1));
                    }
                } catch (CellReader.MissingHeadersException ignored) {
                    // This similarly named sheet is not a detailed data sheet.
                }
            }
        }
        SchemaCount selected = null;
        for (SchemaCount candidate : schemas.values())
            if (selected == null || candidate.count() > selected.count()) selected = candidate;
        return selected == null ? "" : selected.value();
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("[\\s_()]+", "").toUpperCase(Locale.ROOT);
    }

    private record SchemaCount(String value, long count) {
    }
}
