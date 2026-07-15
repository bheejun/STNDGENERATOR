package kr.wise.csr.export;

public final class MariaDbLiteral {
    private MariaDbLiteral() {}
    public static String of(String value) {
        if (value == null) return "NULL";
        StringBuilder escaped = new StringBuilder(value.length() + 8).append('\'');
        for (int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if (ch < 0x20 && ch != '\r' && ch != '\n') throw new IllegalArgumentException("MariaDB 문자열에 허용되지 않는 제어문자가 있습니다");
            switch(ch) {
                case '\'' -> escaped.append("''");
                case '\\' -> escaped.append("\\\\");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                default -> escaped.append(ch);
            }
        }
        return escaped.append('\'').toString();
    }
}
