package kr.wise.csr.importfile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectContextTest {
    private final ProjectContext context = new ProjectContext(1, 1, 2026, "202607",
            "DB", "SCH", "지킴-e", "[{year}표준시스템DB 지킴-e]");

    @Test
    void replacesLegacyYearPrefixInsteadOfStackingIt() {
        assertThat(context.prefixedReason("[2026년 표준시스템DB(지킴e)] 미사용 테이블 제외"))
                .isEqualTo("[2026표준시스템DB 지킴-e] 미사용 테이블 제외");
    }

    @Test
    void removesRepeatedLegacyPrefixes() {
        assertThat(context.prefixed("[2026표준시스템DB 지킴-e] [2026년 표준시스템DB(지킴e)] log 제외"))
                .isEqualTo("[2026표준시스템DB 지킴-e] log 제외");
    }
}
