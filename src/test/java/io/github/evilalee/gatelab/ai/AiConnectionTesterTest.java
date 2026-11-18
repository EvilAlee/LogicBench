package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiConnectionTesterTest {
    private static AiProvider fake(List<String> models, ModelCall call) {
        return new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("model-aware overload should be used");
                return "";
            }

            @Override
            public List<String> models() {
                return models;
            }

            @Override
            public String complete(String model, String prompt,
                                   List<Turn> conversation, JsonObject schema) throws IOException {
                return call.complete(model);
            }

            @Override
            public String redact(String text) {
                return text == null ? "" : text.replace("connection-secret", "***");
            }
        };
    }

    @FunctionalInterface
    private interface ModelCall {
        String complete(String model) throws IOException;
    }

    @Test
    void triesPrimaryThenFallbackAndReportsEachHttpCode() throws Exception {
        List<String> calls = new ArrayList<>();
        AiProvider provider = fake(List.of("missing-model", "working-model"), model -> {
            calls.add(model);
            if (model.equals("missing-model")) {
                throw new ProviderFailure(ProviderFailure.Kind.MODEL_UNAVAILABLE,
                        "Gemini model not found (HTTP 404).", model, 404);
            }
            return "{\"ok\":true}";
        });

        AiConnectionTester.Result result = AiConnectionTester.test(provider);

        assertTrue(result.connected());
        assertEquals("working-model", result.answeringModel());
        assertEquals(List.of("missing-model", "working-model"), calls);
        assertEquals(List.of("missing-model: HTTP 404", "working-model: 200 OK"),
                result.modelAttempts());
    }

    @Test
    void reports503And429WithTheModelNameAndAttemptsEachConfiguredModel() throws Exception {
        for (int status : List.of(503, 429)) {
            List<String> calls = new ArrayList<>();
            AiProvider provider = fake(List.of("primary-model", "fallback-model"), model -> {
                calls.add(model);
                throw new ProviderFailure(ProviderFailure.Kind.RETRYABLE,
                        "Gemini transport failed (HTTP " + status + ").", model, status);
            });

            AiConnectionTester.Result result = AiConnectionTester.test(provider);

            assertFalse(result.connected());
            assertEquals(List.of("primary-model", "fallback-model"), calls);
            assertTrue(result.message().contains("HTTP " + status));
            assertTrue(result.message().contains("fallback-model"));
            assertEquals(List.of("primary-model: HTTP " + status,
                    "fallback-model: HTTP " + status), result.modelAttempts());
        }
    }

    @Test
    void rejectedKeyStopsBeforeAnyFallbackModelAndIsRedacted() throws Exception {
        List<String> calls = new ArrayList<>();
        AiProvider provider = fake(List.of("primary-model", "fallback-model"), model -> {
            calls.add(model);
            throw new ProviderFailure(ProviderFailure.Kind.KEY_REJECTED,
                    "API key rejected: connection-secret (HTTP 401).", model, 401);
        });

        AiConnectionTester.Result result = AiConnectionTester.test(provider);

        assertFalse(result.connected());
        assertEquals(List.of("primary-model"), calls);
        assertTrue(result.message().contains("API key rejected"));
        assertFalse(result.message().contains("connection-secret"));
        assertFalse(String.join("\n", result.modelAttempts()).contains("connection-secret"));
    }
}
