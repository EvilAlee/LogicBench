package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.ExpressionParser;
import io.github.evilalee.gatelab.synth.NamedExpression;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableGenerator;
import io.github.evilalee.gatelab.synth.layout.SchematicExpressionVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts untrusted model JSON into expressions, then independently builds,
 * lays out and engine-verifies the resulting circuit before it can be placed.
 */
public final class AiCircuitService {
    public enum Status { VERIFIED, BUILT, UNVERIFIED, UNAVAILABLE }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final long[] ROUND_RETRY_DELAYS_MILLIS = {2_000, 6_000};

    public record Result(
            int attemptCount,
            Status status,
            String summary,
            List<NamedExpression> expressions,
            Netlist netlist,
            SchematicLayout.LayoutResult layout,
            TruthTable truthTable,
            List<String> mismatchingRows,
            List<String> attemptLog,
            boolean fromCache,
            String answeredBy,
            List<String> modelAttempts,
            List<NamedExpression> signals) {
        public Result {
            if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            expressions = List.copyOf(expressions == null ? List.of() : expressions);
            mismatchingRows = List.copyOf(mismatchingRows == null ? List.of() : mismatchingRows);
            attemptLog = List.copyOf(attemptLog == null ? List.of() : attemptLog);
            answeredBy = answeredBy == null ? "" : answeredBy;
            modelAttempts = List.copyOf(modelAttempts == null ? List.of() : modelAttempts);
            signals = List.copyOf(signals == null ? List.of() : signals);
        }

        public Result(int attemptCount, Status status, String summary,
                      List<NamedExpression> expressions, Netlist netlist,
                      SchematicLayout.LayoutResult layout, TruthTable truthTable,
                      List<String> mismatchingRows, List<String> attemptLog,
                      boolean fromCache, String answeredBy, List<String> modelAttempts) {
            this(attemptCount, status, summary, expressions, netlist, layout, truthTable,
                    mismatchingRows, attemptLog, fromCache, answeredBy, modelAttempts, List.of());
        }

        public Result(int attemptCount, Status status, String summary,
                      List<NamedExpression> expressions, Netlist netlist,
                      SchematicLayout.LayoutResult layout, TruthTable truthTable,
                      List<String> mismatchingRows, List<String> attemptLog,
                      boolean fromCache) {
            this(attemptCount, status, summary, expressions, netlist, layout, truthTable,
                    mismatchingRows, attemptLog, fromCache, "", List.of(), List.of());
        }
    }

    private record ModelAnswer(String raw, String model, List<String> modelAttempts) {
        private ModelAnswer {
            modelAttempts = List.copyOf(modelAttempts);
        }
    }

    private record Answer(
            String summary,
            List<String> inputs,
            List<String> outputNames,
            List<String> signalNames,
            List<String> signalExpressions,
            List<String> expressionTexts,
            JsonObject specification) {
    }

    private record ExpectedRow(int lineNumber, Map<String, Boolean> inputs,
                               Map<String, Boolean> outputs) {
        private ExpectedRow {
            inputs = Map.copyOf(inputs);
            outputs = Map.copyOf(outputs);
        }
    }

    private record ExpectedParse(List<ExpectedRow> rows, List<String> errors) {
        private ExpectedParse {
            rows = List.copyOf(rows);
            errors = List.copyOf(errors);
        }
    }

    private record Attempt(
            Answer answer,
            ExpressionCompiler.Compilation compilation,
            SchematicLayout.LayoutResult layout,
            TruthTable truthTable,
            List<String> mismatchRows,
            List<String> errors,
            List<String> warnings,
            boolean independentCheck) {
        private Attempt {
            mismatchRows = List.copyOf(mismatchRows);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        private int score() {
            if (answer == null) return Integer.MAX_VALUE;
            return (truthTable == null ? 10_000 : 0)
                    + mismatchRows.size() + errors.size();
        }

        private boolean passed() {
            return errors.isEmpty() && mismatchRows.isEmpty();
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private final AiProvider provider;
    private final SchematicLayout layoutBuilder;
    private final AiCache cache;
    private final Sleeper sleeper;

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions) {
        this(provider, definitions, null);
    }

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions, AiCache cache) {
        this(provider, definitions, cache, Thread::sleep);
    }

    AiCircuitService(AiProvider provider, Collection<GateDef> definitions,
                     AiCache cache, Sleeper sleeper) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.layoutBuilder = new SchematicLayout(Objects.requireNonNull(definitions, "definitions"));
        this.cache = cache;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /** JSON schema sent to Gemini for structured output. */
    public static JsonObject responseSchema() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();

        JsonObject summary = new JsonObject();
        summary.addProperty("type", "STRING");
        properties.add("summary", summary);

        JsonObject inputItems = new JsonObject();
        inputItems.addProperty("type", "STRING");
        JsonObject inputs = new JsonObject();
        inputs.addProperty("type", "ARRAY");
        inputs.add("items", inputItems);
        properties.add("inputs", inputs);

        JsonObject outputItem = namedExpressionSchema();
        JsonObject outputs = new JsonObject();
        outputs.addProperty("type", "ARRAY");
        outputs.add("items", outputItem);
        properties.add("outputs", outputs);

        JsonObject signals = new JsonObject();
        signals.addProperty("type", "ARRAY");
        signals.add("items", namedExpressionSchema());
        properties.add("signals", signals);

        JsonObject spec = new JsonObject();
        spec.addProperty("type", "OBJECT");
        JsonObject specProperties = new JsonObject();

        JsonObject pinArray = new JsonObject();
        pinArray.addProperty("type", "ARRAY");
        JsonObject pinName = new JsonObject();
        pinName.addProperty("type", "STRING");
        pinArray.add("items", pinName);
        JsonObject buses = new JsonObject();
        buses.addProperty("type", "OBJECT");
        buses.add("additionalProperties", pinArray);
        specProperties.add("buses", buses);

        JsonObject ruleProperties = new JsonObject();
        JsonObject ruleOut = new JsonObject();
        ruleOut.addProperty("type", "STRING");
        JsonObject ruleExpr = new JsonObject();
        ruleExpr.addProperty("type", "STRING");
        ruleProperties.add("out", ruleOut);
        ruleProperties.add("expression", ruleExpr);
        JsonObject ruleItem = new JsonObject();
        ruleItem.addProperty("type", "OBJECT");
        ruleItem.add("properties", ruleProperties);
        ruleItem.add("required", stringArray("out", "expression"));
        ruleItem.addProperty("additionalProperties", false);
        JsonObject rules = new JsonObject();
        rules.addProperty("type", "ARRAY");
        rules.add("items", ruleItem);
        specProperties.add("rules", rules);

        spec.add("properties", specProperties);
        spec.add("required", stringArray("buses", "rules"));
        spec.addProperty("additionalProperties", false);
        properties.add("spec", spec);

        root.add("properties", properties);
        root.add("required", stringArray("summary", "inputs", "outputs"));
        root.addProperty("additionalProperties", false);
        return root;
    }

