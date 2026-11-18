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
            assertEquals(AiCircuitService.Status.VERIFIED, result.status(), description);
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(result.fromCache());
        assertEquals("user override", result.summary());
        assertEquals(AiCache.Source.USER, cache.lookupCandidates(FULL_ADDER).getFirst().source());
    }

    @Test
    void verifiedLiveAnswerIsStoredInTheUserCache() throws Exception {
        AiCache cache = new AiCache(directory);
        String description = "Build a two-input AND gate with inputs A and B.";
        AiProvider provider = (prompt, conversation, schema) -> AND_ANSWER;
        AiCircuitService.Result result = service(provider, cache).generate(description);

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertFalse(result.fromCache());
        assertEquals(AiCache.Source.USER, cache.lookupCandidates(description).getFirst().source());
        String saved = Files.readString(cache.file());
        assertFalse(saved.contains("test-secret"));
        assertTrue(saved.contains(AiCache.normalizeDescription(description)));
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(result.fromCache());
        assertEquals("Full adder with sum and carry outputs.", result.summary());
        assertTrue(result.attemptLog().stream().anyMatch(line -> line.contains("Ignored invalid user cache")));
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

        assertEquals(AiCircuitService.Status.VERIFIED, result.status());
        assertTrue(result.fromCache());
        assertEquals(0, result.attemptCount());
        assertEquals(3, calls[0]);
        assertEquals(List.of(2_000L, 6_000L), delays);
        assertTrue(result.attemptLog().stream().anyMatch(line ->
                line.startsWith("AI unavailable (No network connection to Gemini., model default) - showing cached result")));
    }
}
