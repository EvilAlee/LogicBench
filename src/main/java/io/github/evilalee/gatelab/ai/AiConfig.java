package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads Gemini settings without ever exposing the API key in diagnostics. */
public final class AiConfig {
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    public enum KeySource { ENV, FILE, NONE }

    private final String apiKey;
    private final String model;
    private final List<String> fallbackModels;
    private final KeySource keySource;
    private final Path configDirectory;
    private final String baseUrl;

    private AiConfig(String apiKey, String model, List<String> fallbackModels,
                     KeySource keySource, Path configDirectory, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.fallbackModels = List.copyOf(fallbackModels);
        this.keySource = keySource;
        this.configDirectory = configDirectory;
        this.baseUrl = baseUrl;
    }

    public static AiConfig load() {
        return load(System.getenv(), defaultConfigDirectory(), DEFAULT_BASE_URL);
    }

    /** Injectable environment, directory and endpoint keep configuration tests offline. */
    public static AiConfig load(Map<String, String> environment, Path directory, String baseUrl) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(directory, "directory");
        String fileKey = null;
        String fileModel = null;
        List<String> fileFallbackModels = new ArrayList<>();
        Path settings = directory.resolve("ai.json");
        if (Files.isRegularFile(settings)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
                if (json.has("apiKey") && !json.get("apiKey").isJsonNull()) {
                    fileKey = nonBlank(json.get("apiKey").getAsString());
                }
                if (json.has("model") && !json.get("model").isJsonNull()) {
                    fileModel = nonBlank(json.get("model").getAsString());
                }
                if (json.has("fallbackModels") && json.get("fallbackModels").isJsonArray()) {
                    JsonArray models = json.getAsJsonArray("fallbackModels");
                    for (JsonElement item : models) {
                        if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                            String candidate = nonBlank(item.getAsString());
                            if (candidate != null) fileFallbackModels.add(candidate);
                        }
                    }
                }
            } catch (RuntimeException | IOException ignored) {
                // Invalid local settings are treated as absent; never echo file contents.
            }
        }
        String envKey = nonBlank(environment.get("GEMINI_API_KEY"));
        String key = envKey != null ? envKey : fileKey;
        KeySource source = envKey != null ? KeySource.ENV
                : fileKey != null ? KeySource.FILE : KeySource.NONE;
        String model = firstNonBlank(environment.get("GEMINI_MODEL"), fileModel, DEFAULT_MODEL);
        LinkedHashSet<String> uniqueFallbacks = new LinkedHashSet<>(fileFallbackModels);
        uniqueFallbacks.remove(model);
        return new AiConfig(key, model, List.copyOf(uniqueFallbacks),
                source, directory, stripTrailingSlash(baseUrl));
    }

    public static Path defaultConfigDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData == null || appData.isBlank()
                    ? System.getProperty("user.home") : appData, "LogicBench");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return Path.of(xdg == null || xdg.isBlank()
                ? System.getProperty("user.home") + "/.config" : xdg, "logicbench");
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String candidate = nonBlank(value);
            if (candidate != null) return candidate;
        }
        return DEFAULT_MODEL;
    }

    private static String stripTrailingSlash(String value) {
        String result = nonBlank(value);
        if (result == null) return DEFAULT_BASE_URL;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    /** Removes the configured API key from any text before it reaches logs or UI. */
    public String redact(String text) {
        if (text == null) return "";
        return apiKey == null || apiKey.isEmpty() ? text : text.replace(apiKey, "***");
    }

    public boolean hasApiKey() { return apiKey != null; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public List<String> fallbackModels() { return fallbackModels; }
    public List<String> models() {
        List<String> result = new ArrayList<>();
        result.add(model);
        result.addAll(fallbackModels);
        return List.copyOf(result);
    }
    public KeySource keySource() { return keySource; }
    public Path configDirectory() { return configDirectory; }
    public Path configFile() { return configDirectory.resolve("ai.json"); }
    public String baseUrl() { return baseUrl; }

    @Override
    public String toString() {
        return "AiConfig[apiKey=" + (apiKey == null ? "absent" : "****")
                + ", model=" + model + ", fallbackModels=" + fallbackModels
                + ", keySource=" + keySource
                + ", configDirectory=" + configDirectory + ", baseUrl=" + baseUrl + "]";
    }
}