    private static JsonObject namedExpressionSchema() {
        JsonObject fields = new JsonObject();
        JsonObject name = new JsonObject();
        name.addProperty("type", "STRING");
        JsonObject expression = new JsonObject();
        expression.addProperty("type", "STRING");
        fields.add("name", name);
        fields.add("expression", expression);
        JsonObject item = new JsonObject();
        item.addProperty("type", "OBJECT");
        item.add("properties", fields);
        item.add("required", stringArray("name", "expression"));
        item.addProperty("additionalProperties", false);
        return item;
    }

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    /** Short model instruction; verification comes from code, never model-claimed rows. */
    public static String systemPrompt() {
        return """
                You design digital logic circuits. Reply with JSON only, shaped as {summary, inputs, outputs, signals, spec}.
                inputs: single-bit pin names.
                outputs: list of {name, expression} for ONLY the pins the user asked for.
                signals: ordered list of {name, expression} for every intermediate value (propagate, generate, carries, partial products).
                A signal may use inputs and EARLIER signals only. Reuse earlier signals: never expand an earlier carry inside a later formula; write C2 from C1 and C3 from C2.
                Output expressions use inputs and signals. Every expression is at most 300 characters.
                Operators: & | ^ ! parentheses and the words NAND NOR XNOR.
                Default to ripple carry for adders (each carry from the previous carry).
                If the user explicitly asks for carry lookahead, write each carry directly from P, G and CIN using named product signals.
                spec: {buses: {A: [A3,A2,A1,A0]}, rules: [{out, expression}]} using integer arithmetic over bus names, for example S = A + B + CIN, and it must cover every output.

                Additional response limits: at most 10 inputs, 16 outputs, 160 signals, 400 gates, 3000 total expression characters, 16 spec rules, and 64 user-supplied expected rows. Never return a truth table. Include a spec for arithmetic, comparison, selection, parity, counting and decoders; omit it for irregular logic without a compact integer description. Bus pins are MSB first, and undeclared pin groupings are one-bit buses.

                Example 1 — 4-bit ripple-carry adder:
                {"summary":"4-bit ripple adder","inputs":["A3","A2","A1","A0","B3","B2","B1","B0","CIN"],
                 "signals":[
                  {"name":"G0","expression":"A0 & B0"},{"name":"G1","expression":"A1 & B1"},
                  {"name":"G2","expression":"A2 & B2"},{"name":"G3","expression":"A3 & B3"},
                  {"name":"P0","expression":"A0 ^ B0"},{"name":"P1","expression":"A1 ^ B1"},
                  {"name":"P2","expression":"A2 ^ B2"},{"name":"P3","expression":"A3 ^ B3"},
                  {"name":"C1","expression":"G0 | (P0 & CIN)"},
                  {"name":"C2","expression":"G1 | (P1 & C1)"},
                  {"name":"C3","expression":"G2 | (P2 & C2)"},
                  {"name":"C4","expression":"G3 | (P3 & C3)"}],
                 "outputs":[
                  {"name":"S0","expression":"P0 ^ CIN"},{"name":"S1","expression":"P1 ^ C1"},
                  {"name":"S2","expression":"P2 ^ C2"},{"name":"S3","expression":"P3 ^ C3"},
                  {"name":"COUT","expression":"C4"}],
                 "spec":{"buses":{"A":["A3","A2","A1","A0"],"B":["B3","B2","B1","B0"],"S":["S3","S2","S1","S0"]},
                         "rules":[{"out":"S","expression":"A + B + CIN"},
                                  {"out":"COUT","expression":"(A + B + CIN) >> 4}]}}

                Example 2 — 4x4 multiplier partial-product signal fragment:
                {"signals":[
                 {"name":"P00","expression":"A0 & B0"},{"name":"P10","expression":"A1 & B0"},
                 {"name":"P20","expression":"A2 & B0"},{"name":"P30","expression":"A3 & B0"},
                 {"name":"P01","expression":"A0 & B1"},{"name":"P11","expression":"A1 & B1"},
                 {"name":"P21","expression":"A2 & B1"},{"name":"P31","expression":"A3 & B1"},
                 {"name":"P02","expression":"A0 & B2"},{"name":"P12","expression":"A1 & B2"},
                 {"name":"P22","expression":"A2 & B2"},{"name":"P32","expression":"A3 & B2"},
                 {"name":"P03","expression":"A0 & B3"},{"name":"P13","expression":"A1 & B3"},
                 {"name":"P23","expression":"A2 & B3"},{"name":"P33","expression":"A3 & B3"}]}
                Continue these partial products with ordered carry-save/ripple sum and carry signals, then output Y0 through Y7. The multiplier spec uses buses A=[A3,A2,A1,A0], B=[B3,B2,B1,B0], Y=[Y7,Y6,Y5,Y4,Y3,Y2,Y1,Y0] and rule {out:Y, expression:"A * B"}.
                """;
    }

    /**
     * If the entire input already is valid expression text, synthesize it offline and do
     * not call the provider. Otherwise request, validate and retry up to three times.
     */
    public Result generate(String description) throws IOException, InterruptedException {
        return generate(description, false, true, "");
    }

    /** When cacheOnly is true, no provider request is made. */
    public Result generate(String description, boolean cacheOnly)
            throws IOException, InterruptedException {
        return generate(description, cacheOnly, true, "");
    }

    public Result generate(String description, boolean cacheOnly, boolean invertingGates)
            throws IOException, InterruptedException {
        return generate(description, cacheOnly, invertingGates, "");
    }

    /**
     * Generates a circuit with optional caller-supplied expected rows.
     * Provider transport and model retry/fallback policy are unchanged.
     */
    public Result generate(
            String description, boolean cacheOnly, boolean invertingGates,
            String expectedRowsText) throws IOException, InterruptedException {
        return generate(description, cacheOnly, invertingGates, expectedRowsText, false);
    }

