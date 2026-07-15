package kr.wise.csr.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class WdqIdAllocatorTest {
    @Autowired WdqIdAllocator allocator;
    @Autowired JdbcTemplate jdbc;
    long system1;
    long system2;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from source_file");
        jdbc.update("delete from id_registry");
        jdbc.update("delete from id_sequence");
        jdbc.update("delete from build_project");
        jdbc.update("delete from standard_system");
        system1 = addSystem("S1");
        system2 = addSystem("S2");
    }

    @Test
    void formatsIdsForTheActualWdqColumnContracts() {
        assertThat(allocator.format(WdqIdType.VERIFICATION_RULE, 1)).isEqualTo("STNDRULE_0000001");
        assertThat(allocator.format(WdqIdType.CODE_RULE, 1)).isEqualTo("STNDCD_00000001");
        assertThat(allocator.format(WdqIdType.COLUMN_MAPPING, 1)).isEqualTo("STND_0000000001");
        assertThat(allocator.format(WdqIdType.BUSINESS_RULE, 1)).isEqualTo("STNDPRF_0000001");
    }

    @Test
    void reusesExistingLogicalObjectAcrossYears() {
        String first = allocator.allocate(WdqIdType.VERIFICATION_RULE, system1, "RULE-A", null);
        String nextYear = allocator.allocate(WdqIdType.VERIFICATION_RULE, system1, "RULE-A", null);
        assertThat(nextYear).isEqualTo(first);
    }

    @Test
    void rejectsAnExistingIdOwnedByAnotherSystem() {
        allocator.registerExisting(WdqIdType.CODE_RULE, system1, "CODE-A", "STNDCD_00000001", null);
        assertThatThrownBy(() -> allocator.registerExisting(
                WdqIdType.CODE_RULE, system2, "CODE-B", "STNDCD_00000001", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAllocationsAreUnique() throws Exception {
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(i -> (Callable<String>) () -> allocator.allocate(
                            WdqIdType.COLUMN_MAPPING, system1, "COLUMN-" + i, null))
                    .toList();
            List<String> ids = executor.invokeAll(tasks).stream().map(future -> {
                try { return future.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).toList();
            assertThat(new HashSet<>(ids)).hasSize(12);
        }
    }

    @Test
    void rejectsOverflowBeforeIdBecomesSixteenCharacters() {
        assertThatThrownBy(() -> allocator.format(WdqIdType.VERIFICATION_RULE, 10_000_000))
                .isInstanceOf(IllegalStateException.class);
    }

    private long addSystem(String code) {
        return jdbc.queryForObject("""
                insert into standard_system(system_code, system_name, dbms_type, dbms_physical_name,
                  default_schema_original, default_schema_normalized)
                values (?, ?, 'MARIADB', 'DB', 'PUBLIC', 'PUBLIC') returning id
                """, Long.class, code, code);
    }
}
