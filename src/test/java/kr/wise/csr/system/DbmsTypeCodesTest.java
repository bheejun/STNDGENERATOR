package kr.wise.csr.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class DbmsTypeCodesTest {
    @Test
    void normalizesNamesAndPreservesCodes() {
        assertThat(DbmsTypeCodes.normalize("Oracle")).isEqualTo("ORA");
        assertThat(DbmsTypeCodes.normalize("MariaDB")).isEqualTo("MRA");
        assertThat(DbmsTypeCodes.normalize("POS")).isEqualTo("POS");
        assertThatIllegalArgumentException().isThrownBy(() -> DbmsTypeCodes.normalize("unknown"));
    }
}
