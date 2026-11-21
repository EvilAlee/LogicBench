package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.GateLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AiCacheTest {
    @TempDir Path directory;

    private static final String FULL_ADDER =
            "design a full adder with inputs a, b and cin; outputs sum and carry";

    private static final String AND_ANSWER = """
            {"summary":"AND gate","inputs":["A","B"],
             "outputs":[{"name":"F","expression":"A & B"}],
             "truthTable":[{"output":"F","bits":"0001"}]}
            """;

    private static final String AND_ANSWER_WITH_SPEC = """
            {"summary":"AND gate","inputs":["A","B"],
             "outputs":[{"name":"F","expression":"A & B"}],
             "spec":{"buses":{},"rules":[{"out":"F","expr":"A & B"}]}}
            """;

    private AiCircuitService service(AiProvider provider, AiCache cache) {
        return new AiCircuitService(provider, GateLibrary.load().all(), cache);
    }

    @Test
    void allSixBundledDemonstrationsPassTheFullValidationAndLayoutPath() throws Exception {
        AiCache cache = new AiCache(directory);
        assertEquals(6, cache.bundledDescriptions().size());
        AiProvider neverCall = (prompt, conversation, schema) -> {
            fail("bundled cache should avoid the provider");
            return "";
        };
        AiCircuitService service = service(neverCall, cache);

        for (String description : cache.bundledDescriptions()) {
            AiCircuitService.Result result = service.generate(description, true);
            assertEquals(AiCircuitService.Status.BUILT, result.status(), description);
            assertTrue(result.fromCache(), description);
            assertNotNull(result.netlist(), description);
            assertNotNull(result.layout(), description);
            assertNotNull(result.truthTable(), description);
        }
    }

    @Test
    void normalizationCollapsesCaseAndWhitespaceVariants() {
        assertEquals(
                AiCache.normalizeDescription("Design a FULL adder with inputs A and B"),
                AiCache.normalizeDescription("  DESIGN   a full\nadder with inputs a and b  "));
    }

    @Test
    void userCacheTakesPriorityOverBundledCache() throws Exception {
        AiCache cache = new AiCache(directory);
        JsonObject override = com.google.gson.JsonParser.parseString(AND_ANSWER).getAsJsonObject();
        override.addProperty("summary", "user override");
        cache.storeVerified(FULL_ADDER, override.toString());

        AiCircuitService.Result result = service(
                (prompt, conversation, schema) -> { fail("cache-only should not call provider"); return ""; },
                cache).generate(FULL_ADDER, true);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals("user override", result.summary());
        assertEquals(AiCache.Source.USER, cache.lookupCandidates(FULL_ADDER).getFirst().source());
    }

    @Test
    void normalGenerationUsesValidatedCacheBeforeCallingTheProviderAndRestoresItsModel() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a uniquely described cached AND circuit for checkpoint one.";
        cache.storeVerified(description, AND_ANSWER_WITH_SPEC, "cached-model");
        int[] calls = {0};
        AiProvider neverCall = (prompt, conversation, schema) -> {
            calls[0]++;
            fail("a valid cache hit must avoid the provider");
            return "";
        };

        AiCircuitService.Result result = service(neverCall, cache).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(result.fromCache());
        assertEquals("cached-model", result.answeredBy());
        assertEquals(0, calls[0]);
    }

    @Test
    void regenerateBypassesCacheAndOverwritesItWithTheVerifiedLiveModel() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Regenerate this uniquely described AND circuit.";
        cache.storeVerified(description,
                AND_ANSWER_WITH_SPEC.replace("AND gate", "old cached AND gate"), "old-model");
        AiProvider liveProvider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                return AND_ANSWER_WITH_SPEC.replace("AND gate", "regenerated AND gate");
            }

            @Override
            public List<String> models() { return List.of("live-model"); }
        };

        AiCircuitService.Result regenerated = new AiCircuitService(
                liveProvider, GateLibrary.load().all(), cache)
                .generate(description, false, true, "", true);

        assertEquals(AiCircuitService.Status.VERIFIED, regenerated.status());
        assertFalse(regenerated.fromCache());
        assertEquals("live-model", regenerated.answeredBy());

        AiCache reloadedCache = new AiCache(directory);
        AiCircuitService.Result replayed = service(
                (prompt, conversation, schema) -> {
                    fail("the verified regenerated answer should now be cached");
                    return "";
                }, reloadedCache).generate(description);
        assertTrue(replayed.fromCache());
        assertEquals("live-model", replayed.answeredBy());
        assertEquals("regenerated AND gate", replayed.summary());
    }

    @Test
    void invalidCacheHitFallsThroughToTheLiveProvider() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "A unique description whose cached answer is invalid.";
        cache.storeVerified(description,
                "{\"summary\":\"bad\",\"inputs\":[\"A\"],\"outputs\":[]}", "bad-model");
        int[] calls = {0};
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            return AND_ANSWER_WITH_SPEC;
        };

        AiCircuitService.Result result = service(provider, cache).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertFalse(result.fromCache());
        assertEquals(1, calls[0]);
        assertTrue(result.attemptLog().stream()
                .anyMatch(line -> line.contains("Ignored invalid user cache")));
    }

    @Test
    void expressionShortcutStillRunsBeforeMatchingCacheLookup() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "F = A & B";
        cache.storeVerified(description, "{\"summary\":\"bad\",\"inputs\":[],\"outputs\":[]}");
        AiProvider neverCall = (prompt, conversation, schema) -> {
            fail("expression text must compile locally without provider calls");
            return "";
        };

        AiCircuitService.Result result = service(neverCall, cache).generate(description);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertFalse(result.fromCache());
        assertEquals(0, result.attemptCount());
    }

    @Test
    void legacyUnwrappedUserCacheEntriesRemainReadableWithoutModelMetadata() throws Exception {
        String key = AiCache.normalizeDescription("legacy cache description");
        Files.writeString(directory.resolve("ai_cache.json"),
                "{\"entries\":{\"" + key + "\":" + AND_ANSWER + "}}");
        AiCache cache = new AiCache(directory);

        AiCache.CacheHit hit = cache.lookupCandidates("legacy cache description").getFirst();
        assertEquals(AiCache.Source.USER, hit.source());
        assertEquals("", hit.model());
        assertTrue(hit.answerJson().contains("AND gate"));
    }

    @Test
    void specVerifiedLiveAnswerIsStoredInTheUserCache() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a two-input AND gate with inputs A and B.";
        AiProvider provider = (prompt, conversation, schema) -> AND_ANSWER_WITH_SPEC;
        AiCircuitService.Result result = service(provider, cache).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertFalse(result.fromCache());
        assertEquals(AiCache.Source.USER, cache.lookupCandidates(description).getFirst().source());
        String saved = Files.readString(cache.file());
        assertFalse(saved.contains("test-secret"));
        assertTrue(saved.contains(AiCache.normalizeDescription(description)));
        assertFalse(saved.contains("truthTable"));
    }

    @Test
    void apiKeyTextIsRedactedBeforeAnswerDisplayAndCachePersistence() throws Exception {
        AiCache cache = new AiCache(directory);
        String secret = "sensitive-key";
        String description = "Build an AND gate; token=" + secret;
        String answerWithSecret = AND_ANSWER_WITH_SPEC.replace("AND gate", secret + " AND gate");
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                return answerWithSecret;
            }

            @Override
            public String redact(String text) {
                return text == null ? "" : text.replace(secret, "***");
            }
        };

        AiCircuitService.Result result = service(provider, cache).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertFalse(result.summary().contains(secret));
        assertTrue(result.summary().contains("***"));
        assertFalse(Files.readString(cache.file()).contains(secret));
    }

    @Test
    void cacheOnlyUsesBundledAnswerWithoutCallingProvider() throws Exception {
        AiCache cache = new AiCache(directory);
        int[] calls = {0};
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            throw new IOException("must not be called");
        };
        AiCircuitService.Result result = service(provider, cache).generate(FULL_ADDER, true);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals(0, calls[0]);
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("FROM CACHE")));
    }

    @Test
    void invalidUserCacheEntryIsRejectedAndBundledEntryIsValidatedNext() throws Exception {
        AiCache cache = new AiCache(directory);
        cache.storeVerified(FULL_ADDER, """
                {"summary":"bad","inputs":["A"],"outputs":[],"truthTable":[]}
                """);
        AiCircuitService.Result result = service(
                (prompt, conversation, schema) -> { fail("bundled entry should pass"); return ""; },
                cache).generate(FULL_ADDER, true);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals("Full adder with sum and carry outputs.", result.summary());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("Ignored invalid user cache")));
    }

    @Test
    void preexistingCacheIsUsedBeforeAnyProviderCall() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a two-input AND gate with inputs A and B.";
        cache.storeVerified(description, AND_ANSWER, "cached-model");
        int[] calls = {0};
        List<Long> delays = new java.util.ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            throw new IOException("Gemini server error (HTTP 503).");
        };

        AiCircuitService.Result result = new AiCircuitService(
                provider, GateLibrary.load().all(), cache, delays::add).generate(description);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals("cached-model", result.answeredBy());
        assertEquals(0, result.attemptCount());
        assertEquals(0, calls[0]);
        assertTrue(delays.isEmpty());
    }

    @Test
    void cacheHitPrecedesAnyRejectedKeyLiveRequest() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Design an AND function using A and B.";
        cache.storeVerified(description, AND_ANSWER, "cached-model");
        List<String> calls = new java.util.ArrayList<>();
        AiProvider provider = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("the existing valid cache entry must be used first");
                return "";
            }
            @Override
            public List<String> models() { return List.of("primary-model", "fallback-model"); }
            @Override
            public String complete(String model, String prompt,
                                    List<Turn> conversation, JsonObject schema) throws IOException {
                calls.add(model);
                throw new ProviderFailure(ProviderFailure.Kind.KEY_REJECTED,
                        "API key rejected (HTTP 403).", model, 403);
            }
        };

        AiCircuitService.Result result = service(provider, cache).generate(description);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals("cached-model", result.answeredBy());
        assertTrue(calls.isEmpty());
    }

    @Test
    void cacheOnlyRedactsSecretsFromPreexistingCacheAnswers() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "A circuit description with a preexisting cached answer.";
        String secret = "sensitive-key";
        cache.storeVerified(description, AND_ANSWER.replace("AND gate", secret + " AND gate"));
        AiProvider neverCall = new AiProvider() {
            @Override
            public String complete(String prompt, List<Turn> conversation, JsonObject schema) {
                fail("cache-only mode must not call the provider");
                return "";
            }

            @Override
            public String redact(String text) {
                return text == null ? "" : text.replace(secret, "***");
            }
        };

        AiCircuitService.Result result = service(neverCall, cache).generate(description, true);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertFalse(result.summary().contains(secret));
        assertTrue(result.summary().contains("***"));
        assertFalse(String.join("\n", result.attemptLog()).contains(secret));
    }

    @Test
    void cacheOnlyWithoutAnAnswerIsUnavailableRatherThanUnverified() throws Exception {
        AiCache cache = new AiCache(directory);
        AiProvider neverCall = (prompt, conversation, schema) -> {
            fail("cache-only mode must not call the provider");
            return "";
        };

        AiCircuitService.Result result = service(neverCall, cache)
                .generate("A circuit description that is not cached.", true);

        assertEquals(AiCircuitService.Status.UNAVAILABLE, result.status());
        assertEquals(0, result.attemptCount());
        assertTrue(result.summary().startsWith("AI unavailable:"));
    }

    @Test
    void noNetworkFailureCanFallBackToAJustAvailableCacheEntry() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a two-input AND gate with inputs A and B.";
        int[] calls = {0};
        List<Long> delays = new java.util.ArrayList<>();
        AiProvider provider = (prompt, conversation, schema) -> {
            calls[0]++;
            cache.storeVerified(description, AND_ANSWER);
            throw new IOException("No network connection to Gemini.");
        };
        AiCircuitService.Result result = new AiCircuitService(
                provider, GateLibrary.load().all(), cache, delays::add).generate(description);

        assertEquals(AiCircuitService.Status.BUILT, result.status());
        assertTrue(result.fromCache());
        assertEquals(0, result.attemptCount());
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.startsWith("AI unavailable (No network connection to Gemini., model default) - showing cached result")));
    }
}
