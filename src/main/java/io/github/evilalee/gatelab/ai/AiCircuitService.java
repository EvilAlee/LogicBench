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
import java.util.regex.Pattern;

/**
 * Converts untrusted model JSON into expressions, then independently builds,
 * lays out and engine-verifies the resulting circuit before it can be placed.
 */
public final class AiCircuitService {
    public static final int MAX_INPUTS = 6;
    public static final int MAX_OUTPUTS = 8;
    public static final int MAX_EXPRESSION_LENGTH = 1000;
    public static final int MAX_GATES = 200;
    public static final int MAX_ATTEMPTS = 3;

    public enum Status { VERIFIED, UNVERIFIED }

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
            boolean fromCache) {
        public Result {
            if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            expressions = List.copyOf(expressions == null ? List.of() : expressions);
            mismatchingRows = List.copyOf(mismatchingRows == null ? List.of() : mismatchingRows);
            attemptLog = List.copyOf(attemptLog == null ? List.of() : attemptLog);
        }
    }

    private record Answer(
            String summary,
            List<String> inputs,
            List<String> outputNames,
            List<String> expressionTexts,
            Map<String, String> claimedBits) {
    }

    private record Attempt(
            Answer answer,
            ExpressionCompiler.Compilation compilation,
            SchematicLayout.LayoutResult layout,
            TruthTable truthTable,
            List<String> mismatchRows,
            List<String> errors) {
        private int score() {
            return truthTable == null ? Integer.MAX_VALUE : mismatchRows.size();
        }
    }

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private final AiProvider provider;
    private final SchematicLayout layoutBuilder;
    private final AiCache cache;

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions) {
        this(provider, definitions, null);
    }

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions, AiCache cache) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.layoutBuilder = new SchematicLayout(Objects.requireNonNull(definitions, "definitions"));
        this.cache = cache;
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

        JsonObject outputProperties = new JsonObject();
        JsonObject outputName = new JsonObject();
        outputName.addProperty("type", "STRING");
        JsonObject expression = new JsonObject();
        expression.addProperty("type", "STRING");
        outputProperties.add("name", outputName);
        outputProperties.add("expression", expression);
        JsonObject outputItem = new JsonObject();
        outputItem.addProperty("type", "OBJECT");
        outputItem.add("properties", outputProperties);
        outputItem.add("required", stringArray("name", "expression"));
        outputItem.addProperty("additionalProperties", false);
        JsonObject outputs = new JsonObject();
        outputs.addProperty("type", "ARRAY");
        outputs.add("items", outputItem);
        properties.add("outputs", outputs);

        JsonObject tableProperties = new JsonObject();
        JsonObject tableOutput = new JsonObject();
        tableOutput.addProperty("type", "STRING");
        JsonObject bits = new JsonObject();
        bits.addProperty("type", "STRING");
        tableProperties.add("output", tableOutput);
        tableProperties.add("bits", bits);
        JsonObject tableItem = new JsonObject();
        tableItem.addProperty("type", "OBJECT");
        tableItem.add("properties", tableProperties);
        tableItem.add("required", stringArray("output", "bits"));
        tableItem.addProperty("additionalProperties", false);
        JsonObject table = new JsonObject();
        table.addProperty("type", "ARRAY");
        table.add("items", tableItem);
        properties.add("truthTable", table);

        root.add("properties", properties);
        root.add("required", stringArray("summary", "inputs", "outputs", "truthTable"));
        root.addProperty("additionalProperties", false);
        return root;
    }

    private static JsonArray stringArray(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    /** The prompt deliberately disallows intermediate named signals. */
    public static String systemPrompt() {
        return """
                You are a Boolean-circuit expression author. Treat the user's description as
                the specification. Return ONLY one JSON object matching the supplied schema;
                no Markdown, code fences, commentary, or extra properties.

                JSON fields:
                - summary: short plain-language description.
                - inputs: every true primary input exactly once, in first-appearance order across
                  output expressions. This is the truth-table order; the first input
                  is the most-significant bit.
                - outputs: only the outputs the user requested, each with a unique name and
                  one Boolean expression.
                - truthTable: independently reason from the user's description and provide
                  one entry per output. Each bits string has exactly 2^input-count characters,
                  uses only 0 and 1, and starts with the row where all inputs are zero.
                  Do NOT derive the table by merely copying/evaluating your expressions;
                  independently determine the intended behavior from the description.

                Expression syntax must be exactly the LogicBench expression parser syntax.
                Identifiers are ASCII names matching [A-Za-z][A-Za-z0-9_]*. Constants are 0
                and 1. Operators, highest precedence first:
                1. NOT: prefix !, ~, NOT; postfix apostrophe (').
                2. AND family: &, *, AND, NAND.
                3. XOR family: ^, XOR, XNOR.
                4. OR family: |, +, OR, NOR.
                Parentheses override precedence; binary operators are left-associative.

                Every expression may reference only names declared in inputs. Use at most
                6 inputs and 8 outputs. Each expression must be at most 1000 characters.
                Names must use the identifier syntax above.

                Each output is represented as "Name = expression" and therefore creates an
                output pin. Do NOT define intermediate named signals; inline sub-expressions
                instead. Identical subexpressions are shared automatically by the compiler.
                Do not output netlists, gates, wiring, or schematic instructions. The
                application parses your expressions and constructs and verifies the circuit.
                """;
    }

    /**
     * If the entire input already is valid expression text, synthesize it offline and do
     * not call the provider. Otherwise request, validate and retry up to three times.
     */
    public Result generate(String description) throws IOException, InterruptedException {
        return generate(description, false);
    }

    /** When cacheOnly is true, no provider request is made. */
    public Result generate(String description, boolean cacheOnly)
            throws IOException, InterruptedException {
        Objects.requireNonNull(description, "description");
        List<String> log = new ArrayList<>();

        ExpressionCompiler.Compilation local = null;
        try {
            local = ExpressionCompiler.compile(description);
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
            if (local.netlist().inputs().size() > MAX_INPUTS
                    || local.netlist().outputs().size() > MAX_OUTPUTS
                    || local.netlist().gates().size() > MAX_GATES) {
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
                log.add("Description parsed as expression text; skipped AI.");
                log.add("Verified " + table.rows().size() + "/" + table.rows().size() + " rows");
                return new Result(0, Status.VERIFIED, "Parsed and verified locally.",
                        local.expressions(), local.netlist(), placed, table,
                        List.of(), log, false);
            } catch (RuntimeException exception) {
                log.add("Local expression build failed: " + safeParseMessage(exception));
                return new Result(0, Status.UNVERIFIED, "Local expression could not be built.",
                        local.expressions(), local.netlist(), null, null, List.of(), log, false);
            }
        }

        Result cached = cachedResult(description, log, "Using", 0);
        if (cached != null) return cached;
        if (cacheOnly) {
            log.add("AI cache only is enabled; no valid cached answer exists.");
            return new Result(0, Status.UNVERIFIED, "No valid cached answer is available offline.",
                    List.of(), null, null, null, List.of(), log, false);
        }

        List<Turn> conversation = new ArrayList<>();
        conversation.add(new Turn(Turn.Role.USER, description));
        Attempt best = null;
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            log.add("Attempt " + attemptNumber + ": asking model");
            String raw;
            try {
                raw = provider.complete(systemPrompt(), List.copyOf(conversation), responseSchema());
            } catch (IOException exception) {
                String error = safeProviderError(exception);
                log.add("Attempt " + attemptNumber + ": " + error);
                if (error.toLowerCase(java.util.Locale.ROOT).contains("no network")) {
                    Result fallback = cachedResult(description, log,
                            "No network connection; falling back to", attemptNumber);
                    if (fallback != null) return fallback;
                }
                conversation.add(new Turn(Turn.Role.USER,
                        "The previous request failed: " + error
                                + " Return a valid JSON answer for the original circuit description."));
                if (attemptNumber == MAX_ATTEMPTS) {
                    break;
                }
                continue;
            }
            conversation.add(new Turn(Turn.Role.MODEL, raw));
            Attempt current = validate(raw);
            if (best == null || current.score() < best.score()
                    || (current.score() == best.score()
                        && current.errors().size() < best.errors().size())) {
                best = current;
            }
            if (current.errors().isEmpty()) {
                int rows = current.truthTable().rows().size();
                log.add("Verified " + rows + "/" + rows + " rows");
                if (cache != null) {
                    try {
                        cache.storeVerified(description, raw);
                        log.add("Saved verified live answer to the user cache.");
                    } catch (IOException cacheError) {
                        log.add("Verified answer, but could not save the user cache.");
                    }
                }
                return result(attemptNumber, Status.VERIFIED, current, log, false);
            }

            List<String> feedbackLines = new ArrayList<>(current.errors());
            feedbackLines.addAll(current.mismatchRows().stream().limit(8).toList());
            String feedback = String.join("\n", feedbackLines);
            if (!current.mismatchRows().isEmpty()) {
                log.add("Mismatch at rows " + String.join("; ", current.mismatchRows().stream().limit(8).toList())
                        + (attemptNumber < MAX_ATTEMPTS ? ", retrying" : ""));
            } else {
                log.add("Validation failed: " + feedback
                        + (attemptNumber < MAX_ATTEMPTS ? "; retrying" : ""));
            }
            if (attemptNumber < MAX_ATTEMPTS) {
                conversation.add(new Turn(Turn.Role.USER,
                        "Your previous JSON was:\n" + raw
                                + "\nCorrect it. The concrete validation errors are:\n"
                                + feedback
                                + "\nReturn the complete corrected JSON object only."));
            }
        }

        if (best == null) {
            return new Result(MAX_ATTEMPTS, Status.UNVERIFIED, "No valid model answer was received.",
                    List.of(), null, null, null, List.of(), log, false);
        }
        return result(MAX_ATTEMPTS, Status.UNVERIFIED, best, log, false);
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
            String description, List<String> log, String prefix, int attemptCount) {
        if (cache == null) return null;
        for (AiCache.CacheHit hit : cache.lookupCandidates(description)) {
            Attempt cachedAttempt = validate(hit.answerJson());
            String source = hit.source().name().toLowerCase(java.util.Locale.ROOT);
            if (cachedAttempt.errors().isEmpty()) {
                int rows = cachedAttempt.truthTable().rows().size();
                log.add(prefix + " " + source + " cache.");
                log.add("FROM CACHE: Verified " + rows + "/" + rows + " rows");
                return result(attemptCount, Status.VERIFIED, cachedAttempt, log, true);
            }
            log.add("Ignored invalid " + source + " cache entry: "
                    + String.join("; ", cachedAttempt.errors()));
        }
        return null;
    }

    private Result result(int attempts, Status status, Attempt attempt,
                          List<String> log, boolean fromCache) {
        return new Result(attempts, status,
                attempt.answer() == null ? "" : attempt.answer().summary(),
                attempt.compilation() == null ? List.of() : attempt.compilation().expressions(),
                attempt.compilation() == null ? null : attempt.compilation().netlist(),
                attempt.layout(), attempt.truthTable(), attempt.mismatchRows(), log, fromCache);
    }

    private Attempt validate(String raw) {
        Answer answer;
        try {
            answer = parseAnswer(raw);
        } catch (RuntimeException exception) {
            return new Attempt(null, null, null, null, List.of(),
                    List.of("JSON/schema error: " + safeParseMessage(exception)));
        }

        StringBuilder source = new StringBuilder();
        for (int index = 0; index < answer.expressionTexts().size(); index++) {
            if (index > 0) source.append('\n');
            source.append(answer.outputNames().get(index))
                    .append(" = ").append(answer.expressionTexts().get(index));
        }

        List<NamedExpression> parsed = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        for (int index = 0; index < answer.expressionTexts().size(); index++) {
            try {
                parsed.add(new NamedExpression(answer.outputNames().get(index),
                        ExpressionParser.parse(answer.expressionTexts().get(index))));
            } catch (RuntimeException exception) {
                String position = exception instanceof io.github.evilalee.gatelab.synth.ParseException parse
                        ? " at position " + parse.position() : "";
                parseErrors.add("Output " + answer.outputNames().get(index)
                        + " expression parse error" + position + ": "
                        + safeParseMessage(exception));
            }
        }
        if (!parseErrors.isEmpty()) {
            return new Attempt(answer, null, null, null, List.of(), parseErrors);
        }

        LinkedHashSet<String> usedVariables = new LinkedHashSet<>();
        parsed.forEach(named -> usedVariables.addAll(named.expression().variables()));
        Set<String> declared = new LinkedHashSet<>(answer.inputs());
        if (!declared.containsAll(usedVariables)) {
            Set<String> undeclared = new LinkedHashSet<>(usedVariables);
            undeclared.removeAll(declared);
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Undeclared variables used by expressions: " + String.join(", ", undeclared)));
        }
        if (!new ArrayList<>(usedVariables).equals(answer.inputs())) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("inputs must exactly match expression variables in first-appearance order; "
                            + "expected " + usedVariables + " but got " + answer.inputs()));
        }

        ExpressionCompiler.Compilation compilation;
        try {
            compilation = ExpressionCompiler.compile(source.toString());
        } catch (RuntimeException exception) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Expression compilation failed: " + safeParseMessage(exception)));
        }
        if (compilation.netlist().gates().size() > MAX_GATES) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Circuit has " + compilation.netlist().gates().size()
                            + " gates; maximum is " + MAX_GATES));
        }

        SchematicLayout.LayoutResult placed;
        try {
            placed = layoutBuilder.layout(compilation.netlist());
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, null, null, List.of(),
                    List.of("Schematic layout failed: " + safeParseMessage(exception)));
        }

        SchematicExpressionVerifier.VerificationResult placedVerification;
        try {
            placedVerification = SchematicExpressionVerifier.verify(compilation, placed);
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, placed, null, List.of(),
                    List.of("Placed schematic verification failed: " + safeParseMessage(exception)));
        }
        if (!placedVerification.verified()) {
            List<String> placedMismatches = placedVerification.mismatches().stream()
                    .limit(8)
                    .map(mismatch -> "placed schematic inputs " + mismatch.inputBits()
                            + " output " + mismatch.outputName() + ": expected "
                            + mismatch.expected() + ", got " + mismatch.actual())
                    .toList();
            return new Attempt(answer, compilation, placed, null, placedMismatches,
                    List.of("Placed schematic did not match its parsed expressions."));
        }

        TruthTable engineTable;
        try {
            engineTable = TruthTableGenerator.generate(compilation.netlist());
        } catch (RuntimeException exception) {
            return new Attempt(answer, compilation, placed, null, List.of(),
                    List.of("Engine truth-table generation failed: " + safeParseMessage(exception)));
        }
        List<String> mismatches = compareClaimedTable(answer, engineTable);
        if (!mismatches.isEmpty()) {
            return new Attempt(answer, compilation, placed, engineTable, mismatches,
                    List.of("Claimed truth table does not match the engine truth table."));
        }
        return new Attempt(answer, compilation, placed, engineTable, List.of(), List.of());
    }

    private static Answer parseAnswer(String raw) {
        String jsonText = stripCodeFence(Objects.requireNonNull(raw, "model response"));
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        requireKeys(root, Set.of("summary", "inputs", "outputs", "truthTable"), "response");
        if (!root.get("summary").isJsonPrimitive()
                || !root.get("summary").getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("summary must be a string");
        }

        JsonArray inputArray = requireArray(root, "inputs");
        if (inputArray.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("inputs has " + inputArray.size()
                    + " entries; maximum is " + MAX_INPUTS);
        }
        List<String> inputs = new ArrayList<>();
        Set<String> seenInputs = new LinkedHashSet<>();
        for (JsonElement item : inputArray) {
            String name = requireString(item, "input name");
            requireName(name, "input name");
            if (!seenInputs.add(name)) throw new IllegalArgumentException("duplicate input name: " + name);
            inputs.add(name);
        }

        JsonArray outputArray = requireArray(root, "outputs");
        if (outputArray.isEmpty()) throw new IllegalArgumentException("outputs must not be empty");
        if (outputArray.size() > MAX_OUTPUTS) {
            throw new IllegalArgumentException("outputs has " + outputArray.size()
                    + " entries; maximum is " + MAX_OUTPUTS);
        }
        List<String> outputNames = new ArrayList<>();
        List<String> expressions = new ArrayList<>();
        Set<String> seenOutputs = new LinkedHashSet<>();
        for (JsonElement item : outputArray) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("each output must be an object");
            JsonObject output = item.getAsJsonObject();
            requireKeys(output, Set.of("name", "expression"), "output");
            String name = requireString(output.get("name"), "output name");
            String expression = requireString(output.get("expression"), "output expression");
            requireName(name, "output name");
            if (!seenOutputs.add(name)) throw new IllegalArgumentException("duplicate output name: " + name);
            if (expression.length() > MAX_EXPRESSION_LENGTH) {
                throw new IllegalArgumentException("expression for " + name + " exceeds "
                        + MAX_EXPRESSION_LENGTH + " characters");
            }
            if (expression.isBlank()) throw new IllegalArgumentException("expression for " + name + " is empty");
            outputNames.add(name);
            expressions.add(expression);
        }

        JsonArray tableArray = requireArray(root, "truthTable");
        if (tableArray.size() != outputNames.size()) {
            throw new IllegalArgumentException("truthTable must contain exactly one entry per output");
        }
        Map<String, String> claimed = new LinkedHashMap<>();
        int expectedLength = 1 << inputs.size();
        for (JsonElement item : tableArray) {
            if (!item.isJsonObject()) throw new IllegalArgumentException("each truthTable entry must be an object");
            JsonObject entry = item.getAsJsonObject();
            requireKeys(entry, Set.of("output", "bits"), "truthTable entry");
            String output = requireString(entry.get("output"), "truthTable output");
            String bits = requireString(entry.get("bits"), "truthTable bits");
            if (!seenOutputs.contains(output)) {
                throw new IllegalArgumentException("truthTable references unknown output: " + output);
            }
            if (claimed.putIfAbsent(output, bits) != null) {
                throw new IllegalArgumentException("duplicate truthTable output: " + output);
            }
            if (bits.length() != expectedLength) {
                throw new IllegalArgumentException("truthTable bits for " + output + " has length "
                        + bits.length() + "; expected " + expectedLength);
            }
            if (!bits.matches("[01]+")) {
                throw new IllegalArgumentException("truthTable bits for " + output
                        + " must contain only 0 and 1");
            }
        }
        if (!claimed.keySet().equals(seenOutputs)) {
            throw new IllegalArgumentException("truthTable entries must cover every output");
        }
        return new Answer(root.get("summary").getAsString(), List.copyOf(inputs),
                List.copyOf(outputNames), List.copyOf(expressions), Map.copyOf(claimed));
    }

    private static void requireKeys(JsonObject object, Set<String> required, String label) {
        Set<String> actual = new LinkedHashSet<>(object.keySet());
        if (!actual.equals(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(actual);
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(required);
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

    private static List<String> compareClaimedTable(Answer answer, TruthTable engineTable) {
        Map<Integer, List<String>> mismatchesByRow = new LinkedHashMap<>();
        for (int outputIndex = 0; outputIndex < engineTable.outputNames().size(); outputIndex++) {
            String outputName = engineTable.outputNames().get(outputIndex);
            String claimedBits = answer.claimedBits().get(outputName);
            for (TruthTable.Row row : engineTable.rows()) {
                Value actual = row.outputValues().get(outputIndex);
                char actualBit = actual == Value.ONE ? '1' : actual == Value.ZERO ? '0' : 'X';
                char claimedBit = claimedBits.charAt(row.index());
                if (actualBit != claimedBit || row.oscillating()) {
                    String detail = "output " + outputName + ": you claimed " + claimedBit
                            + ", circuit gives " + actualBit;
                    if (row.oscillating()) detail += " (oscillating)";
                    mismatchesByRow.computeIfAbsent(row.index(), ignored -> new ArrayList<>())
                            .add(detail);
                }
            }
        }
        List<String> result = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> entry : mismatchesByRow.entrySet()) {
            String bits = engineTable.rows().get(entry.getKey()).inputBits();
            result.add("inputs " + bits + ": " + String.join("; ", entry.getValue()));
        }
        return List.copyOf(result);
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

    private static String safeParseMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.replaceAll("[\\r\\n]+", " ");
    }

    private static String safeProviderError(IOException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? "Gemini request failed." : message.replaceAll("[\\r\\n]+", " ");
    }
}
