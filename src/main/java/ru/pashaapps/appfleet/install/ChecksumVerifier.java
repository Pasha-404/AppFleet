package ru.pashaapps.appfleet.install;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChecksumVerifier {
    private static final Pattern SHA256 = Pattern.compile("^([A-Fa-f0-9]{64})\\s{2,}(.+)$");
    public String parseSha256Asset(Path checksumFile, String expectedFileName) throws IOException {
        String line = Files.readString(checksumFile, StandardCharsets.UTF_8).strip();
        Matcher matcher = SHA256.matcher(line);
        if (!matcher.matches() || !expectedFileName.equals(matcher.group(2))) throw new IOException("Некорректный SHA-256-файл релиза");
        return matcher.group(1).toLowerCase();
    }
    public boolean matches(Path file, String expectedHash) throws IOException {
        try (var input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            return MessageDigest.isEqual(HexFormat.of().formatHex(digest.digest()).getBytes(StandardCharsets.US_ASCII), expectedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException absent) { throw new IllegalStateException("В Java отсутствует SHA-256", absent); }
    }
}

