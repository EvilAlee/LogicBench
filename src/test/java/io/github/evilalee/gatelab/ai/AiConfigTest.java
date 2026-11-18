package io.github.evilalee.gatelab.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiConfigTest {
    @TempDir Path directory;

    @Test
    void environmentKeyAndModelOverrideFileValues() throws Exception {
        Files.writeString(directory.resolve("ai.json"),
                "{\"apiKey\":\"file-secret\",\"model\":\"file-model\"}");
        AiConfig config = AiConfig.load(
                Map.of("GEMINI_API_KEY", "env-secret", "GEMINI_MODEL", "env-model"),
                directory, "http://localhost:8080/");
        assertEquals("env-secret", config.apiKey());
        assertEquals("env-model", config.model());
        assertEquals(AiConfig.KeySource.ENV, config.keySource());
        assertFalse(config.toString().contains("env-secret"));
        assertFalse(config.toString().contains("file-secret"));
        assertEquals("http://localhost:8080", config.baseUrl());
    }

    @Test
    void fileValuesAndDefaultsAreUsedWhenEnvironmentIsAbsent() throws Exception {
        Files.writeString(directory.resolve("ai.json"),
                "{\"apiKey\":\"file-secret\",\"model\":\"file-model\"}");
        AiConfig config = AiConfig.load(Map.of(), directory, null);
        assertEquals("file-secret", config.apiKey());
        assertEquals("file-model", config.model());
        assertEquals(AiConfig.KeySource.FILE, config.keySource());
        assertFalse(config.toString().contains("file-secret"));
    }

    @Test
    void redactRemovesConfiguredApiKeyFromMessages() {
        AiConfig config = AiConfig.load(Map.of("GEMINI_API_KEY", "sensitive-key"),
                directory, AiConfig.DEFAULT_BASE_URL);
        assertEquals("provider returned ***; *** was rejected",
                config.redact("provider returned sensitive-key; sensitive-key was rejected"));
    }

    @Test
    void requestTimeoutIsTwentySeconds() {
        assertEquals(java.time.Duration.ofSeconds(20), AiConfig.REQUEST_TIMEOUT);
    }

    @Test
    void defaultsAndMissingKeyAreSafe() {
        AiConfig config = AiConfig.load(Map.of(), directory, AiConfig.DEFAULT_BASE_URL);
        assertFalse(config.hasApiKey());
        assertEquals(AiConfig.DEFAULT_MODEL, config.model());
        assertEquals(AiConfig.KeySource.NONE, config.keySource());
        assertEquals(directory.resolve("ai.json"), config.configFile());
        assertTrue(config.toString().contains("apiKey=absent"));
    }

    @Test
    void malformedSettingsAreIgnoredWithoutLeakingContents() throws Exception {
        Files.writeString(directory.resolve("ai.json"), "not-json-secret");
        AiConfig config = AiConfig.load(Map.of(), directory, AiConfig.DEFAULT_BASE_URL);
        assertFalse(config.hasApiKey());
        assertFalse(config.toString().contains("not-json-secret"));
    }
}
