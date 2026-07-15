package kr.wise.csr.export;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record GeneratedFile(String fileName, String mediaType, byte[] content, String sha256) {
    public GeneratedFile(String fileName, String mediaType, byte[] content) {
        this(fileName, mediaType, content.clone(), hash(content));
    }
    public GeneratedFile { content = content.clone(); }
    @Override public byte[] content() { return content.clone(); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
