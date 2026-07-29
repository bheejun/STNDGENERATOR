package kr.wise.csr.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.wise.csr.importfile.ProjectImportService.CriteriaCategory;
import kr.wise.csr.importfile.WorkbookDetector;
import kr.wise.csr.importfile.WorkbookType;

class CriteriaTemplateServiceTest {
    @TempDir Path directory;
    private final CriteriaTemplateService templates = new CriteriaTemplateService();
    private final WorkbookDetector detector = new WorkbookDetector();

    @Test
    void everyIndividualTemplateMatchesItsUploadCategory() throws Exception {
        Map<CriteriaCategory, WorkbookType> expected = Map.of(
                CriteriaCategory.TABLE_EXCLUSION, WorkbookType.CRITERIA_TABLE_EXCLUSION,
                CriteriaCategory.COLUMN_EXCLUSION, WorkbookType.CRITERIA_COLUMN_EXCLUSION,
                CriteriaCategory.VERIFICATION_RULE, WorkbookType.CRITERIA_VERIFICATION_RULE,
                CriteriaCategory.DOMAIN_MAPPING, WorkbookType.CRITERIA_DOMAIN_MAPPING,
                CriteriaCategory.BUSINESS_RULE, WorkbookType.CRITERIA_BUSINESS_RULE,
                CriteriaCategory.EXCLUSION_PATTERN, WorkbookType.CRITERIA_EXCLUSION_PATTERN,
                CriteriaCategory.CODE_RULE, WorkbookType.CRITERIA_CODE_RULE);

        for (var entry : expected.entrySet()) {
            GeneratedFile generated = templates.create(entry.getKey());
            Path file = directory.resolve(entry.getKey().name() + ".xlsx");
            Files.write(file, generated.content());
            assertThat(detector.detect(file)).isEqualTo(entry.getValue());
        }
    }
}
