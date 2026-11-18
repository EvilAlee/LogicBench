package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GeminiProviderTest {
    @TempDir Path directory;

    private record ServerFixture(HttpServer server, String baseUrl) implements AutoCloseable {
        @Override public void close() { server.stop(0); }
    }

    private ServerFixture server(HttpServer.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return new ServerFixture(server, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private AiConfig config(String baseUrl) {
        return AiConfig.load(Map.of("GEMINI_API_KEY", "test-secret"), directory, baseUrl);
    }

    private GeminiProvider provider(String baseUrl) {
        return new GeminiProvider(config(baseUrl), Duration.ofSeconds(3));
    }

    private static String success(String text) {
        JsonObject response = new JsonObject();
        JsonArray candidates = new JsonArray();
        JsonObject candidate = new JsonObject();
        candidate.addProperty("finishReason", "STOP");
        JsonObject content = new JsonObject();
        JsonArray parts = new JsonArray();
        JsonObject thought = new JsonObject();
        thought.addProperty("text", "internal thought");
        thought.addProperty("thought", true);
        parts.add(thought);
        JsonObject answer = new JsonObject();
        answer.addProperty("text", text);
        parts.add(answer);
        content.add("parts", parts);
        candidate.add("content", content);
        candidates.add(candidate);
        response.add("candidates", candidates);
        return response.toString();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String errorResponse(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        JsonObject root = new JsonObject();
        root.add("error", error);
        return root.toString();
    }

    private static String blockedResponse() {
        JsonObject feedback = new JsonObject();
        feedback.addProperty("blockReason", "SAFETY");
        JsonObject root = new JsonObject();
        root.add("promptFeedback", feedback);
        root.add("candidates", new JsonArray());
        return root.toString();
    }

    private static String nonStopResponse() {
        JsonObject candidate = new JsonObject();
        candidate.addProperty("finishReason", "MAX_TOKENS");
        JsonObject content = new JsonObject();
        content.add("parts", new JsonArray());
        candidate.add("content", content);
        JsonArray candidates = new JsonArray();
        candidates.add(candidate);
        JsonObject root = new JsonObject();
        root.add("candidates", candidates);
        return root.toString();
    }

    private static String complete(AiProvider provider) throws Exception {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        return provider.complete("system rules", List.of(new Turn(Turn.Role.USER, "build XOR")), schema);
    }

    @Test
    void sendsExpectedRequestAndSkipsThoughtPartsAndCodeFences() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<JsonObject> body = new AtomicReference<>();
        try (ServerFixture fixture = server(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, success("\`\`\`json\n{\"ok\":true}\n\`\`\`"));
        })) {
            assertEquals("{\"ok\":true}", complete(provider(fixture.baseUrl())));
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", path.get());
            assertEquals("test-secret", key.get());
            assertEquals("system rules", body.get().getAsJsonObject("systemInstruction")
                    .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("user", body.get().getAsJsonArray("contents").get(0)
                    .getAsJsonObject().get("role").getAsString());
            JsonObject generation = body.get().getAsJsonObject("generationConfig");
            assertEquals(8192, generation.get("maxOutputTokens").getAsInt());
            assertEquals("APPLICATION_JSON", generation.getAsJsonObject("responseFormat")
                    .getAsJsonObject("text").get("mimeType").getAsString());
            assertTrue(generation.getAsJsonObject("responseFormat")
                    .getAsJsonObject("text").has("schema"));
        }
    }

    @Test
    void followsNewOldAndNoSchemaFallbackChainOn400() throws Exception {
        AtomicInteger count = new AtomicInteger();
        List<JsonObject> bodies = new ArrayList<>();
        try (ServerFixture fixture = server(exchange -> {
            JsonObject body = JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            bodies.add(body);
            if (count.incrementAndGet() <= 2) {
                respond(exchange, 400, errorResponse("responseFormat unsupported"));
            } else {
                respond(exchange, 200, success("{\"fallback\":true}"));
            }
        })) {
            assertEquals("{\"fallback\":true}", complete(provider(fixture.baseUrl())));
            assertEquals(3, count.get());
            assertTrue(bodies.get(0).getAsJsonObject("generationConfig").has("responseFormat"));
            assertEquals("application/json", bodies.get(1).getAsJsonObject("generationConfig")
                    .get("responseMimeType").getAsString());
            assertTrue(bodies.get(1).getAsJsonObject("generationConfig").has("responseJsonSchema"));
            assertFalse(bodies.get(2).getAsJsonObject("generationConfig").has("responseFormat"));
            assertFalse(bodies.get(2).getAsJsonObject("generationConfig").has("responseJsonSchema"));
        }
    }

    @Test
    void retries429And503ExactlyOnce() throws Exception {
        for (int status : List.of(429, 503)) {
            AtomicInteger count = new AtomicInteger();
            try (ServerFixture fixture = server(exchange -> {
                if (count.incrementAndGet() == 1) respond(exchange, status, "{}");
                else respond(exchange, 200, success("{\"ok\":1}"));
            })) {
                assertEquals("{\"ok\":1}", complete(provider(fixture.baseUrl())));
                assertEquals(2, count.get());
            }
        }
    }

    @Test
    void mapsAuthenticationModelQuotaAndServerErrorsToPlainMessages() throws Exception {
        for (int status : List.of(401, 403, 404, 429, 500, 502)) {
            try (ServerFixture fixture = server(exchange -> respond(exchange, status, "{}"))) {
                IOException error = assertThrows(IOException.class,
                        () -> complete(provider(fixture.baseUrl())));
                if (status == 401 || status == 403) assertTrue(error.getMessage().contains("key rejected"));
                if (status == 404) assertTrue(error.getMessage().contains("model not found"));
                if (status == 429) assertTrue(error.getMessage().contains("rate limit or quota"));
                if (status >= 500) assertTrue(error.getMessage().contains("server error"));
            }
        }
    }

    @Test
    void rejectsEmptyBlockedAndNonStopResponses() throws Exception {
        JsonObject empty = new JsonObject();
        empty.add("candidates", new JsonArray());
        List<String> responses = List.of(empty.toString(), blockedResponse(), nonStopResponse());
        for (String response : responses) {
            try (ServerFixture fixture = server(exchange -> respond(exchange, 200, response))) {
                IOException error = assertThrows(IOException.class,
                        () -> complete(provider(fixture.baseUrl())));
                assertFalse(error.getMessage().contains("test-secret"));
            }
        }
    }

    @Test
    void requestTimeoutProducesPlainError() throws Exception {
        try (ServerFixture fixture = server(exchange -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try { respond(exchange, 200, success("{\"late\":true}")); }
            catch (IOException ignored) { }
        })) {
            GeminiProvider shortTimeout = new GeminiProvider(config(fixture.baseUrl()),
                    Duration.ofMillis(50));
            IOException error = assertThrows(IOException.class, () -> complete(shortTimeout));
            assertTrue(error.getMessage().contains("timed out"));
            assertFalse(error.getMessage().contains("test-secret"));
        }
    }

    @Test
    void turnToStringRedactsConversationText() {
        Turn turn = new Turn(Turn.Role.USER, "possibly sensitive content");
        assertFalse(turn.toString().contains("possibly sensitive"));
    }
}