    /** Regeneration bypasses cache lookup and cache fallback after a live failure. */
    public Result generate(
            String description, boolean cacheOnly, boolean invertingGates,
            String expectedRowsText, boolean bypassCache) throws IOException, InterruptedException {
        Objects.requireNonNull(description, "description");
        expectedRowsText = expectedRowsText == null ? "" : expectedRowsText;
        String safeDescription = provider.redact(description);
        List<String> log = new ArrayList<>();

        ExpressionCompiler.Compilation local = null;
        try {
            local = ExpressionCompiler.compile(description, false, invertingGates);
        } catch (RuntimeException notExpressionText) {
            if (isExpressionSource(description)) {
                log.add("Description parses as expression text but cannot be compiled: "
                        + safeParseMessage(notExpressionText));
                return new Result(0, Status.UNVERIFIED,
                        "Expression text is valid but exceeds synthesis constraints.",
                        List.of(), null, null, null, List.of(), log, false);
            }
            // Natural-language descriptions continue to the provider.
        }
        if (local != null) {
            if (local.netlist().inputs().size() > AiCircuitLimits.MAX_INPUTS
                    || local.netlist().outputs().size() > AiCircuitLimits.MAX_OUTPUTS
                    || local.netlist().gates().size() > AiCircuitLimits.MAX_GATES) {
                log.add("Description parses as expressions but exceeds AI circuit limits.");
                return new Result(0, Status.UNVERIFIED, "Local expression exceeds AI circuit limits.",
                        local.expressions(), local.netlist(), null, null, List.of(), log, false);
            }
            try {
                SchematicLayout.LayoutResult placed = layoutBuilder.layout(local.netlist());
                SchematicExpressionVerifier.VerificationResult placedCheck =
                        SchematicExpressionVerifier.verify(local, placed);
                if (!placedCheck.verified()) {
                    log.add("Placed schematic verification failed: " + placedCheck.mismatches());
                    return new Result(0, Status.UNVERIFIED, "Local expression failed schematic verification.",
                            local.expressions(), local.netlist(), placed, null,
                            placedCheck.mismatches().stream().map(Object::toString).toList(), log, false);
                }
                TruthTable table = TruthTableGenerator.generate(local.netlist());
                List<String> structuralErrors = structuralErrors(table);
                List<String> warnings = structuralWarnings(local.netlist(), table);
                ExpectedParse expected = parseExpectedRows(expectedRowsText,
                        table.inputNames(), table.outputNames());
                List<String> mismatches = compareExpectedRows(expected.rows(), table);
                List<String> failures = new ArrayList<>(structuralErrors);
                failures.addAll(expected.errors());
                if (failures.isEmpty()) failures.addAll(mismatches);
                warnings.forEach(warning -> log.add("Warning: " + warning));
                boolean independent = !expectedRowsText.isBlank();
                if (failures.isEmpty()) {
                    Status status = independent ? Status.VERIFIED : Status.BUILT;
                    log.add(independent
                            ? "Verified against the user's expected rows."
                            : "BUILT: no independent check; read the truth table.");
                    log.add("Description parsed as expression text; skipped AI.");
                    return new Result(0, status, status == Status.VERIFIED
                            ? "Parsed and verified locally." : "Parsed and built locally.",
                            local.expressions(), local.netlist(), placed, table,
                            List.of(), log, false);
                }
                log.addAll(failures);
                return new Result(0, Status.UNVERIFIED, "Local expression failed verification.",
                        local.expressions(), local.netlist(), placed, table, failures, log, false);
            } catch (RuntimeException exception) {
                log.add("Local expression build failed: " + safeParseMessage(exception));
                return new Result(0, Status.UNVERIFIED, "Local expression could not be built.",
                        local.expressions(), local.netlist(), null, null, List.of(), log, false);
            }
        }

        if (!bypassCache) {
            Result cached = cachedResult(
                    safeDescription, log, "Using", 0, invertingGates, expectedRowsText);
            if (cached != null) return cached;
        }

        if (cacheOnly) {
            log.add("AI unavailable: cache-only mode is enabled and no valid cached answer exists.");
            return new Result(0, Status.UNAVAILABLE,
                    "AI unavailable: no valid cached answer is available offline.",
                    List.of(), null, null, null, List.of(), log, false);
        }

        List<Turn> conversation = new ArrayList<>();
        conversation.add(new Turn(Turn.Role.USER, safeDescription));
        Set<String> skippedModels = new LinkedHashSet<>();
        Attempt best = null;
        String bestAnsweredBy = "";
        List<String> allModelAttempts = new ArrayList<>();
        for (int attemptNumber = 1; attemptNumber <= AiCircuitLimits.MAX_ATTEMPTS; attemptNumber++) {
            log.add("Verification attempt " + attemptNumber + ": requesting a model answer.");
            ModelAnswer response;
            try {
                response = completeWithModelFallback(conversation, log, skippedModels);
                allModelAttempts.addAll(response.modelAttempts());
            } catch (ProviderFailure failure) {
                allModelAttempts.addAll(failure.modelAttempts());
                String reason = provider.redact(unavailableReason(failure));
                String notice = provider.redact(cachedNotice(failure));
                Result fallback = bypassCache ? null
                        : cachedResult(safeDescription, log, notice, attemptNumber - 1,
                                invertingGates, expectedRowsText);
                if (fallback != null) {
                    return withModelAttempts(fallback, allModelAttempts);
                }
                log.add("AI unavailable: " + reason);
                return new Result(attemptNumber - 1, Status.UNAVAILABLE,
                        "AI unavailable: " + reason,
                        List.of(), null, null, null, List.of(), log, false,
                        "", allModelAttempts);
            }

            String safeRaw = provider.redact(response.raw());
            conversation.add(new Turn(Turn.Role.MODEL, safeRaw));
            Attempt current = validate(safeRaw, invertingGates, expectedRowsText);
            if (best == null || current.score() < best.score()
                    || (current.score() == best.score()
                        && current.errors().size() < best.errors().size())) {
                best = current;
                bestAnsweredBy = response.model();
            }
            current.warnings().forEach(warning -> log.add("Warning: " + warning));
            if (current.passed()) {
                Status status = current.independentCheck() ? Status.VERIFIED : Status.BUILT;
                int rows = current.truthTable().rows().size();
                if (status == Status.VERIFIED) {
                    log.add("Verified " + rows + " engine rows against independent checks via "
                            + response.model());
                    if (cache != null) {
                        try {
                            cache.storeVerified(safeDescription, serializeAnswer(current.answer()),
                                    response.model());
                            log.add("Saved verified live answer in the new cache schema.");
                        } catch (IOException cacheError) {
                            log.add("Verified answer, but could not save the user cache.");
                        }
                    }
                } else {
                    log.add("BUILT: " + rows
                            + " engine rows generated; no independent check, read the truth table.");
                }
                return result(attemptNumber, status, current, log, false,
                        response.model(), allModelAttempts);
            }

            List<String> feedbackLines = new ArrayList<>(current.errors());
            feedbackLines.addAll(current.mismatchRows());
            if (feedbackLines.isEmpty()) feedbackLines.add("An independent validation check failed.");
            String feedback = String.join("\n", feedbackLines.stream().limit(8).toList());
            log.add("Validation failed: " + feedback
                    + (attemptNumber < AiCircuitLimits.MAX_ATTEMPTS ? "; retrying verification" : ""));
            if (attemptNumber < AiCircuitLimits.MAX_ATTEMPTS) {
                conversation.add(new Turn(Turn.Role.USER,
                        "Your previous JSON was:\n" + safeRaw
                                + "\nCorrect it. The named validation errors are:\n"
                                + feedback
                                + "\nReturn the complete corrected JSON object only."));
            }
        }

        if (best == null) {
            return new Result(0, Status.UNAVAILABLE, "AI unavailable: no model answer was received.",
                    List.of(), null, null, null, List.of(), log, false, "", allModelAttempts);
        }
        return result(AiCircuitLimits.MAX_ATTEMPTS, Status.UNVERIFIED, best, log, false,
                bestAnsweredBy, allModelAttempts);
    }

