package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Persistent user cache plus the bundled, offline-safe demonstration answers. */
public final class AiCache {
    public enum Source { USER, BUNDLED }

    public record CacheHit(String answerJson, Source source, String model) {
        public CacheHit {
            Objects.requireNonNull(answerJson, "answerJson");
            Objects.requireNonNull(source, "source");
            model = model == null ? "" : model;
        }
        public CacheHit(String answerJson, Source source) { this(answerJson, source, ""); }
    }

    private record StoredAnswer(JsonObject answer, String model) {
        private StoredAnswer {
            Objects.requireNonNull(answer, "answer");
            model = model == null ? "" : model;
        }
    }

    private final Path file;
    private final Map<String, StoredAnswer> userEntries = new LinkedHashMap<>();
    private final Map<String, JsonObject> bundledEntries = new LinkedHashMap<>();

    public AiCache(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        this.file = configDirectory.resolve("ai_cache.json");
        loadBundled();
        loadUser();
    }

    public static String normalizeDescription(String description) {
        Objects.requireNonNull(description, "description");
        return description.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public Path file() {
        return file;
    }

    /** Returns available hits in strict priority order: user cache, then bundled cache. */
    public synchronized List<CacheHit> lookupCandidates(String description) {
        String key = normalizeDescription(description);
        List<CacheHit> hits = new ArrayList<>(2);
        StoredAnswer user = userEntries.get(key);
        if (user != null) hits.add(new CacheHit(user.answer().toString(), Source.USER, user.model()));
        JsonObject bundled = bundledEntries.get(key);
        if (bundled != null) hits.add(new CacheHit(bundled.toString(), Source.BUNDLED));
        return List.copyOf(hits);
    }

    public synchronized List<String> bundledDescriptions() {
        return List.copyOf(bundledEntries.keySet());
    }

    /** Stores only a response that the caller has already fully verified. */
    public synchronized void storeVerified(String description, String answerJson) throws IOException {
        storeVerified(description, answerJson, "");
    }

    /** Stores the producing model alongside the verified answer. */
    public synchronized void storeVerified(String description, String answerJson, String model)
            throws IOException {
        String key = normalizeDescription(description);
        JsonObject answer;
        try {
            answer = JsonParser.parseString(answerJson).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Verified AI answer could not be serialized for cache storage.");
        }
        userEntries.put(key, new StoredAnswer(answer.deepCopy(), model));
        writeUserCache();
    }

    private void loadBundled() {
        try (InputStream stream = AiCache.class.getResourceAsStream("/demo_prompts.json")) {
            if (stream == null) throw new IllegalStateException("Missing bundled demo_prompts.json resource.");
            JsonObject root = JsonParser.parseString(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray entries = root.getAsJsonArray("entries");
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                String key = normalizeDescription(entry.get("description").getAsString());
                JsonObject answer = entry.getAsJsonObject("answer");
                bundledEntries.put(key, answer.deepCopy());
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not load bundled AI demonstration answers.");
        }
    }

    private void loadUser() {
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject entries = root.getAsJsonObject("entries");
            if (entries == null) return;
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject stored = entry.getValue().getAsJsonObject();
                JsonObject answer = stored;
                String model = "";
                JsonElement version = stored.get("cacheVersion");
                if (version != null && version.isJsonPrimitive()
                        && version.getAsJsonPrimitive().isNumber()
                        && version.getAsInt() == 1
                        && stored.has("answer") && stored.get("answer").isJsonObject()) {
                    answer = stored.getAsJsonObject("answer");
                    JsonElement modelElement = stored.get("model");
                    if (modelElement != null && modelElement.isJsonPrimitive()
                            && modelElement.getAsJsonPrimitive().isString()) {
                        model = modelElement.getAsString();
                    }
                }
                userEntries.put(normalizeDescription(entry.getKey()),
                        new StoredAnswer(answer.deepCopy(), model));
            }
        } catch (IOException | RuntimeException ignored) {
            // Corrupt cache data is ignored; no content is echoed to diagnostics.
        }
    }

    private void writeUserCache() throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject entries = new JsonObject();
        userEntries.forEach((key, stored) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("cacheVersion", 1);
            entry.add("answer", stored.answer().deepCopy());
            if (!stored.model().isBlank()) entry.addProperty("model", stored.model());
            entries.add(key, entry);
        });
        JsonObject root = new JsonObject();
        root.add("entries", entries);
        Path temporary = Files.createTempFile(file.getParent(), "ai-cache-", ".tmp");
        try {
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
