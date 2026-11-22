package io.github.evilalee.gatelab.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Bounded, redacted provider-response diagnostics; never stores prompts or credentials. */
public final class AiDebugLog {
    public static final int MAX_ENTRY_BYTES = 20 * 1024;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;

    private final Path file;
    private final Path rotatedFile;
    private final UnaryOperator<String> redactor;

    public AiDebugLog(Path configDirectory, UnaryOperator<String> redactor) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        this.file = configDirectory.resolve("ai_debug.log");
        this.rotatedFile = configDirectory.resolve("ai_debug.log.1");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    public Path file() {
        return file;
    }

    public synchronized void append(String model, String rawAnswer) {
        String safeModel = redact(model == null ? "unknown" : model);
        String safeRaw = redact(rawAnswer == null ? "" : rawAnswer);
        String header = "[" + Instant.now() + "] model=" + safeModel + "\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] rawBytes = safeRaw.getBytes(StandardCharsets.UTF_8);
        int available = Math.max(0, MAX_ENTRY_BYTES - headerBytes.length - 1);
        if (rawBytes.length > available) rawBytes = utf8Prefix(rawBytes, available);
        byte[] separator = "\n\n".getBytes(StandardCharsets.UTF_8);
        int headerLimit = Math.max(0, MAX_ENTRY_BYTES - separator.length);
        if (headerBytes.length > headerLimit) {
            headerBytes = utf8Prefix(headerBytes, headerLimit);
        }
        int rawLimit = Math.max(0, MAX_ENTRY_BYTES - headerBytes.length - separator.length);
        if (rawBytes.length > rawLimit) rawBytes = utf8Prefix(rawBytes, rawLimit);
        byte[] entry = new byte[headerBytes.length + rawBytes.length + separator.length];
        System.arraycopy(headerBytes, 0, entry, 0, headerBytes.length);
        System.arraycopy(rawBytes, 0, entry, headerBytes.length, rawBytes.length);
        System.arraycopy(separator, 0, entry, headerBytes.length + rawBytes.length,
                separator.length);

        try {
            Files.createDirectories(file.getParent());
            long currentSize = Files.exists(file) ? Files.size(file) : 0L;
            if (currentSize + entry.length > MAX_FILE_BYTES) rotate();
            Files.write(file, entry, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Diagnostics must never turn an otherwise usable provider response into a failure.
        }
    }

    private String redact(String text) {
        String result = redactor.apply(text);
        return result == null ? "" : result;
    }

    private void rotate() throws IOException {
        Files.deleteIfExists(rotatedFile);
        if (Files.exists(file)) {
            Files.move(file, rotatedFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] utf8Prefix(byte[] bytes, int maximum) {
        int end = Math.min(bytes.length, maximum);
        while (end > 0 && end < bytes.length && (bytes[end] & 0xC0) == 0x80) end--;
        return java.util.Arrays.copyOf(bytes, end);
    }
}
