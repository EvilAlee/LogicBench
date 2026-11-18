package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Minimal Gemini generateContent client using only Java HttpClient and Gson. */
public final class GeminiProvider implements AiProvider {
    private final AiConfig config;
    private final HttpClient client;
    private final Duration requestTimeout;

    public GeminiProvider(AiConfig config) {
        this(config, AiConfig.REQUEST_TIMEOUT);
    }

    /** Timeout override is useful for deterministic local HTTP-server tests. */
    public GeminiProvider(AiConfig config, Duration requestTimeout) {
        this.config = Objects.requireNonNull(config, "config");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.client = HttpClient.newBuilder()
                .connectTimeout(AiConfig.CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String complete(String systemPrompt, List<Turn> conversation, JsonObject schema)
            throws IOException, InterruptedException {
        if (!config.hasApiKey()) {
            throw new IOException("No Gemini API key configured.");
        }
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(schema, "schema");

        JsonObject response = sendWithSchemaFallback(systemPrompt, conversation, schema);
        return extractText(response);
    }

    private JsonObject sendWithSchemaFallback(
            String systemPrompt, List<Turn> conversation, JsonObject schema)
            throws IOException, InterruptedException {
        JsonObject first = requestBody(systemPrompt, conversation, schema, 0);
        HttpResponse<String> response = sendWithRateLimitRetry(first);
        if (response.statusCode() == 400 && mentionsResponseFormat(response.body())) {
            JsonObject older = requestBody(systemPrompt, conversation, schema, 1);
            response = sendWithRateLimitRetry(older);
            if (response.statusCode() == 400) {
                JsonObject withoutSchema = requestBody(systemPrompt, conversation, null, 2);
                response = sendWithRateLimitRetry(withoutSchema);
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mappedError(response.statusCode());
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Gemini returned an invalid response.");
        }
    }

    private HttpResponse<String> sendWithRateLimitRetry(JsonObject body)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(body);
        if (response.statusCode() == 429 || response.statusCode() == 503) {
            Thread.sleep(2_000);
            response = send(body);
        }
        return response;
    }

    private HttpResponse<String> send(JsonObject body)
            throws IOException, InterruptedException {
        URI uri = URI.create(config.baseUrl() + "/v1beta/models/"
                + config.model() + ":generateContent");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("x-goog-api-key", config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException exception) {
            throw new IOException("No network connection to Gemini.");
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new IOException("Gemini request timed out.");
        } catch (IOException exception) {
            throw new IOException("No network connection to Gemini.");
        }
    }

    private static JsonObject requestBody(
            String systemPrompt, List<Turn> conversation, JsonObject schema, int schemaMode) {
        JsonObject root = new JsonObject();
        JsonObject instruction = new JsonObject();
        JsonArray instructionParts = new JsonArray();
        JsonObject instructionText = new JsonObject();
        instructionText.addProperty("text", systemPrompt);
        instructionParts.add(instructionText);
        instruction.add("parts", instructionParts);
        root.add("systemInstruction", instruction);

        JsonArray contents = new JsonArray();
        for (Turn turn : conversation) {
            JsonObject content = new JsonObject();
            content.addProperty("role", turn.geminiRole());
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", turn.text());
            parts.add(part);
            content.add("parts", parts);
            contents.add(content);
        }
        root.add("contents", contents);

        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", 8192);
        if (schema != null) {
            if (schemaMode == 0) {
                JsonObject responseFormat = new JsonObject();
                JsonObject text = new JsonObject();
                text.addProperty("mimeType", "APPLICATION_JSON");
                text.add("schema", schema.deepCopy());
                responseFormat.add("text", text);
                generation.add("responseFormat", responseFormat);
            } else if (schemaMode == 1) {
                generation.addProperty("responseMimeType", "application/json");
                generation.add("responseJsonSchema", schema.deepCopy());
            }
        }
        root.add("generationConfig", generation);
        return root;
    }

    private static boolean mentionsResponseFormat(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("responseformat");
    }

    private static IOException mappedError(int status) {
        return switch (status) {
            case 400 -> new IOException("Gemini rejected the request (HTTP 400).");
            case 401, 403 -> new IOException("Gemini API key rejected (HTTP " + status + ").");
            case 404 -> new IOException("Gemini model not found; check the model name (HTTP 404).");
            case 429 -> new IOException("Gemini rate limit or quota reached (HTTP 429).");
            default -> status >= 500
                    ? new IOException("Gemini server error (HTTP " + status + ").")
                    : new IOException("Gemini request failed (HTTP " + status + ").");
        };
    }

    private static String extractText(JsonObject response) throws IOException {
        JsonObject feedback = objectOrNull(response.get("promptFeedback"));
        if (feedback != null && feedback.has("blockReason")
                && !feedback.get("blockReason").isJsonNull()) {
            throw new IOException("Gemini blocked the prompt: "
                    + safeEnum(feedback.get("blockReason")));
        }
        JsonArray candidates = arrayOrNull(response.get("candidates"));
        if (candidates == null || candidates.isEmpty()) {
            throw new IOException("Gemini returned no candidates.");
        }
        JsonObject candidate = objectOrNull(candidates.get(0));
        if (candidate == null) {
            throw new IOException("Gemini returned an invalid candidate.");
        }
        String finishReason = candidate.has("finishReason")
                ? safeEnum(candidate.get("finishReason")) : "";
        if (!"STOP".equals(finishReason)) {
            throw new IOException("Gemini response did not finish normally: "
                    + (finishReason.isBlank() ? "unknown reason" : finishReason) + ".");
        }
        JsonObject content = objectOrNull(candidate.get("content"));
        JsonArray parts = content == null ? null : arrayOrNull(content.get("parts"));
        if (parts == null) {
            throw new IOException("Gemini returned no response text.");
        }
        StringBuilder result = new StringBuilder();
        for (JsonElement element : parts) {
            JsonObject part = objectOrNull(element);
            if (part == null || (part.has("thought") && part.get("thought").getAsBoolean())) {
                continue;
            }
            if (part.has("text") && !part.get("text").isJsonNull()) {
                if (!result.isEmpty()) result.append('\n');
                result.append(part.get("text").getAsString());
            }
        }
        String text = result.toString().trim();
        if (text.isEmpty()) {
            throw new IOException("Gemini returned no response text.");
        }
        return stripCodeFence(text);
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstLine = text.indexOf('\n');
        int closing = text.lastIndexOf("```");
        if (firstLine < 0 || closing <= firstLine) return text;
        return text.substring(firstLine + 1, closing).trim();
    }

    private static JsonObject objectOrNull(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray arrayOrNull(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String safeEnum(JsonElement element) {
        try {
            return element.getAsString().replaceAll("[^A-Za-z0-9_]", "");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }
}
