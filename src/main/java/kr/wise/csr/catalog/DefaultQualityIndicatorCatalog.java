package kr.wise.csr.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
public class DefaultQualityIndicatorCatalog {
    private static final Map<String, String> BUILT_IN_IDS = Map.ofEntries(
            Map.entry("유효성진단", "OBJ_00000084167"),
            Map.entry("금액 도메인", "OBJ_00000084173"),
            Map.entry("날짜 도메인", "OBJ_00000084175"),
            Map.entry("코드 도메인", "OBJ_00000084176"),
            Map.entry("수량 도메인", "OBJ_00000084172"),
            Map.entry("여부 도메인", "OBJ_00000084171"),
            Map.entry("율 도메인", "OBJ_00000084174"),
            Map.entry("번호 도메인", "OBJ_00000084177"),
            Map.entry("구분 도메인", "OBJ_00000084177"),
            Map.entry("정합성진단", "OBJ_00000180004"),
            Map.entry("완결성진단", "OBJ_00000180005"),
            Map.entry("시간순서 일관성", "OBJ_00000084179"),
            Map.entry("계산식", "OBJ_00000084181"),
            Map.entry("선후관계 정확성", "OBJ_00000084184"),
            Map.entry("참조관계", "OBJ_00000085074"),
            Map.entry("논리관계 일관성", "OBJ_00000180008"),
            Map.entry("공백, 특수문자", "OB1_00000103014"),
            Map.entry("글자깨짐", "OBJ_00000180009"),
            Map.entry("필수값", "OBJ_00000180010"),
            Map.entry("중복데이터", "OBJ_00000180011"),
            Map.entry("업무규칙", "OBJ_00000103013"));

    private final JdbcTemplate jdbc;
    private volatile Map<String, String> ids;

    public DefaultQualityIndicatorCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findId(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(load().get(name.trim()));
    }

    public static Optional<String> builtInId(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(BUILT_IN_IDS.get(name.trim()));
    }

    public static boolean isBuiltIn(String name) {
        return builtInId(name).isPresent();
    }

    private Map<String, String> load() {
        Map<String, String> current = ids;
        if (current != null) return current;
        synchronized (this) {
            if (ids == null) {
                LinkedHashMap<String, String> loaded = new LinkedHashMap<>();
                jdbc.query("select quality_indicator_name, quality_indicator_id"
                                + " from default_quality_indicator where active=true",
                        (RowCallbackHandler) rs -> loaded.put(rs.getString(1), rs.getString(2)));
                ids = Map.copyOf(loaded);
            }
            return ids;
        }
    }
}
