package kr.wise.csr.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultQualityIndicatorCatalogTest {
    @Test
    void mapsCanonicalNamesAndWdqAliasesToStableIds() {
        assertThat(DefaultQualityIndicatorCatalog.builtInId("수량 도메인"))
                .contains("OBJ_00000084172");
        assertThat(DefaultQualityIndicatorCatalog.builtInId("구분 도메인"))
                .contains("OBJ_00000084177");
        assertThat(DefaultQualityIndicatorCatalog.builtInId("업무규칙"))
                .contains("OBJ_00000103013");
    }
}
