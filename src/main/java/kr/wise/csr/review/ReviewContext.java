package kr.wise.csr.review;

public record ReviewContext(String expectedSystemName, String expectedDbmsName, String expectedSchema) {
    public static ReviewContext empty() {
        return new ReviewContext("", "", "");
    }
}