    /**
     * A model round visits every model not previously marked unavailable. 404s are
     * skipped for the rest of this generation request; transient failures advance
     * to the next model and only trigger a new round after all remaining models fail.
     */
    private ModelAnswer completeWithModelFallback(
            List<Turn> conversation, List<String> log, Set<String> skippedModels)
            throws ProviderFailure, InterruptedException {
        List<String> models = provider.models();
        if (models == null || models.isEmpty()) models = List.of("default");
        LinkedHashSet<String> uniqueModels = new LinkedHashSet<>(models);
        List<String> modelAttempts = new ArrayList<>();
        ProviderFailure lastFailure = null;

        for (int round = 0; round <= ROUND_RETRY_DELAYS_MILLIS.length; round++) {
            boolean retryableFailure = false;
            for (String model : uniqueModels) {
                if (skippedModels.contains(model)) continue;
                try {
                    String raw = provider.complete(model, systemPrompt(),
                            List.copyOf(conversation), responseSchema());
                    String safeModel = provider.redact(model);
                    String outcome = safeModel + ": 200 OK";
                    modelAttempts.add(outcome);
                    log.add(outcome);
                    return new ModelAnswer(provider.redact(raw), safeModel, modelAttempts);
                } catch (IOException exception) {
                    ProviderFailure failure = classifyFailure(exception, model);
                    lastFailure = failure;
                    String outcome = provider.redact(model) + ": "
                            + provider.redact(httpOutcome(failure));
                    modelAttempts.add(outcome);
                    log.add(outcome);

                    switch (failure.kind()) {
                        case KEY_REJECTED -> throw failure.withModelAttempts(modelAttempts);
                        case MODEL_UNAVAILABLE -> skippedModels.add(model);
                        case RETRYABLE -> retryableFailure = true;
                        case FATAL -> throw failure.withModelAttempts(modelAttempts);
                    }
                }
            }

            if (!retryableFailure) break;
            if (round < ROUND_RETRY_DELAYS_MILLIS.length) {
                long wait = ROUND_RETRY_DELAYS_MILLIS[round];
                log.add("All remaining models are temporarily unavailable; retrying in "
                        + (wait / 1_000) + " seconds.");
                sleeper.sleep(wait);
            }
        }

        if (lastFailure == null) {
            lastFailure = new ProviderFailure(ProviderFailure.Kind.MODEL_UNAVAILABLE,
                    "No configured Gemini model is available (HTTP 404).", "", 404);
        }
        throw lastFailure.withModelAttempts(modelAttempts);
    }

    private ProviderFailure classifyFailure(IOException exception, String model) {
        if (exception instanceof ProviderFailure failure) {
            return new ProviderFailure(failure.kind(), provider.redact(failure.getMessage()),
                    provider.redact(failure.model()), failure.httpStatus(),
                    failure.modelAttempts().stream().map(provider::redact).toList());
        }
        String message = safeProviderError(exception);
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher statusMatcher =
                Pattern.compile("http\\s+(\\d{3})", Pattern.CASE_INSENSITIVE).matcher(message);
        int status = statusMatcher.find() ? Integer.parseInt(statusMatcher.group(1)) : -1;
        ProviderFailure.Kind kind;
        if (status == 401 || status == 403 || lower.contains("key rejected")) {
            kind = ProviderFailure.Kind.KEY_REJECTED;
        } else if (status == 404 || lower.contains("model not found")) {
            kind = ProviderFailure.Kind.MODEL_UNAVAILABLE;
        } else if (status == 429 || status >= 500 || lower.contains("timed out")
                || lower.contains("timeout") || lower.contains("network")
                || lower.contains("connect")) {
            kind = ProviderFailure.Kind.RETRYABLE;
        } else {
            // Untyped IOException from an injected/legacy provider is treated as transport.
            kind = ProviderFailure.Kind.RETRYABLE;
        }
        return new ProviderFailure(kind, provider.redact(message), model, status);
    }

    private static String httpOutcome(ProviderFailure failure) {
        return failure.httpStatus() >= 100
                ? "HTTP " + failure.httpStatus()
                : failure.kind() + " (" + failure.getMessage() + ")";
    }

    private static String cachedNotice(ProviderFailure failure) {
        String status = failure.httpStatus() >= 100
                ? "HTTP " + failure.httpStatus()
                : failure.getMessage();
        return "AI unavailable (" + status + ", model "
                + (failure.model().isBlank() ? "unknown" : failure.model())
                + ") - showing cached result";
    }

    private static String unavailableReason(ProviderFailure failure) {
        if (failure.kind() == ProviderFailure.Kind.KEY_REJECTED) {
            return "API key rejected (HTTP " + (failure.httpStatus() < 0
                    ? "unknown" : failure.httpStatus()) + ").";
        }
        if (failure.kind() == ProviderFailure.Kind.MODEL_UNAVAILABLE) {
            return "No configured Gemini model is available (HTTP 404). Check model and fallbackModels in ai.json.";
        }
        if (failure.httpStatus() == 503 || failure.httpStatus() >= 500) {
            return "Gemini is overloaded (HTTP " + failure.httpStatus()
                    + "). Try again, or enable cache-only.";
        }
        if (failure.httpStatus() == 429) {
            return "Gemini is rate limited (HTTP 429). Rate limit reached, try again in a minute or switch model in ai.json.";
        }
        if (failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
            return "Gemini request timed out. Try again, or enable cache-only.";
        }
        if (failure.httpStatus() == 400) return failure.getMessage();
        return "Gemini connection unavailable. " + failure.getMessage()
                + " Try again, or enable cache-only.";
    }

    private Result withModelAttempts(Result result, List<String> modelAttempts) {
        return new Result(result.attemptCount(), result.status(), result.summary(),
                result.expressions(), result.netlist(), result.layout(), result.truthTable(),
                result.mismatchingRows(), result.attemptLog(), result.fromCache(),
                result.answeredBy(), modelAttempts.stream().map(provider::redact).toList());
    }

