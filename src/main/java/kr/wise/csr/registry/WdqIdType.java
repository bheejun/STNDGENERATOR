package kr.wise.csr.registry;

public enum WdqIdType {
    DB_CONNECTION("STNDDB_", 8, 15),
    EXCLUSION("STNDEXP_", 7, 15),
    VERIFICATION_RULE("STNDRULE_", 7, 20),
    CODE_RULE("STNDCD_", 8, 15),
    COLUMN_MAPPING("STND_", 10, 15),
    BUSINESS_RULE("STNDPRF_", 7, 15),
    SCHEMA("STNDSCH_", 7, 15),
    RULE_RELATION("STNDRR_", 8, 15),
    EXCLUSION_RULE("STNDEXR_", 7, 15);

    private final String prefix;
    private final int digits;
    private final int maximumLength;

    WdqIdType(String prefix, int digits, int maximumLength) {
        this.prefix = prefix;
        this.digits = digits;
        this.maximumLength = maximumLength;
    }

    public String prefix() { return prefix; }
    public int digits() { return digits; }
    public int maximumLength() { return maximumLength; }
    public long maximumSequence() {
        long maximum = 1;
        for (int i = 0; i < digits; i++) maximum *= 10;
        return maximum - 1;
    }
}