    private static boolean isExpressionSource(String source) {
        List<String> statements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && depth > 0) {
                depth--;
            } else if ((current == ';' || current == '\n') && depth == 0) {
                addExpressionStatement(statements, source, start, index);
                start = index + 1;
            }
        }
        addExpressionStatement(statements, source, start, source.length());
        if (statements.isEmpty()) return false;

        for (String statement : statements) {
            String trimmed = statement.trim();
            int equals = trimmed.indexOf('=');
            String expression = trimmed;
            if (equals >= 0) {
                int assignmentStart = equals > 0 && trimmed.charAt(equals - 1) == ':'
                        ? equals - 1 : equals;
                String name = trimmed.substring(0, assignmentStart).trim();
                if (!NAME.matcher(name).matches()) return false;
                expression = trimmed.substring(equals + 1).trim();
            }
            if (expression.isEmpty()) return false;
            try {
                ExpressionParser.parse(expression);
            } catch (RuntimeException exception) {
                return false;
            }
        }
        return true;
    }

    private static void addExpressionStatement(
            List<String> statements, String source, int start, int end) {
        String statement = source.substring(start, end);
        if (!statement.isBlank()) statements.add(statement);
    }

    private Result cachedResult(
            String description, List<String> log, String prefix, int attemptCount,
            boolean invertingGates, String expectedRowsText) {
        if (cache == null) return null;
        for (AiCache.CacheHit hit : cache.lookupCandidates(description)) {
            Attempt cachedAttempt = validate(
                    provider.redact(hit.answerJson()), invertingGates, expectedRowsText);
            String source = hit.source().name().toLowerCase(java.util.Locale.ROOT);
            if (cachedAttempt.passed()) {
                Status status = cachedAttempt.independentCheck() ? Status.VERIFIED : Status.BUILT;
                int rows = cachedAttempt.truthTable().rows().size();
                if (prefix.startsWith("AI unavailable")) {
                    log.add(prefix);
                    log.add("Using " + source + " cache.");
                } else {
                    log.add(prefix + " " + source + " cache.");
                }
                log.add("FROM CACHE: Revalidated " + rows + " engine rows"
                        + (status == Status.BUILT ? "; no independent check, read the truth table." : "."));
                return result(attemptCount, status, cachedAttempt, log, true,
                        provider.redact(hit.model()), List.of());
            }
            List<String> failures = new ArrayList<>(cachedAttempt.errors());
            failures.addAll(cachedAttempt.mismatchRows());
            log.add("Ignored invalid " + source + " cache entry: "
                    + String.join("; ", failures.stream().limit(8).toList()));
        }
        return null;
    }

    private Result result(int attempts, Status status, Attempt attempt,
                          List<String> log, boolean fromCache) {
        return result(attempts, status, attempt, log, fromCache, "", List.of());
    }

    private Result result(int attempts, Status status, Attempt attempt,
                          List<String> log, boolean fromCache,
                          String answeredBy, List<String> modelAttempts) {
        List<String> failures = new ArrayList<>(attempt.errors());
        failures.addAll(attempt.mismatchRows());
        List<NamedExpression> signals = new ArrayList<>();
        if (attempt.answer() != null && attempt.compilation() != null) {
            for (int index = 0; index < attempt.answer().signalNames().size(); index++) {
                signals.add(new NamedExpression(attempt.answer().signalNames().get(index),
                        ExpressionParser.parse(attempt.answer().signalExpressions().get(index))));
            }
        }
        return new Result(attempts, status,
                attempt.answer() == null ? "" : provider.redact(attempt.answer().summary()),
                attempt.compilation() == null ? List.of() : attempt.compilation().expressions(),
                attempt.compilation() == null ? null : attempt.compilation().netlist(),
                attempt.layout(), attempt.truthTable(), failures, log, fromCache,
                answeredBy, modelAttempts, signals);
    }

    private Attempt validate(String raw, boolean invertingGates, String expectedRowsText) {
        Answer answer;
        try {
            answer = parseAnswer(raw);
        } catch (RuntimeException exception) {
            return new Attempt(null, null, null, null, List.of(),
                    List.of("JSON/schema error: " + safeParseMessage(exception)),
                    List.of(), false);
        }

        StringBuilder source = new StringBuilder();
        List<String> parseErrors = new ArrayList<>();
        int expressionIndex = 0;
        for (int index = 0; index < answer.signalNames().size(); index++) {
            String name = answer.signalNames().get(index);
            String expression = answer.signalExpressions().get(index);
            try {
                ExpressionParser.parse(expression);
            } catch (RuntimeException exception) {
                parseErrors.add(parseError("Signal", name, exception));
            }
            if (!source.isEmpty()) source.append('\n');
            source.append(name).append(" := ").append(expression);
            expressionIndex++;
        }
        for (int index = 0; index < answer.expressionTexts().size(); index++) {
            String name = answer.outputNames().get(index);
            String expression = answer.expressionTexts().get(index);
            try {
                ExpressionParser.parse(expression);
            } catch (RuntimeException exception) {
                parseErrors.add(parseError("Output", name, exception));
            }
            if (!source.isEmpty()) source.append('\n');
            source.append(name).append(" = ").append(expression);
            expressionIndex++;
        }
        if (!parseErrors.isEmpty()) {
            return new Attempt(answer, null, null, null, List.of(), parseErrors, List.of(),
                    answer.specification() != null || !expectedRowsText.isBlank());
        }

        ExpressionCompiler.Compilation compilation;
        try {
            compilation = ExpressionCompiler.compile(source.toString(), false, invertingGates);
        } catch (RuntimeException exception) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Expression compilation failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        Set<String> declaredInputs = new LinkedHashSet<>(answer.inputs());
        Set<String> expressionInputs = new LinkedHashSet<>(compilation.netlist().inputs());
        if (!declaredInputs.equals(expressionInputs)) {
            Set<String> undeclared = new LinkedHashSet<>(expressionInputs);
            undeclared.removeAll(declaredInputs);
            Set<String> unused = new LinkedHashSet<>(declaredInputs);
            unused.removeAll(expressionInputs);
            List<String> errors = new ArrayList<>();
            undeclared.forEach(name -> errors.add(
                    "Expression variable '" + name + "' became an input but is not declared."));
            unused.forEach(name -> errors.add(
                    "Declared input '" + name + "' is not used by the expressions."));
            return new Attempt(answer, compilation, null, null, List.of(), errors,
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        if (compilation.netlist().inputs().size() > AiCircuitLimits.MAX_INPUTS) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Circuit has " + compilation.netlist().inputs().size()
                            + " inputs; maximum is " + AiCircuitLimits.MAX_INPUTS),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (compilation.netlist().outputs().size() > AiCircuitLimits.MAX_OUTPUTS) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Circuit has " + compilation.netlist().outputs().size()
                            + " outputs; maximum is " + AiCircuitLimits.MAX_OUTPUTS),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (compilation.netlist().gates().size() > AiCircuitLimits.MAX_GATES) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Circuit has " + compilation.netlist().gates().size()
                            + " gates; maximum is " + AiCircuitLimits.MAX_GATES),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        SchematicLayout.LayoutResult placed;
        try {
            placed = layoutBuilder.layout(compilation.netlist());
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Schematic layout failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        SchematicExpressionVerifier.VerificationResult placedVerification;
        try {
            placedVerification = SchematicExpressionVerifier.verify(compilation, placed);
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, placed, null, List.of(),
                    List.of("Placed geometry verification failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (!placedVerification.verified()) {
            List<String> placedMismatches = placedVerification.mismatches().stream()
                    .limit(8)
                    .map(mismatch -> "Placed geometry " + mismatch.inputBits()
                            + ": " + mismatch.outputName() + " expected "
                            + mismatch.expected() + ", circuit " + mismatch.actual())
                    .toList();
            return new Attempt(answer, compilation, placed, null, placedMismatches,
                    List.of("Placed geometry does not preserve the compiled netlist."),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        TruthTable engineTable;
        try {
            engineTable = TruthTableGenerator.generate(compilation.netlist());
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, placed, null, List.of(),
                    List.of("Engine truth-table generation failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        List<String> structuralFailures = structuralErrors(engineTable);
        List<String> warnings = new ArrayList<>(structuralWarnings(compilation.netlist(), engineTable));
        warnings.addAll(unusedSignalWarnings(answer));
        if (!structuralFailures.isEmpty()) {
            return new Attempt(answer, compilation, placed, engineTable, List.of(),
                    structuralFailures, warnings,
                    answer.specification() != null || !expectedRowsText.isBlank());
        }

        List<String> failures = new ArrayList<>();
        ExpectedParse expected = parseExpectedRows(
                expectedRowsText, engineTable.inputNames(), engineTable.outputNames());
        failures.addAll(expected.errors());
        List<String> rowMismatches = compareExpectedRows(expected.rows(), engineTable);

        // The spec is intentionally the last check in the pipeline, after user-owned rows.
        if (answer.specification() != null) {
            try {
                SpecEvaluator evaluator = SpecEvaluator.parse(
                        answer.specification(), engineTable.inputNames(), engineTable.outputNames());
                List<String> specMismatches = compareSpecification(evaluator, engineTable);
                if (!specMismatches.isEmpty()) failures.addAll(specMismatches);
            } catch (SpecEvaluator.SpecException exception) {
                String position = exception.position() >= 0
                        ? " at position " + exception.position() : "";
                failures.add("Spec error" + position + ": " + safeParseMessage(exception));
            } catch (RuntimeException exception) {
                failures.add("Spec error: " + safeParseMessage(exception));
            }
        }

        boolean independent = answer.specification() != null || !expectedRowsText.isBlank();
        return new Attempt(answer, compilation, placed, engineTable, rowMismatches,
                failures, warnings, independent);
    }

    private static String parseError(String kind, String name, RuntimeException exception) {
        String position = exception instanceof io.github.evilalee.gatelab.synth.ParseException parse
                ? " at position " + parse.position() : "";
        String detail = exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
        return kind + " '" + name + "' parse error" + position + ": "
                + detail.replaceAll("[\\r\\n]+", " ");
    }

    static List<String> structuralErrors(TruthTable table) {
        List<String> errors = new ArrayList<>();
        for (TruthTable.Row row : table.rows()) {
            String assignment = inputPinContext(table, row);
            if (row.oscillating()) {
                errors.add("Engine oscillation for " + assignment + ".");
            }
            for (int index = 0; index < row.outputValues().size(); index++) {
                Value value = row.outputValues().get(index);
                if (value != Value.ZERO && value != Value.ONE) {
                    errors.add("Engine output " + table.outputNames().get(index)
                            + " is X for " + assignment + ".");
                }
            }
            if (errors.size() >= 8) break;
        }
        return List.copyOf(errors);
    }

    private static List<String> unusedSignalWarnings(Answer answer) {
        if (answer.signalNames().isEmpty()) return List.of();
        Map<String, String> expressionsBySignal = new LinkedHashMap<>();
        for (int index = 0; index < answer.signalNames().size(); index++) {
            expressionsBySignal.put(answer.signalNames().get(index),
                    answer.signalExpressions().get(index));
        }
        Set<String> reachable = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        for (String expression : answer.expressionTexts()) {
            pending.addAll(ExpressionParser.parse(expression).variables());
        }
        for (int index = 0; index < pending.size(); index++) {
            String dependency = pending.get(index);
            String expression = expressionsBySignal.get(dependency);
            if (expression != null && reachable.add(dependency)) {
                pending.addAll(ExpressionParser.parse(expression).variables());
            }
        }
        List<String> warnings = new ArrayList<>();
        for (String signal : answer.signalNames()) {
            if (!reachable.contains(signal)) {
                warnings.add("Signal '" + signal + "' is unused; remove it or reference it from an output.");
            }
        }
        return List.copyOf(warnings);
    }

    private static List<String> structuralWarnings(Netlist netlist, TruthTable table) {
        List<String> warnings = new ArrayList<>();
        Set<String> referenced = new LinkedHashSet<>();
        for (Netlist.Gate gate : netlist.gates()) referenced.addAll(gate.inputs());
        for (Netlist.Output output : netlist.outputs()) referenced.add(output.source());
        for (String input : netlist.inputs()) {
            if (!referenced.contains(input)) {
                warnings.add("Declared input '" + input + "' is not connected to an output path.");
            }
        }
        for (int index = 0; index < table.outputNames().size(); index++) {
            int outputIndex = index;
            Value first = table.rows().getFirst().outputValues().get(outputIndex);
            boolean constant = table.rows().stream()
                    .allMatch(row -> row.outputValues().get(outputIndex) == first);
            if (constant) warnings.add("Output '" + table.outputNames().get(index)
                    + "' is constant at " + first + ".");
        }
        return List.copyOf(warnings);
    }

    private static String inputPinContext(TruthTable table, TruthTable.Row row) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < table.inputNames().size(); index++) {
            if (!result.isEmpty()) result.append(' ');
            result.append(table.inputNames().get(index)).append('=')
                    .append(row.inputBits().charAt(index));
        }
        return result.toString();
    }

    private static ExpectedParse parseExpectedRows(
            String text, List<String> inputNames, List<String> outputNames) {
        if (text == null || text.isBlank()) return new ExpectedParse(List.of(), List.of());
        List<ExpectedRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        Pattern assignment = Pattern.compile("([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*([01])");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) continue;
            if (rows.size() + errors.size() >= AiCircuitLimits.MAX_EXPECTED_ROWS) {
                errors.add("Expected rows exceed maximum of "
                        + AiCircuitLimits.MAX_EXPECTED_ROWS + ".");
                break;
            }
            String[] halves = line.split("->", -1);
            if (halves.length != 2) {
                errors.add("Expected row line " + (index + 1)
                        + ": expected 'inputs -> outputs'.");
                continue;
            }
            Map<String, Boolean> inputValues = parseAssignments(
                    halves[0], assignment, inputNames, "input", index + 1, errors);
            Map<String, Boolean> outputValues = parseAssignments(
                    halves[1], assignment, outputNames, "output", index + 1, errors);
            if (inputValues.size() != inputNames.size()
                    || outputValues.size() != outputNames.size()) continue;
            rows.add(new ExpectedRow(index + 1, inputValues, outputValues));
        }
        return new ExpectedParse(rows, errors);
    }

    private static Map<String, Boolean> parseAssignments(
            String text, Pattern pattern, List<String> allowed, String label,
            int lineNumber, List<String> errors) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(text);
        int cursor = 0;
        boolean invalid = false;
        while (matcher.find()) {
            if (!text.substring(cursor, matcher.start()).isBlank()) invalid = true;
            String name = matcher.group(1);
            if (!allowed.contains(name)) {
                errors.add("Expected row line " + lineNumber
                        + ": unknown " + label + " name '" + name + "'.");
                invalid = true;
            } else if (values.putIfAbsent(name, matcher.group(2).equals("1")) != null) {
                errors.add("Expected row line " + lineNumber
                        + ": duplicate " + label + " name '" + name + "'.");
                invalid = true;
            }
            cursor = matcher.end();
        }
        if (cursor == 0 || !text.substring(cursor).isBlank()) invalid = true;
        if (invalid) {
            errors.add("Expected row line " + lineNumber
                    + ": malformed " + label + " assignments.");
        }
        for (String name : allowed) {
            if (!values.containsKey(name)) {
                errors.add("Expected row line " + lineNumber
                        + ": missing " + label + " '" + name + "'.");
            }
        }
        return values;
    }

    private static List<String> compareExpectedRows(
            List<ExpectedRow> expectedRows, TruthTable table) {
        List<String> mismatches = new ArrayList<>();
        Map<String, Integer> inputs = new LinkedHashMap<>();
        Map<String, Integer> outputs = new LinkedHashMap<>();
        for (int index = 0; index < table.inputNames().size(); index++) {
            inputs.put(table.inputNames().get(index), index);
        }
        for (int index = 0; index < table.outputNames().size(); index++) {
            outputs.put(table.outputNames().get(index), index);
        }
        for (ExpectedRow expected : expectedRows) {
            int rowIndex = 0;
            for (String name : table.inputNames()) {
                rowIndex = (rowIndex << 1) | (expected.inputs().get(name) ? 1 : 0);
            }
            TruthTable.Row actualRow = table.rows().get(rowIndex);
            for (String outputName : table.outputNames()) {
                boolean expectedValue = expected.outputs().get(outputName);
                Value actualValue = actualRow.outputValues().get(outputs.get(outputName));
                if (actualValue == Value.X || (actualValue == Value.ONE) != expectedValue) {
                    mismatches.add("Expected row line " + expected.lineNumber() + " "
                            + formatAssignments(expected.inputs()) + ": " + outputName
                            + " expected " + (expectedValue ? "1" : "0") + ", circuit "
                            + (actualValue == Value.ONE ? "1" : actualValue == Value.ZERO ? "0" : "X") + ".");
                    break;
                }
            }
        }
        return mismatches.stream().limit(8).toList();
    }

    private static String formatAssignments(Map<String, Boolean> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((name, value) -> {
            if (!result.isEmpty()) result.append(' ');
            result.append(name).append('=').append(value ? '1' : '0');
        });
        return result.toString();
    }

    private static List<String> compareSpecification(SpecEvaluator evaluator, TruthTable table) {
        List<String> mismatches = new ArrayList<>();
        for (TruthTable.Row row : table.rows()) {
            Map<String, Boolean> inputValues = new LinkedHashMap<>();
            for (int index = 0; index < table.inputNames().size(); index++) {
                inputValues.put(table.inputNames().get(index), row.inputValue(index));
            }
            SpecEvaluator.Evaluation expected = evaluator.evaluate(inputValues);
            String context = evaluator.formatInputContext(inputValues);
            for (SpecEvaluator.Bus bus : evaluator.outputBuses()) {
                long circuitValue = 0L;
                for (String pin : bus.pins()) {
                    int outputIndex = table.outputNames().indexOf(pin);
                    Value bit = row.outputValues().get(outputIndex);
                    circuitValue = (circuitValue << 1)
                            | (bit == Value.ONE ? 1L : 0L);
                }
                long specValue = expected.outputBusValues().get(bus.name());
                if (specValue != circuitValue) {
                    mismatches.add(context + ": " + bus.name() + " spec " + specValue
                            + ", circuit " + circuitValue + ". Either side may be wrong.");
                    break;
                }
            }
            if (mismatches.size() >= 8) break;
        }
        return List.copyOf(mismatches);
    }

    private static String serializeAnswer(Answer answer) {
        JsonObject root = new JsonObject();
        root.addProperty("summary", answer.summary());
        JsonArray inputs = new JsonArray();
        answer.inputs().forEach(inputs::add);
        root.add("inputs", inputs);
        if (!answer.signalNames().isEmpty()) {
            JsonArray signals = new JsonArray();
            for (int index = 0; index < answer.signalNames().size(); index++) {
                JsonObject signal = new JsonObject();
                signal.addProperty("name", answer.signalNames().get(index));
                signal.addProperty("expression", answer.signalExpressions().get(index));
                signals.add(signal);
            }
            root.add("signals", signals);
        }
        JsonArray outputs = new JsonArray();
        for (int index = 0; index < answer.outputNames().size(); index++) {
            JsonObject output = new JsonObject();
            output.addProperty("name", answer.outputNames().get(index));
            output.addProperty("expression", answer.expressionTexts().get(index));
            outputs.add(output);
        }
        root.add("outputs", outputs);
        if (answer.specification() != null) root.add("spec", answer.specification().deepCopy());
        return root.toString();
    }

    private static Answer parseAnswer(String raw) {
        String jsonText = stripCodeFence(Objects.requireNonNull(raw, "model response"));
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        Set<String> required = Set.of("summary", "inputs", "outputs");
        Set<String> allowed = Set.of("summary", "inputs", "outputs", "signals", "spec", "truthTable");
        Set<String> actual = new LinkedHashSet<>(root.keySet());
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(allowed);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalArgumentException("response fields invalid; missing="
                    + missing + ", unexpected=" + unexpected);
        }
        JsonElement summaryElement = root.get("summary");
        String summary = requireString(summaryElement, "summary");

        JsonArray inputArray = requireArray(root, "inputs");
        if (inputArray.size() > AiCircuitLimits.MAX_INPUTS) {
            throw new IllegalArgumentException("inputs has " + inputArray.size()
                    + " entries; maximum is " + AiCircuitLimits.MAX_INPUTS);
        }
        List<String> inputs = new ArrayList<>();
        Set<String> seenInputs = new LinkedHashSet<>();
        for (JsonElement item : inputArray) {
            String name = requireString(item, "input name");
            requireName(name, "input name");
            if (!seenInputs.add(name)) {
                throw new IllegalArgumentException("duplicate input name: " + name);
            }
            inputs.add(name);
        }

        JsonArray outputArray = requireArray(root, "outputs");
        if (outputArray.isEmpty()) throw new IllegalArgumentException("outputs must not be empty");
        if (outputArray.size() > AiCircuitLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException("outputs has " + outputArray.size()
                    + " entries; maximum is " + AiCircuitLimits.MAX_OUTPUTS);
        }
        List<String> outputNames = new ArrayList<>();
        List<String> outputExpressions = new ArrayList<>();
        Set<String> seenOutputs = new LinkedHashSet<>();
        int totalExpressionLength = 0;
        for (JsonElement item : outputArray) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("each output must be an object");
            JsonObject output = item.getAsJsonObject();
            requireExactKeys(output, Set.of("name", "expression"), "output");
            String name = requireString(output.get("name"), "output name");
            String expression = requireString(output.get("expression"), "output expression");
            requireName(name, "output name");
            if (!seenOutputs.add(name)) throw new IllegalArgumentException("duplicate output name: " + name);
            if (expression.isBlank()) throw new IllegalArgumentException("expression for " + name + " is empty");
            requireExpressionLength(expression, "output '" + name + "'");
            totalExpressionLength += expression.length();
            outputNames.add(name);
            outputExpressions.add(expression);
        }

        List<String> signalNames = new ArrayList<>();
        List<String> signalExpressions = new ArrayList<>();
        Set<String> seenSignals = new LinkedHashSet<>();
        JsonElement signalsElement = root.get("signals");
        if (signalsElement != null) {
            if (!signalsElement.isJsonArray()) throw new IllegalArgumentException("signals must be an array");
            if (signalsElement.getAsJsonArray().size() > AiCircuitLimits.MAX_SIGNALS) {
                throw new IllegalArgumentException("signals has " + signalsElement.getAsJsonArray().size()
                        + " entries; maximum is " + AiCircuitLimits.MAX_SIGNALS);
            }
            for (JsonElement item : signalsElement.getAsJsonArray()) {
                if (!item.isJsonObject()) throw new IllegalArgumentException("each signal must be an object");
                JsonObject signal = item.getAsJsonObject();
                requireExactKeys(signal, Set.of("name", "expression"), "signal");
                String name = requireString(signal.get("name"), "signal name");
                String expression = requireString(signal.get("expression"), "signal expression");
                requireName(name, "signal name");
                if (!seenSignals.add(name)) throw new IllegalArgumentException("duplicate signal name: " + name);
                if (seenOutputs.contains(name)) {
                    throw new IllegalArgumentException("signal name conflicts with output: " + name);
                }
                if (expression.isBlank()) throw new IllegalArgumentException("expression for signal " + name + " is empty");
                requireExpressionLength(expression, "signal '" + name + "'");
                totalExpressionLength += expression.length();
                signalNames.add(name);
                signalExpressions.add(expression);
            }
        }
        for (String name : signalNames) {
            if (seenInputs.contains(name)) {
                throw new IllegalArgumentException("signal name conflicts with declared input: " + name);
            }
        }

        Set<String> allSignals = new LinkedHashSet<>(signalNames);
        Set<String> availableSignalInputs = new LinkedHashSet<>(seenInputs);
        for (int index = 0; index < signalNames.size(); index++) {
            String signalName = signalNames.get(index);
            String signalExpression = signalExpressions.get(index);
            try {
                for (String dependency : ExpressionParser.parse(signalExpression).variables()) {
                    if (!availableSignalInputs.contains(dependency)) {
                        if (allSignals.contains(dependency)) {
                            throw new IllegalArgumentException("signal '" + signalName
                                    + "' references signal '" + dependency
                                    + "' before it is defined; signals may use declared inputs and earlier signals only");
                        }
                        throw new IllegalArgumentException("signal '" + signalName
                                + "' references undeclared name '" + dependency
                                + "'; signals may use declared inputs and earlier signals only");
                    }
                }
            } catch (io.github.evilalee.gatelab.synth.ParseException ignored) {
                // Syntax feedback with parser position is produced by validate().
            }
            availableSignalInputs.add(signalName);
        }
        if (totalExpressionLength > AiCircuitLimits.MAX_EXPRESSION_TEXT) {
            throw new IllegalArgumentException("total expression text exceeds "
                    + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters");
        }

        JsonObject specification = null;
        JsonElement specElement = root.get("spec");
        if (specElement != null) {
            if (!specElement.isJsonObject()) throw new IllegalArgumentException("spec must be an object");
            specification = specElement.getAsJsonObject().deepCopy();
            requireExactKeys(specification, Set.of("buses", "rules"), "spec");
            if (!specification.has("buses") || !specification.get("buses").isJsonObject()) {
                throw new IllegalArgumentException("spec.buses must be an object");
            }
            JsonArray rules = requireArray(specification, "rules");
            if (rules.size() > AiCircuitLimits.MAX_SPEC_RULES) {
                throw new IllegalArgumentException("spec.rules has " + rules.size()
                        + " entries; maximum is " + AiCircuitLimits.MAX_SPEC_RULES);
            }
            for (int index = 0; index < rules.size(); index++) {
                JsonElement ruleElement = rules.get(index);
                if (!ruleElement.isJsonObject()) {
                    throw new IllegalArgumentException("spec.rules[" + index + "] must be an object");
                }
                JsonObject rule = ruleElement.getAsJsonObject();
                Set<String> keys = new LinkedHashSet<>(rule.keySet());
                boolean legacyExpression = keys.equals(Set.of("out", "expr"));
                boolean currentExpression = keys.equals(Set.of("out", "expression"));
                if (!legacyExpression && !currentExpression) {
                    throw new IllegalArgumentException("spec.rules[" + index
                            + "] must contain exactly out and expression");
                }
                requireString(rule.get("out"), "spec.rules[" + index + "].out");
                String specExpression = requireString(
                        rule.get(currentExpression ? "expression" : "expr"),
                        "spec.rules[" + index + "].expression");
                requireExpressionLength(specExpression,
                        "spec rule '" + rule.get("out").getAsString() + "'");
                if (currentExpression) {
                    rule.add("expr", rule.get("expression").deepCopy());
                    rule.remove("expression");
                }
                totalExpressionLength += specExpression.length();
            }
            if (totalExpressionLength > AiCircuitLimits.MAX_EXPRESSION_TEXT) {
                throw new IllegalArgumentException("total expression text exceeds "
                        + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters");
            }
        }
        // truthTable is a legacy cache field. It is deliberately never parsed or used as evidence.
        return new Answer(summary, List.copyOf(inputs), List.copyOf(outputNames),
                List.copyOf(signalNames), List.copyOf(signalExpressions),
                List.copyOf(outputExpressions), specification);
    }

    private static void requireExpressionLength(String expression, String label) {
        if (expression.length() > AiCircuitLimits.MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds "
                    + AiCircuitLimits.MAX_EXPRESSION_LENGTH
                    + " characters; split the formula into named signals");
        }
    }

    private static void requireExactKeys(JsonObject object, Set<String> expected, String label) {
        Set<String> actual = new LinkedHashSet<>(object.keySet());
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            throw new IllegalArgumentException(label + " fields invalid; missing="
                    + missing + ", unexpected=" + extra);
        }
    }

    private static JsonArray requireArray(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return element.getAsString();
    }

    private static void requireName(String name, String label) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(label + " must match [A-Za-z][A-Za-z0-9_]*: " + name);
        }
    }

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        String fence = String.valueOf((char) 96).repeat(3);
        if (!trimmed.startsWith(fence)) return trimmed;
        int firstLine = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf(fence);
        if (firstLine < 0 || closing <= firstLine) return trimmed;
        return trimmed.substring(firstLine + 1, closing).trim();
    }

    private String safeParseMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return provider.redact(message.replaceAll("[\\r\\n]+", " "));
    }

    private String providerSafe(String text) {
        return provider.redact(text);
    }

    private String safeProviderError(IOException exception) {
        String message = exception.getMessage();
        return providerSafe(message == null || message.isBlank()
                ? "Gemini request failed." : message.replaceAll("[\\r\\n]+", " "));
    }
}
