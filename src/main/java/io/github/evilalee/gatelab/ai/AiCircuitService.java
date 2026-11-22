package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.Simulator;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.Expression;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.ExpressionParser;
import io.github.evilalee.gatelab.synth.NamedExpression;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableGenerator;
import io.github.evilalee.gatelab.synth.generators.GeneratedCircuit;
import io.github.evilalee.gatelab.synth.generators.TemplateMatcher;
import io.github.evilalee.gatelab.synth.generators.StandardCircuitGenerators;
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

    public enum ExplanationStatus { EXPLAINED, INVALID_RESPONSE, UNAVAILABLE }

    public record ExplanationResult(
            int attemptCount,
            ExplanationStatus status,
            String summary,
            List<String> steps,
            List<String> notable,
            List<String> attemptLog,
            String answeredBy,
            List<String> modelAttempts) {
        public ExplanationResult {
            if (attemptCount < 0) throw new IllegalArgumentException("attemptCount must not be negative");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            steps = List.copyOf(steps == null ? List.of() : steps);
            notable = List.copyOf(notable == null ? List.of() : notable);
            attemptLog = List.copyOf(attemptLog == null ? List.of() : attemptLog);
            answeredBy = answeredBy == null ? "" : answeredBy;
            modelAttempts = List.copyOf(modelAttempts == null ? List.of() : modelAttempts);
        }
    }

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
            List<NamedExpression> signals,
            Schematic schematic,
            String expressionSource,
            Map<String, Integer> componentUseCounts) {
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
            schematic = schematic == null && layout != null ? layout.schematic() : schematic;
            expressionSource = expressionSource == null ? "" : expressionSource;
            componentUseCounts = Map.copyOf(componentUseCounts == null ? Map.of() : componentUseCounts);
        }

        public Result(int attemptCount, Status status, String summary,
                      List<NamedExpression> expressions, Netlist netlist,
                      SchematicLayout.LayoutResult layout, TruthTable truthTable,
                      List<String> mismatchingRows, List<String> attemptLog,
                      boolean fromCache, String answeredBy, List<String> modelAttempts,
                      List<NamedExpression> signals) {
            this(attemptCount, status, summary, expressions, netlist, layout, truthTable,
                    mismatchingRows, attemptLog, fromCache, answeredBy, modelAttempts,
                    signals, layout == null ? null : layout.schematic(), "", Map.of());
        }

        public Result(int attemptCount, Status status, String summary,
                      List<NamedExpression> expressions, Netlist netlist,
                      SchematicLayout.LayoutResult layout, TruthTable truthTable,
                      List<String> mismatchingRows, List<String> attemptLog,
                      boolean fromCache, String answeredBy, List<String> modelAttempts) {
            this(attemptCount, status, summary, expressions, netlist, layout, truthTable,
                    mismatchingRows, attemptLog, fromCache, answeredBy, modelAttempts,
                    List.of(), layout == null ? null : layout.schematic(), "", Map.of());
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
            List<InstanceSpec> instances,
            JsonObject specification) {
    }

    private record InstanceSpec(String name, String component, List<String> args) {
        private InstanceSpec { args = List.copyOf(args); }
    }

    private record ComponentBuild(
            ComponentExpressionCompiler.Compilation compilation,
            String source, Map<String, Integer> useCounts) {
        private ComponentBuild { useCounts = Map.copyOf(useCounts); }
    }

    private record ComponentSourcePlan(
            List<String> lines, List<String> errors, Map<String, Integer> useCounts) {
        private ComponentSourcePlan {
            lines = List.copyOf(lines);
            errors = List.copyOf(errors);
            useCounts = Map.copyOf(useCounts);
        }
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
            boolean independentCheck,
            ComponentBuild componentBuild) {
        private Attempt {
            mismatchRows = List.copyOf(mismatchRows);
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        private Attempt(Answer answer, ExpressionCompiler.Compilation compilation,
                        SchematicLayout.LayoutResult layout, TruthTable truthTable,
                        List<String> mismatchRows, List<String> errors,
                        List<String> warnings, boolean independentCheck) {
            this(answer, compilation, layout, truthTable, mismatchRows, errors,
                    warnings, independentCheck, null);
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
    private final List<GateDef> gateDefinitions;
    private final List<ComponentDefinition> componentDefinitions;
    private final AiDebugLog debugLog;
    private volatile String lastRawAnswer = "";

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions) {
        this(provider, definitions, null);
    }

    public AiCircuitService(AiProvider provider, Collection<GateDef> definitions, AiCache cache) {
        this(provider, definitions, cache, Thread::sleep);
    }

    AiCircuitService(AiProvider provider, Collection<GateDef> definitions,
                     AiCache cache, Sleeper sleeper) {
        this(provider, definitions, List.of(), cache, sleeper, null);
    }

    public AiCircuitService(
            AiProvider provider, Collection<GateDef> definitions,
            Collection<ComponentDefinition> componentDefinitions,
            AiCache cache, AiDebugLog debugLog) {
        this(provider, definitions, componentDefinitions, cache, Thread::sleep, debugLog);
    }

    AiCircuitService(AiProvider provider, Collection<GateDef> definitions,
                     Collection<ComponentDefinition> componentDefinitions,
                     AiCache cache, Sleeper sleeper, AiDebugLog debugLog) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.gateDefinitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        this.layoutBuilder = new SchematicLayout(this.gateDefinitions);
        this.componentDefinitions = List.copyOf(Objects.requireNonNull(componentDefinitions, "componentDefinitions"));
        this.cache = cache;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.debugLog = debugLog;
    }

    public String lastRawAnswer() { return lastRawAnswer; }

    /** JSON schema sent to Gemini for structured output. */
    public static JsonObject responseSchema() {
        return responseSchema(true, true, false);
    }

    public static JsonObject responseSchema(boolean includeInstances) {
        return responseSchema(includeInstances, true, false);
    }

    /**
     * Builds a response contract for the selected answer mode. When a request
     * explicitly requires library boxes, templates are removed and instances
     * become a required field so the provider cannot choose a competing shape.
     */
    public static JsonObject responseSchema(
            boolean includeInstances, boolean allowTemplateResponses, boolean requireInstances) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "STRING");
        kind.add("enum", allowTemplateResponses
                ? stringArray("expressions", "template") : stringArray("expressions"));
        properties.add("kind", kind);

        if (allowTemplateResponses) {
            JsonObject templateProperties = new JsonObject();
            JsonObject generator = new JsonObject();
            generator.addProperty("type", "STRING");
            templateProperties.add("generator", generator);
            JsonObject parameterValue = new JsonObject();
            parameterValue.addProperty("type", "STRING");
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "OBJECT");
            parameters.add("additionalProperties", parameterValue);
            templateProperties.add("params", parameters);
            JsonObject template = new JsonObject();
            template.addProperty("type", "OBJECT");
            template.add("properties", templateProperties);
            template.add("required", stringArray("generator", "params"));
            template.addProperty("additionalProperties", false);
            properties.add("template", template);
        }

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

        if (includeInstances) {
            JsonObject fields = new JsonObject();
            JsonObject name = new JsonObject(); name.addProperty("type", "STRING");
            JsonObject component = new JsonObject(); component.addProperty("type", "STRING");
            JsonObject argItem = new JsonObject(); argItem.addProperty("type", "STRING");
            JsonObject args = new JsonObject(); args.addProperty("type", "ARRAY"); args.add("items", argItem);
            fields.add("name", name); fields.add("component", component); fields.add("args", args);
            JsonObject instance = new JsonObject(); instance.addProperty("type", "OBJECT");
            instance.add("properties", fields);
            instance.add("required", stringArray("name", "component", "args"));
            instance.addProperty("additionalProperties", false);
            JsonObject instances = new JsonObject(); instances.addProperty("type", "ARRAY");
            instances.add("items", instance); properties.add("instances", instances);
        }

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
        JsonArray required = allowTemplateResponses
                ? stringArray("kind")
                : requireInstances
                        ? stringArray("kind", "summary", "inputs", "outputs", "instances")
                        : stringArray("kind", "summary", "inputs", "outputs");
        root.add("required", required);
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
                You design digital logic circuits. Reply with JSON only and always choose a response kind.
                For ordinary/free-form logic use {kind:"expressions", summary, inputs, outputs, signals?, spec?}.
                For an exact regular circuit from the template table use only {kind:"template", template:{generator, params}}.
                Template params are strings. The program builds the pin expressions and integer spec, then verifies the result; never invent template inputs, outputs, signals or a spec.
                Do not choose a template when the request adds extra behavior (for example an alarm light or traffic-light controller); use kind "expressions" for that request.
                If a requested template dimension is outside the listed range, still name the intended template and exact dimension; the program will report the supported range.

                Template catalogue (A0/B0/D0/S0/G0 are least-significant bits):
                generator        parameters                      input pins                         output pins
                ripple_adder     n=1..4, carryIn=yes|no          A0..A(n-1), B0..B(n-1), CIN?       S0..S(n-1), COUT
                subtractor       n=1..4                          A0..A(n-1), B0..B(n-1)             D0..D(n-1), BOUT
                comparator       n=1..4                          A0..A(n-1), B0..B(n-1)             LT, EQ, GT
                array_multiplier n=1..4, m=1..4                  A0..A(n-1), B0..B(m-1)             P0..P(n+m-1)
                mux              k=1..2                          D0..D(2^k-1), S0..S(k-1)            Y
                decoder          n=1..3, enable=yes|no            A0..A(n-1), EN?                    Y0..Y(2^n-1)
                parity           n=2..8, mode=even|odd            A0..A(n-1)                         PARITY
                majority         n=3 or 5                       A0..A(n-1)                         Y
                gray_to_binary   n=2..4                          G0..G(n-1)                         B0..B(n-1)
                binary_to_gray   n=2..4                          B0..B(n-1)                         G0..G(n-1)
                A conventional 8:1 mux requires 11 independent inputs and is not available under the 10-input limit.
                For a template answer, params must include every parameter listed for that generator and no others.
                Examples of template parameters: {"n":"4","carryIn":"yes"} for ripple_adder; {"n":"4","m":"4"} for array_multiplier.
                Free-form answers keep the existing shape after the kind field:
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
                {"kind":"expressions","summary":"4-bit ripple adder","inputs":["A3","A2","A1","A0","B3","B2","B1","B0","CIN"],
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

                Example 2 — exact 4x4 multiplier template answer:
                {"kind":"template","template":{"generator":"array_multiplier","params":{"n":"4","m":"4"}}}
                """;
    }

    /** Static instructions are followed by the request-specific component catalog. */
    public static String systemPrompt(
            Collection<ComponentDefinition> definitions, String request,
            boolean useLibraryComponents) {
        List<String> requiredComponents = useLibraryComponents
                ? AiComponentCatalog.explicitlyRequestedComponents(definitions, request)
                : List.of();
        return systemPrompt(definitions, request, useLibraryComponents,
                requiredComponents, true);
    }

    public static String systemPrompt(
            Collection<ComponentDefinition> definitions, String request,
            boolean useLibraryComponents, Collection<String> requiredComponents,
            boolean allowTemplateResponses) {
        String templateNotice = allowTemplateResponses ? "" : """
                This request must use kind "expressions"; do not return a template answer.
                Return a complete expressions object with summary, inputs and outputs.
                """;
        String componentPriority = requiredComponents.isEmpty() ? "" : """
                HIGHEST PRIORITY — REQUIRED LIBRARY BOXES:
                The user explicitly requested these library components: %s.
                This is a hard implementation requirement, not a suggestion. Use these exact
                black-box components as boxed instances. Do NOT use a template generator or
                replace the requested boxes with a gate-only circuit, even if a template
                implements the same Boolean function. Return kind "expressions" with summary,
                inputs, outputs, and an instances array containing the requested component(s).
                For four-bit addition requested with FA, instantiate four FA boxes and connect
                each COUT to the next CIN. Do not include a "template" field.
                """.formatted(String.join(", ", requiredComponents));
        if (!useLibraryComponents) return systemPrompt() + "\n\n" + templateNotice;
        String instructions = """
                
                Library component answers may add "instances": [
                  {"name":"U0","component":"FA","args":["A0","B0","C0"]}
                ].
                Signals and instances share one ordered dependency namespace. Declare signals in their dependency order.
                A signal may use an earlier signal or an earlier instance output pin. An instance may use primary inputs,
                earlier signals and earlier instances only. The service interleaves signal and instance declarations
                in dependency order while preserving the order inside each list. Outputs follow all declarations and
                select pins as U0.S or U0.COUT. Never use a multi-output instance as a scalar.
                Calls to DLATCH, DFF, TFF and JKFF are not supported: explain that sequential components are not available yet.
                Never use feedback. Use combinational catalog entries only.

                Worked example — 4-bit ripple adder from FA boxes (CIN is a declared input):
                C0 := CIN
                U0 := FA(A0, B0, C0)
                S0 = U0.S
                C1 := U0.COUT
                U1 := FA(A1, B1, C1)
                S1 = U1.S
                C2 := U1.COUT
                U2 := FA(A2, B2, C2)
                S2 = U2.S
                C3 := U2.COUT
                U3 := FA(A3, B3, C3)
                S3 = U3.S
                COUT = U3.COUT
                Put U0..U3 in "instances" order and C0..C3 in "signals" order; the compiler places each
                carry signal after its source box and before the next box that consumes it.
                """;
        return systemPrompt() + "\n\n" + instructions + "\n"
                + AiComponentCatalog.promptBlock(definitions, request)
                + "\n\n" + templateNotice + componentPriority;
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
        return generate(description, cacheOnly, invertingGates, expectedRowsText,
                bypassCache, true);
    }

    /** Disables deterministic templates when the caller wants provider-only generation. */
    public Result generate(
            String description, boolean cacheOnly, boolean invertingGates,
            String expectedRowsText, boolean bypassCache, boolean useTemplates)
            throws IOException, InterruptedException {
        return generate(description, cacheOnly, invertingGates, expectedRowsText,
                bypassCache, useTemplates, false);
    }

    public Result generate(
            String description, boolean cacheOnly, boolean invertingGates,
            String expectedRowsText, boolean bypassCache, boolean useTemplates,
            boolean useLibraryComponents)
            throws IOException, InterruptedException {
        Objects.requireNonNull(description, "description");
        lastRawAnswer = "";
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
        if (local == null && requiresSequentialBehavior(description)) {
            String explanation = "Sequential behavior is not available yet. This request requires stored state "
                    + "(for example a flip-flop or latch), while AI generation currently supports only "
                    + "combinational library components such as HA and FA.";
            log.add(explanation);
            return new Result(0, Status.UNAVAILABLE, explanation,
                    List.of(), null, null, null, List.of(explanation), log, false);
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

        List<String> requiredComponentNames = useLibraryComponents
                ? AiComponentCatalog.explicitlyRequestedComponents(componentDefinitions, description)
                : List.of();
        boolean requireComponentInstances = !requiredComponentNames.isEmpty();
        boolean allowTemplateResponse = useTemplates && !requireComponentInstances;

        if (!bypassCache && useTemplates && !requireComponentInstances) {
            TemplateMatcher.MatchResult templateMatch = TemplateMatcher.match(description);
            if (templateMatch instanceof TemplateMatcher.Matched matched) {
                GeneratedCircuit template = matched.circuit();
                String label = templateLabel(template);
                Attempt generated = validate(templateAnswerJson(template, label),
                        invertingGates, expectedRowsText, useLibraryComponents, true, List.of());
                log.add("Matched deterministic " + template.generator() + " template.");
                if (generated.passed() && generated.independentCheck()) {
                    log.add("Verified the template against its integer specification.");
                    return result(0, Status.VERIFIED, generated, log, false);
                }
                log.add("Generated template did not pass the common compilation/verification pipeline.");
                return result(0, Status.UNVERIFIED, generated, log, false);
            }
            if (templateMatch instanceof TemplateMatcher.Unsupported unsupported) {
                log.add("Template request is outside the supported range: " + unsupported.message());
                return new Result(0, Status.UNVERIFIED,
                        "Template request not supported: " + unsupported.message(),
                        List.of(), null, null, null, List.of(), log, false);
            }
        }

        if (!bypassCache) {
            Result cached = cachedResult(
                    safeDescription, log, "Using", 0, invertingGates, expectedRowsText,
                    useLibraryComponents, allowTemplateResponse, requiredComponentNames);
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
                response = completeWithModelFallback(
                        conversation, log, skippedModels,
                        systemPrompt(componentDefinitions, description, useLibraryComponents,
                                requiredComponentNames, allowTemplateResponse),
                        responseSchema(useLibraryComponents, allowTemplateResponse,
                                requireComponentInstances));
                allModelAttempts.addAll(response.modelAttempts());
            } catch (ProviderFailure failure) {
                allModelAttempts.addAll(failure.modelAttempts());
                String reason = provider.redact(unavailableReason(failure));
                String notice = provider.redact(cachedNotice(failure));
                Result fallback = bypassCache ? null
                        : cachedResult(safeDescription, log, notice, attemptNumber - 1,
                                invertingGates, expectedRowsText, useLibraryComponents,
                                allowTemplateResponse, requiredComponentNames);
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
            lastRawAnswer = safeRaw;
            conversation.add(new Turn(Turn.Role.MODEL, safeRaw));
            Attempt current = validate(safeRaw, invertingGates, expectedRowsText,
                    useLibraryComponents, allowTemplateResponse, requiredComponentNames);
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

    private static boolean requiresSequentialBehavior(String description) {
        return Pattern.compile(
                "(?i)\\b(accumulator|accumulate|counter|register|flip[\\s-]*flop|"
                        + "sequential|stateful|memory|dlatch|dff|tff|jkff)\\b")
                .matcher(description).find();
    }

    private static boolean templateMatchIsSupported(String description) {
        return TemplateMatcher.match(description) instanceof TemplateMatcher.Matched;
    }

    private static String templateLabel(GeneratedCircuit template) {
        String parameters = template.params().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return "Template: " + template.generator() + "(" + parameters
                + "), verified against its integer spec";
    }

    private static String templateAnswerJson(GeneratedCircuit template, String label) {
        JsonObject answer = template.toJson();
        answer.addProperty("summary", label);
        return answer.toString();
    }

    private static String normalizeTemplateResponse(
            String raw, boolean allowTemplateResponse, List<String> requiredComponentNames) {
        String jsonText = stripCodeFence(Objects.requireNonNull(raw, "model response"));
        JsonElement parsed = JsonParser.parseString(jsonText);
        if (!parsed.isJsonObject()) return raw;
        JsonObject root = parsed.getAsJsonObject();
        JsonElement kindElement = root.get("kind");
        if (kindElement == null) {
            if (!requiredComponentNames.isEmpty()) {
                throw new IllegalArgumentException(componentRequestFormatError(requiredComponentNames));
            }
            if (root.has("template")) {
                throw new IllegalArgumentException(templateNotMatchedError(root.keySet()));
            }
            return raw;
        }
        String kind = requireString(kindElement, "kind");
        if (!requiredComponentNames.isEmpty()
                && (!"expressions".equals(kind) || root.has("template"))) {
            throw new IllegalArgumentException(componentRequestFormatError(requiredComponentNames));
        }
        if ("expressions".equals(kind)) {
            root.remove("kind");
            return root.toString();
        }
        if (!"template".equals(kind)) {
            throw new IllegalArgumentException("kind must be 'expressions' or 'template'; got " + kind);
        }
        if (!allowTemplateResponse) {
            throw new IllegalArgumentException(templateNotMatchedError(root.keySet()));
        }

        Set<String> allowedTemplateFields = Set.of(
                "kind", "template", "summary", "inputs", "outputs", "signals", "spec");
        Set<String> unexpectedTemplateFields = new LinkedHashSet<>(root.keySet());
        unexpectedTemplateFields.removeAll(allowedTemplateFields);
        if (!unexpectedTemplateFields.isEmpty()) {
            throw new IllegalArgumentException("template response has unexpected fields: "
                    + unexpectedTemplateFields);
        }
        // The generator is authoritative; ignore any optional model-supplied circuit details.
        JsonElement templateElement = root.get("template");
        if (templateElement == null || !templateElement.isJsonObject()) {
            throw new IllegalArgumentException("template must be an object with generator and params");
        }
        JsonObject templateObject = templateElement.getAsJsonObject();
        requireExactKeys(templateObject, Set.of("generator", "params"), "template");
        String generatorId = requireString(templateObject.get("generator"), "template generator");
        JsonElement paramsElement = templateObject.get("params");
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            throw new IllegalArgumentException("template params must be an object");
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : paramsElement.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()
                    || !(value.getAsJsonPrimitive().isString()
                        || value.getAsJsonPrimitive().isNumber())) {
                throw new IllegalArgumentException("template parameter '" + entry.getKey()
                        + "' must be a string or integer");
            }
            parameters.put(entry.getKey(), value.getAsString());
        }

        GeneratedCircuit generated = StandardCircuitGenerators.require(generatorId).build(parameters);
        return templateAnswerJson(generated, templateLabel(generated));
    }

    /**
     * A model round visits every model not previously marked unavailable. 404s are
     * skipped for the rest of this generation request; transient failures advance
     * to the next model and only trigger a new round after all remaining models fail.
     */
    /** Structured output schema for read-only circuit explanations. */
    public static JsonObject explanationResponseSchema() {
        JsonObject properties = new JsonObject();
        JsonObject summary = new JsonObject();
        summary.addProperty("type", "STRING");
        properties.add("summary", summary);

        JsonObject stringItem = new JsonObject();
        stringItem.addProperty("type", "STRING");
        JsonObject steps = new JsonObject();
        steps.addProperty("type", "ARRAY");
        steps.add("items", stringItem);
        properties.add("steps", steps);

        JsonObject notable = new JsonObject();
        notable.addProperty("type", "ARRAY");
        notable.add("items", stringItem.deepCopy());
        properties.add("notable", notable);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        schema.add("properties", properties);
        schema.add("required", stringArray("summary", "steps"));
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    /** Applies the same provider redaction used for the Explain user turn. */
    public String redactForProvider(String text) {
        return provider.redact(Objects.requireNonNull(text, "text"));
    }

    /**
     * Requests a read-only explanation. This path deliberately does not consult or
     * update AiCache, build a layout, run generation verification, or create a circuit.
     */
    public ExplanationResult explain(String explainSystemPrompt, String requestText)
            throws InterruptedException {
        Objects.requireNonNull(explainSystemPrompt, "explainSystemPrompt");
        Objects.requireNonNull(requestText, "requestText");
        List<String> log = new ArrayList<>();
        List<String> allModelAttempts = new ArrayList<>();
        List<Turn> conversation = new ArrayList<>();
        conversation.add(new Turn(Turn.Role.USER, provider.redact(requestText)));
        Set<String> skippedModels = new LinkedHashSet<>();

        for (int attempt = 1; attempt <= AiCircuitLimits.MAX_ATTEMPTS; attempt++) {
            log.add("Explanation attempt " + attempt + ": requesting a model answer.");
            ModelAnswer response;
            try {
                response = completeWithModelFallback(
                        conversation, log, skippedModels,
                        explainSystemPrompt, explanationResponseSchema());
                allModelAttempts.addAll(response.modelAttempts());
            } catch (ProviderFailure failure) {
                allModelAttempts.addAll(failure.modelAttempts());
                String reason = provider.redact(unavailableReason(failure));
                log.add("AI unavailable: " + reason);
                return new ExplanationResult(attempt - 1, ExplanationStatus.UNAVAILABLE,
                        "UNAVAILABLE: " + reason, List.of(), List.of(), log,
                        "", allModelAttempts);
            }

            String safeRaw = provider.redact(response.raw());
            conversation.add(new Turn(Turn.Role.MODEL, safeRaw));
            try {
                JsonElement parsed = JsonParser.parseString(stripCodeFence(safeRaw));
                if (!parsed.isJsonObject()) {
                    throw new IllegalArgumentException("response must be a JSON object");
                }
                JsonObject object = parsed.getAsJsonObject();
                Set<String> allowed = Set.of("summary", "steps", "notable");
                Set<String> actual = new LinkedHashSet<>(object.keySet());
                Set<String> extra = new LinkedHashSet<>(actual);
                extra.removeAll(allowed);
                if (!extra.isEmpty()) {
                    throw new IllegalArgumentException("unexpected response fields: " + extra);
                }
                if (!actual.containsAll(Set.of("summary", "steps"))) {
                    Set<String> missing = new LinkedHashSet<>(Set.of("summary", "steps"));
                    missing.removeAll(actual);
                    throw new IllegalArgumentException("missing response fields: " + missing);
                }
                String answerSummary = requireString(object.get("summary"), "summary").trim();
                if (answerSummary.isEmpty()) {
                    throw new IllegalArgumentException("summary must not be empty");
                }
                List<String> steps = parseExplanationList(
                        requireArray(object, "steps"), "steps");
                if (steps.isEmpty()) {
                    throw new IllegalArgumentException("steps must contain at least one item");
                }
                List<String> notable = object.has("notable")
                        ? parseExplanationList(requireArray(object, "notable"), "notable")
                        : List.of();
                log.add("Explanation response parsed successfully via " + response.model() + ".");
                return new ExplanationResult(attempt, ExplanationStatus.EXPLAINED,
                        answerSummary, steps, notable, log, response.model(), allModelAttempts);
            } catch (RuntimeException malformed) {
                String reason = provider.redact(malformed.getMessage() == null
                        ? "invalid explanation response" : malformed.getMessage());
                log.add("Explanation JSON/schema error: " + reason);
                if (attempt < AiCircuitLimits.MAX_ATTEMPTS) {
                    conversation.add(new Turn(Turn.Role.USER,
                            "Your previous explanation response was invalid: " + reason
                                    + ". Return only valid JSON with required string summary, "
                                    + "steps array of strings, and optional notable array of strings."));
                }
            }
        }
        return new ExplanationResult(AiCircuitLimits.MAX_ATTEMPTS,
                ExplanationStatus.INVALID_RESPONSE,
                "AI returned an invalid explanation after "
                        + AiCircuitLimits.MAX_ATTEMPTS + " attempts.",
                List.of(), List.of(), log, "", allModelAttempts);
    }

    private static List<String> parseExplanationList(JsonArray values, String label) {
        List<String> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String value = requireString(values.get(index), label + "[" + index + "]").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(label + "[" + index + "] must not be empty");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private ModelAnswer completeWithModelFallback(
            List<Turn> conversation, List<String> log, Set<String> skippedModels)
            throws ProviderFailure, InterruptedException {
        return completeWithModelFallback(
                conversation, log, skippedModels, systemPrompt(), responseSchema(false));
    }

    private ModelAnswer completeWithModelFallback(
            List<Turn> conversation, List<String> log, Set<String> skippedModels,
            String selectedSystemPrompt, JsonObject selectedSchema)
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
                    String raw = provider.complete(model, selectedSystemPrompt,
                            List.copyOf(conversation), selectedSchema);
                    String safeModel = provider.redact(model);
                    String safeRaw = provider.redact(raw);
                    lastRawAnswer = safeRaw;
                    if (debugLog != null) debugLog.append(safeModel, safeRaw);
                    String outcome = safeModel + ": 200 OK";
                    modelAttempts.add(outcome);
                    log.add(outcome);
                    return new ModelAnswer(safeRaw, safeModel, modelAttempts);
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
                result.answeredBy(), modelAttempts.stream().map(provider::redact).toList(),
                result.signals(), result.schematic(), result.expressionSource(),
                result.componentUseCounts());
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
            boolean invertingGates, String expectedRowsText,
            boolean useLibraryComponents, boolean allowTemplateResponse,
            List<String> requiredComponentNames) {
        if (cache == null) return null;
        for (AiCache.CacheHit hit : cache.lookupCandidates(description)) {
            Attempt cachedAttempt = validate(
                    provider.redact(hit.answerJson()), invertingGates, expectedRowsText,
                    useLibraryComponents, allowTemplateResponse, requiredComponentNames);
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
        List<NamedExpression> outputs = new ArrayList<>();
        if (attempt.answer() != null) {
            for (int index = 0; index < attempt.answer().signalNames().size(); index++) {
                signals.add(new NamedExpression(attempt.answer().signalNames().get(index),
                        ExpressionParser.parse(attempt.answer().signalExpressions().get(index))));
            }
            for (int index = 0; index < attempt.answer().outputNames().size(); index++) {
                outputs.add(new NamedExpression(attempt.answer().outputNames().get(index),
                        ExpressionParser.parse(attempt.answer().expressionTexts().get(index))));
            }
        }
        ComponentBuild built = attempt.componentBuild();
        Schematic schematic = built != null ? built.compilation().schematic()
                : attempt.layout() == null ? null : attempt.layout().schematic();
        return new Result(attempts, status,
                attempt.answer() == null ? "" : provider.redact(attempt.answer().summary()),
                attempt.compilation() == null ? outputs : attempt.compilation().expressions(),
                attempt.compilation() == null ? null : attempt.compilation().netlist(),
                attempt.layout(), attempt.truthTable(), failures, log, fromCache,
                answeredBy, modelAttempts, signals, schematic,
                built == null ? "" : built.source(),
                built == null ? Map.of() : built.useCounts());
    }

    private Attempt validate(
            String raw, boolean invertingGates, String expectedRowsText,
            boolean useLibraryComponents, boolean allowTemplateResponse,
            List<String> requiredComponentNames) {
        Answer answer;
        try {
            answer = parseAnswer(normalizeTemplateResponse(
                    raw, allowTemplateResponse, requiredComponentNames), useLibraryComponents);
        } catch (RuntimeException exception) {
            return new Attempt(null, null, null, null, List.of(),
                    List.of("JSON/schema error: " + safeParseMessage(exception)),
                    List.of(), false);
        }

        if (!requiredComponentNames.isEmpty()) {
            Set<String> suppliedComponents = answer.instances().stream()
                    .map(InstanceSpec::component)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<String> missingComponents = requiredComponentNames.stream()
                    .filter(name -> !suppliedComponents.contains(name)).toList();
            if (!missingComponents.isEmpty()) {
                return new Attempt(answer, null, null, null, List.of(),
                        List.of("This request requires boxed library component(s) "
                                + missingComponents + ". Return an expressions answer with one or more "
                                + "instances of each requested component; do not substitute a gate-only "
                                + "circuit or template."), List.of(),
                        answer.specification() != null || !expectedRowsText.isBlank());
            }
        }

        if (!answer.instances().isEmpty()) {
            if (!useLibraryComponents) {
                return new Attempt(answer, null, null, null, List.of(),
                        List.of("Library component instances are disabled. Turn on 'Use library components' "
                                + "or return a gate-only expressions answer."), List.of(),
                        answer.specification() != null || !expectedRowsText.isBlank());
            }
            return validateComponentAnswer(answer, invertingGates, expectedRowsText);
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

    private Attempt validateComponentAnswer(
            Answer answer, boolean invertingGates, String expectedRowsText) {
        ComponentSourcePlan plan = buildComponentSourcePlan(answer);
        if (!plan.errors().isEmpty()) {
            return new Attempt(answer, null, null, null, List.of(), plan.errors(), List.of(),
                    answer.specification() != null || !expectedRowsText.isBlank());
        }
        String source = String.join("\n", plan.lines());
        Map<String, Integer> uses = plan.useCounts();

        ComponentExpressionCompiler.Compilation compilation;
        try {
            compilation = ComponentExpressionCompiler.compile(source,
                    gateDefinitions, componentDefinitions,
                    ComponentExpressionCompiler.CompileOption.BOXED_INSTANCES,
                    false, invertingGates);
        } catch (RuntimeException exception) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Component compilation failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (compilation.hasSequentialInstances() || compilation.hasFeedback()
                || !compilation.clockComponentIds().isEmpty()) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Sequential components and feedback are not available in AI generation yet. "
                            + "Use combinational components only; DLATCH, DFF, TFF and JKFF are not supported."),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        Set<String> declaredInputs = new LinkedHashSet<>(answer.inputs());
        Set<String> actualInputs = new LinkedHashSet<>(compilation.inputComponentIds().keySet());
        if (!declaredInputs.equals(actualInputs)) {
            Set<String> missing = new LinkedHashSet<>(actualInputs);
            missing.removeAll(declaredInputs);
            Set<String> unused = new LinkedHashSet<>(declaredInputs);
            unused.removeAll(actualInputs);
            List<String> inputErrors = new ArrayList<>();
            missing.forEach(name -> inputErrors.add("Component expression input '" + name
                    + "' is not declared in inputs."));
            unused.forEach(name -> inputErrors.add("Declared input '" + name
                    + "' is not used by the component expressions."));
            return new Attempt(answer, null, null, null, List.of(), inputErrors, List.of(),
                    answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (answer.inputs().size() > AiCircuitLimits.MAX_INPUTS) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Circuit has " + answer.inputs().size()
                            + " inputs; maximum is " + AiCircuitLimits.MAX_INPUTS),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        if (answer.outputNames().size() > AiCircuitLimits.MAX_OUTPUTS) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Circuit has " + answer.outputNames().size()
                            + " outputs; maximum is " + AiCircuitLimits.MAX_OUTPUTS),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        SchematicCompiler.CompiledCircuit flattened;
        try {
            flattened = SchematicCompiler.compileCircuit(compilation.schematic());
        } catch (RuntimeException exception) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Component flattening failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        long gates = flattened.circuit().components().stream().filter(Gate.class::isInstance).count();
        if (gates > AiCircuitLimits.MAX_GATES) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Flattened component circuit has " + gates
                            + " gates; maximum is " + AiCircuitLimits.MAX_GATES),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }

        TruthTable table;
        try {
            table = componentTruthTable(answer, compilation, flattened);
        } catch (RuntimeException exception) {
            return new Attempt(answer, null, null, null, List.of(),
                    List.of("Component engine verification failed: " + safeParseMessage(exception)),
                    List.of(), answer.specification() != null || !expectedRowsText.isBlank());
        }
        ComponentBuild built = new ComponentBuild(compilation, source.toString(), uses);
        List<String> warnings = unusedSignalWarnings(answer);
        List<String> structural = structuralErrors(table);
        if (!structural.isEmpty()) {
            return new Attempt(answer, null, null, table, List.of(), structural, warnings,
                    answer.specification() != null || !expectedRowsText.isBlank(), built);
        }

        List<String> failures = new ArrayList<>();
        ExpectedParse expected = parseExpectedRows(
                expectedRowsText, table.inputNames(), table.outputNames());
        failures.addAll(expected.errors());
        List<String> rowMismatches = compareExpectedRows(expected.rows(), table);
        if (answer.specification() != null) {
            try {
                SpecEvaluator evaluator = SpecEvaluator.parse(
                        answer.specification(), table.inputNames(), table.outputNames());
                failures.addAll(compareSpecification(evaluator, table));
            } catch (SpecEvaluator.SpecException exception) {
                String position = exception.position() >= 0
                        ? " at position " + exception.position() : "";
                failures.add("Spec error" + position + ": " + safeParseMessage(exception));
            } catch (RuntimeException exception) {
                failures.add("Spec error: " + safeParseMessage(exception));
            }
        }
        boolean independent = answer.specification() != null || !expectedRowsText.isBlank();
        return new Attempt(answer, null, null, table, rowMismatches, failures, warnings,
                independent, built);
    }

    private static TruthTable componentTruthTable(
            Answer answer, ComponentExpressionCompiler.Compilation compilation,
            SchematicCompiler.CompiledCircuit flattened) {
        Simulator simulator = new Simulator(flattened.circuit());
        List<TruthTable.Row> rows = new ArrayList<>(1 << answer.inputs().size());
        for (int row = 0; row < (1 << answer.inputs().size()); row++) {
            StringBuilder bits = new StringBuilder(answer.inputs().size());
            for (int index = 0; index < answer.inputs().size(); index++) {
                String name = answer.inputs().get(index);
                boolean high = ((row >>> (answer.inputs().size() - index - 1)) & 1) != 0;
                bits.append(high ? '1' : '0');
                String id = compilation.inputComponentIds().get(name);
                InputPin input = flattened.inputs().get(id);
                if (input == null) throw new IllegalStateException("missing flattened input " + name);
                simulator.setInputValue(input, high ? Value.ONE : Value.ZERO);
            }
            simulator.settle();
            List<Value> outputs = new ArrayList<>(answer.outputNames().size());
            for (String name : answer.outputNames()) {
                String id = compilation.outputComponentIds().get(name);
                OutputProbe probe = flattened.outputs().get(id);
                if (probe == null) throw new IllegalStateException("missing flattened output " + name);
                outputs.add(simulator.getProbeValue(probe));
            }
            rows.add(new TruthTable.Row(row, bits.toString(), outputs, simulator.isOscillating()));
        }
        return new TruthTable(answer.inputs(), answer.outputNames(), rows);
    }

    private ComponentSourcePlan buildComponentSourcePlan(Answer answer) {
        List<String> errors = new ArrayList<>();
        Set<String> inputs = new LinkedHashSet<>(answer.inputs());
        Map<String, Integer> signalIndexes = new LinkedHashMap<>();
        for (int index = 0; index < answer.signalNames().size(); index++) {
            String name = answer.signalNames().get(index);
            if (inputs.contains(name)) {
                errors.add("Signal name '" + name + "' conflicts with a declared input.");
            }
            signalIndexes.put(name, index);
        }

        Map<String, Integer> instanceIndexes = new LinkedHashMap<>();
        for (int index = 0; index < answer.instances().size(); index++) {
            InstanceSpec instance = answer.instances().get(index);
            if (inputs.contains(instance.name()) || signalIndexes.containsKey(instance.name())
                    || instanceIndexes.putIfAbsent(instance.name(), index) != null) {
                errors.add("Instance name '" + instance.name()
                        + "' conflicts with an input, signal or earlier instance.");
            }
        }
        if (answer.instances().size() > AiCircuitLimits.MAX_INSTANCES) {
            errors.add("Circuit has " + answer.instances().size()
                    + " instances; maximum is " + AiCircuitLimits.MAX_INSTANCES);
        }

        Map<String, ComponentDefinition> definitionsByInstance = new LinkedHashMap<>();
        Map<String, Integer> useCounts = new LinkedHashMap<>();
        for (InstanceSpec instance : answer.instances()) {
            ComponentDefinition definition = componentDefinitions.stream()
                    .filter(candidate -> candidate.name().equals(instance.component()))
                    .findFirst().orElse(null);
            if (definition == null) {
                List<String> valid = componentDefinitions.stream()
                        .filter(candidate -> !AiComponentCatalog.isSequential(candidate)
                                && AiComponentCatalog.isCombinational(candidate))
                        .map(ComponentDefinition::name).sorted().toList();
                errors.add("Unknown component '" + instance.component() + "' for "
                        + instance.name() + "; valid combinational components: " + valid);
                continue;
            }
            definitionsByInstance.put(instance.name(), definition);
            if (AiComponentCatalog.isSequential(definition)) {
                errors.add("Component " + definition.name()
                        + " is sequential: not available yet. Use a combinational component instead.");
                continue;
            }
            if (!AiComponentCatalog.isCombinational(definition)) {
                errors.add("Component " + definition.name()
                        + " is not available for AI because its definition contains feedback.");
                continue;
            }
            if (instance.args().size() != definition.inputPorts().size()) {
                errors.add("Instance " + instance.name() + " calls " + definition.name()
                        + " with " + instance.args().size() + " arguments; expected "
                        + definition.inputPorts().size() + " ordered input pins "
                        + definition.inputPorts().stream().map(ComponentDefinition.Port::name).toList());
                continue;
            }
            useCounts.merge(instance.component(), 1, Integer::sum);
        }

        Map<String, Expression> signalExpressions = new LinkedHashMap<>();
        for (int index = 0; index < answer.signalNames().size(); index++) {
            String name = answer.signalNames().get(index);
            try {
                signalExpressions.put(name,
                        ExpressionParser.parse(answer.signalExpressions().get(index)));
            } catch (RuntimeException exception) {
                errors.add("Signal '" + name + "' parse error: " + safeParseMessage(exception));
            }
        }

        List<List<Expression>> instanceArguments = new ArrayList<>();
        for (InstanceSpec instance : answer.instances()) {
            List<Expression> parsed = new ArrayList<>();
            for (int index = 0; index < instance.args().size(); index++) {
                try {
                    parsed.add(ExpressionParser.parse(instance.args().get(index)));
                } catch (RuntimeException exception) {
                    parsed.add(null);
                    String pin = definitionsByInstance.containsKey(instance.name())
                            && index < definitionsByInstance.get(instance.name()).inputPorts().size()
                            ? definitionsByInstance.get(instance.name()).inputPorts().get(index).name()
                            : "argument " + (index + 1);
                    errors.add("Instance " + instance.name() + " argument " + pin
                            + " parse error: " + safeParseMessage(exception));
                }
            }
            instanceArguments.add(parsed);
        }

        for (int index = 0; index < answer.signalNames().size(); index++) {
            String signalName = answer.signalNames().get(index);
            Expression expression = signalExpressions.get(signalName);
            if (expression == null) continue;
            for (String variable : expression.variables()) {
                if (inputs.contains(variable)) continue;
                Integer signalIndex = signalIndexes.get(variable);
                if (signalIndex != null) {
                    if (signalIndex >= index) {
                        errors.add("Signal '" + signalName + "' references signal '" + variable
                                + "' before it is defined; signals may use earlier signals only.");
                    }
                    continue;
                }
                if (instanceIndexes.containsKey(variable)) {
                    ComponentDefinition definition = definitionsByInstance.get(variable);
                    if (definition != null && definition.outputPorts().size() > 1) {
                        errors.add("Instance " + variable + " has multiple outputs "
                                + definition.outputPorts().stream()
                                        .map(ComponentDefinition.Port::name).toList()
                                + "; select " + variable + ".PIN in signal " + signalName + ".");
                    }
                    continue;
                }
                errors.add("Signal '" + signalName + "' references undeclared name '"
                        + variable + "'; use inputs, earlier signals or earlier component instances only.");
            }
            validateComponentOutputReferences(expression, signalName,
                    instanceIndexes, definitionsByInstance, errors);
        }

        List<String> lines = new ArrayList<>();
        Set<String> emittedSignals = new LinkedHashSet<>();
        Set<String> emittedInstances = new LinkedHashSet<>();
        int nextSignal = 0;
        for (int instanceIndex = 0; instanceIndex <= answer.instances().size(); instanceIndex++) {
            while (nextSignal < answer.signalNames().size()) {
                String signalName = answer.signalNames().get(nextSignal);
                Expression expression = signalExpressions.get(signalName);
                if (expression == null) {
                    nextSignal++;
                    continue;
                }
                if (!signalDependenciesAvailable(expression, nextSignal, inputs,
                        signalIndexes, instanceIndexes, emittedSignals, emittedInstances)) {
                    break;
                }
                lines.add(signalName + " := " + answer.signalExpressions().get(nextSignal));
                emittedSignals.add(signalName);
                nextSignal++;
            }

            if (instanceIndex == answer.instances().size()) break;
            InstanceSpec instance = answer.instances().get(instanceIndex);
            List<Expression> args = instanceArguments.get(instanceIndex);
            for (int argumentIndex = 0; argumentIndex < args.size(); argumentIndex++) {
                Expression expression = args.get(argumentIndex);
                if (expression == null) continue;
                String inputPin = definitionsByInstance.containsKey(instance.name())
                        && argumentIndex < definitionsByInstance.get(instance.name()).inputPorts().size()
                        ? definitionsByInstance.get(instance.name()).inputPorts().get(argumentIndex).name()
                        : "argument " + (argumentIndex + 1);
                validateInstanceArgumentReferences(expression, instance, inputPin,
                        instanceIndex, inputs, signalIndexes, instanceIndexes,
                        definitionsByInstance, emittedSignals, emittedInstances, errors);
            }
            if (args.stream().noneMatch(Objects::isNull)
                    && definitionsByInstance.containsKey(instance.name())
                    && instance.args().size() == definitionsByInstance.get(instance.name()).inputPorts().size()) {
                lines.add(instance.name() + " := " + instance.component()
                        + "(" + String.join(", ", instance.args()) + ")");
            }
            emittedInstances.add(instance.name());
        }

        while (nextSignal < answer.signalNames().size()) {
            String signalName = answer.signalNames().get(nextSignal);
            Expression expression = signalExpressions.get(signalName);
            if (expression != null) {
                errors.add("Signal '" + signalName
                        + "' cannot be ordered before the outputs; it references an unknown or later declaration.");
            }
            nextSignal++;
        }

        for (int index = 0; index < answer.outputNames().size(); index++) {
            String outputName = answer.outputNames().get(index);
            Expression expression;
            try {
                expression = ExpressionParser.parse(answer.expressionTexts().get(index));
            } catch (RuntimeException exception) {
                continue; // The component compiler supplies syntax diagnostics.
            }
            for (String variable : expression.variables()) {
                if (inputs.contains(variable) || signalIndexes.containsKey(variable)) continue;
                if (instanceIndexes.containsKey(variable)) {
                    ComponentDefinition definition = definitionsByInstance.get(variable);
                    if (definition != null && definition.outputPorts().size() > 1) {
                        errors.add("Instance " + variable + " has multiple outputs "
                                + definition.outputPorts().stream()
                                        .map(ComponentDefinition.Port::name).toList()
                                + "; select " + variable + ".PIN in output " + outputName + ".");
                    }
                    continue;
                }
                errors.add("Output '" + outputName + "' references undeclared name '"
                        + variable + "'; valid names are inputs, signals and component instances.");
            }
            validateComponentOutputReferences(expression, outputName,
                    instanceIndexes, definitionsByInstance, errors);
            lines.add(outputName + " = " + answer.expressionTexts().get(index));
        }
        return new ComponentSourcePlan(lines, errors, useCounts);
    }

    private static boolean signalDependenciesAvailable(
            Expression expression, int signalIndex, Set<String> inputs,
            Map<String, Integer> signalIndexes, Map<String, Integer> instanceIndexes,
            Set<String> emittedSignals, Set<String> emittedInstances) {
        for (String variable : expression.variables()) {
            if (inputs.contains(variable)) continue;
            Integer dependencySignal = signalIndexes.get(variable);
            if (dependencySignal != null) {
                if (dependencySignal >= signalIndex || !emittedSignals.contains(variable)) return false;
                continue;
            }
            if (instanceIndexes.containsKey(variable)) {
                if (!emittedInstances.contains(variable)) return false;
                continue;
            }
            return false;
        }
        List<Expression.ComponentOutput> outputs = new ArrayList<>();
        collectComponentOutputReferences(expression, outputs);
        for (Expression.ComponentOutput output : outputs) {
            if (!emittedInstances.contains(output.instanceName())) return false;
        }
        return true;
    }

    private static void validateInstanceArgumentReferences(
            Expression expression, InstanceSpec instance, String inputPin, int instanceIndex,
            Set<String> inputs, Map<String, Integer> signalIndexes,
            Map<String, Integer> instanceIndexes,
            Map<String, ComponentDefinition> definitionsByInstance,
            Set<String> emittedSignals, Set<String> emittedInstances,
            List<String> errors) {
        for (String variable : expression.variables()) {
            if (inputs.contains(variable)) continue;
            if (signalIndexes.containsKey(variable)) {
                if (!emittedSignals.contains(variable)) {
                    errors.add("Instance " + instance.name() + " argument " + inputPin
                            + " references signal '" + variable
                            + "' before it is available; instances may use earlier signals only.");
                }
                continue;
            }
            Integer referencedInstance = instanceIndexes.get(variable);
            if (referencedInstance != null) {
                ComponentDefinition definition = definitionsByInstance.get(variable);
                if (definition != null && definition.outputPorts().size() > 1) {
                    errors.add("Instance " + variable + " has multiple outputs "
                            + definition.outputPorts().stream()
                                    .map(ComponentDefinition.Port::name).toList()
                            + "; select " + variable + ".PIN in " + instance.name() + "'s arguments.");
                }
                if (referencedInstance >= instanceIndex) {
                    errors.add("Instance " + instance.name() + " argument " + inputPin
                            + " references later instance '" + variable
                            + "'; instances may use earlier instances only.");
                } else if (!emittedInstances.contains(variable)) {
                    errors.add("Instance " + instance.name() + " argument " + inputPin
                            + " references instance '" + variable + "' before it is available.");
                }
                continue;
            }
            errors.add("Instance " + instance.name() + " argument " + inputPin
                    + " references undeclared name '" + variable
                    + "'; valid names are inputs, earlier signals and earlier instances.");
        }
        validateComponentOutputArgumentReferences(expression, instance, inputPin,
                instanceIndex, instanceIndexes, definitionsByInstance, emittedInstances, errors);
    }

    private static void validateComponentOutputArgumentReferences(
            Expression expression, InstanceSpec usingInstance, String inputPin,
            int instanceIndex, Map<String, Integer> instanceIndexes,
            Map<String, ComponentDefinition> definitionsByInstance,
            Set<String> emittedInstances, List<String> errors) {
        List<Expression.ComponentOutput> outputs = new ArrayList<>();
        collectComponentOutputReferences(expression, outputs);
        for (Expression.ComponentOutput output : outputs) {
            Integer referencedIndex = instanceIndexes.get(output.instanceName());
            if (referencedIndex == null) {
                errors.add("Instance " + usingInstance.name() + " argument " + inputPin
                        + " references unknown instance '" + output.instanceName() + "'.");
                continue;
            }
            ComponentDefinition definition = definitionsByInstance.get(output.instanceName());
            if (definition != null && definition.outputPorts().stream()
                    .noneMatch(port -> port.name().equals(output.outputName()))) {
                errors.add("Unknown output pin '" + output.outputName() + "' on "
                        + output.instanceName() + "; valid pins: "
                        + definition.outputPorts().stream()
                                .map(ComponentDefinition.Port::name).toList());
            }
            if (referencedIndex >= instanceIndex) {
                errors.add("Instance " + usingInstance.name() + " argument " + inputPin
                        + " references later instance '" + output.instanceName()
                        + "'; instances may use earlier instances only.");
            } else if (!emittedInstances.contains(output.instanceName())) {
                errors.add("Instance " + usingInstance.name() + " argument " + inputPin
                        + " references instance '" + output.instanceName() + "' before it is available.");
            }
        }
    }

    private static void validateComponentOutputReferences(
            Expression expression, String owner,
            Map<String, Integer> instanceIndexes,
            Map<String, ComponentDefinition> definitionsByInstance,
            List<String> errors) {
        List<Expression.ComponentOutput> outputs = new ArrayList<>();
        collectComponentOutputReferences(expression, outputs);
        for (Expression.ComponentOutput output : outputs) {
            if (!instanceIndexes.containsKey(output.instanceName())) {
                errors.add(owner + " references unknown instance '" + output.instanceName() + "'.");
                continue;
            }
            ComponentDefinition definition = definitionsByInstance.get(output.instanceName());
            if (definition != null && definition.outputPorts().stream()
                    .noneMatch(port -> port.name().equals(output.outputName()))) {
                errors.add("Unknown output pin '" + output.outputName() + "' on "
                        + output.instanceName() + "; valid pins: "
                        + definition.outputPorts().stream()
                                .map(ComponentDefinition.Port::name).toList());
            }
        }
    }

    private static void collectComponentOutputReferences(
            Expression expression, List<Expression.ComponentOutput> outputs) {
        if (expression instanceof Expression.ComponentOutput output) {
            outputs.add(output);
        } else if (expression instanceof Expression.Unary unary) {
            collectComponentOutputReferences(unary.operand(), outputs);
        } else if (expression instanceof Expression.Binary binary) {
            collectComponentOutputReferences(binary.left(), outputs);
            collectComponentOutputReferences(binary.right(), outputs);
        } else if (expression instanceof Expression.ComponentCall call) {
            for (Expression argument : call.arguments()) {
                collectComponentOutputReferences(argument, outputs);
            }
        } else if (expression instanceof Expression.PartCall call) {
            for (Expression argument : call.arguments()) {
                collectComponentOutputReferences(argument, outputs);
            }
        }
    }

    private static String componentRequestFormatError(List<String> requiredComponents) {
        return "This request explicitly requires boxed library component(s) "
                + requiredComponents + ". A template-shaped answer cannot satisfy that requirement. "
                + "Return kind:\"expressions\" with summary, inputs, outputs and instances; "
                + "instantiate the requested component(s) and connect their named output pins. "
                + "Do not include a template field or substitute a gate-only circuit.";
    }

    private static String templateNotMatchedError(Set<String> receivedFields) {
        return "A template response was received, but no supported template matched this request. "
                + "Allowed fields for an expressions answer: [summary, inputs, outputs, signals, instances, spec]. "
                + "Fields sent: " + new java.util.TreeSet<>(receivedFields)
                + ". Return a complete expressions object with summary, inputs, outputs and optional signals, "
                + "instances and spec; do not return a template field.";
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
        for (InstanceSpec instance : answer.instances()) {
            for (String argument : instance.args()) {
                pending.addAll(ExpressionParser.parse(argument).variables());
            }
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
        if (!answer.instances().isEmpty()) {
            JsonArray instances = new JsonArray();
            for (InstanceSpec instance : answer.instances()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", instance.name());
                item.addProperty("component", instance.component());
                JsonArray args = new JsonArray();
                instance.args().forEach(args::add);
                item.add("args", args);
                instances.add(item);
            }
            root.add("instances", instances);
        }
        if (answer.specification() != null) root.add("spec", answer.specification().deepCopy());
        return root.toString();
    }

    private static Answer parseAnswer(String raw, boolean allowInstances) {
        String jsonText = stripCodeFence(Objects.requireNonNull(raw, "model response"));
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        Set<String> required = Set.of("summary", "inputs", "outputs");
        Set<String> allowed = new LinkedHashSet<>(
                Set.of("summary", "inputs", "outputs", "signals", "spec", "truthTable"));
        if (allowInstances) allowed.add("instances");
        Set<String> actual = new LinkedHashSet<>(root.keySet());
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(allowed);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalArgumentException("response fields invalid for an expressions answer; "
                    + "allowed fields: " + new java.util.TreeSet<>(allowed)
                    + "; fields sent: " + new java.util.TreeSet<>(actual)
                    + "; missing=" + new java.util.TreeSet<>(missing)
                    + ", unexpected=" + new java.util.TreeSet<>(unexpected)
                    + ". Expected shape: {\"summary\":\"...\",\"inputs\":[\"A\"],"
                    + "\"outputs\":[{\"name\":\"Y\",\"expression\":\"A & B\"}],"
                    + "\"signals\":[{\"name\":\"C1\",\"expression\":\"A & B\"}]?,"
                    + "\"instances\":[{\"name\":\"U0\",\"component\":\"FA\",\"args\":[\"A\",\"B\",\"CIN\"]}]?,"
                    + "\"spec\":{...}?}. Include required fields and no unrelated fields.");
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

        List<InstanceSpec> instances = new ArrayList<>();
        JsonElement instancesElement = root.get("instances");
        if (instancesElement != null) {
            if (!instancesElement.isJsonArray()) {
                throw new IllegalArgumentException("instances must be an array");
            }
            if (instancesElement.getAsJsonArray().size() > AiCircuitLimits.MAX_INSTANCES) {
                throw new IllegalArgumentException("instances has "
                        + instancesElement.getAsJsonArray().size() + " entries; maximum is "
                        + AiCircuitLimits.MAX_INSTANCES);
            }
            Set<String> namespace = new LinkedHashSet<>(seenInputs);
            namespace.addAll(signalNames);
            for (JsonElement item : instancesElement.getAsJsonArray()) {
                if (!item.isJsonObject()) throw new IllegalArgumentException("each instance must be an object");
                JsonObject instance = item.getAsJsonObject();
                requireExactKeys(instance, Set.of("name", "component", "args"), "instance");
                String name = requireString(instance.get("name"), "instance name");
                String component = requireString(instance.get("component"), "instance component");
                requireName(name, "instance name");
                requireName(component, "instance component");
                if (!namespace.add(name)) {
                    throw new IllegalArgumentException("duplicate input/signal/instance name: " + name);
                }
                JsonElement argsElement = instance.get("args");
                if (argsElement == null || !argsElement.isJsonArray()) {
                    throw new IllegalArgumentException("args for instance " + name + " must be an array");
                }
                List<String> args = new ArrayList<>();
                for (JsonElement arg : argsElement.getAsJsonArray()) {
                    String expression = requireString(arg, "argument for instance " + name);
                    if (expression.isBlank()) {
                        throw new IllegalArgumentException("argument for instance " + name + " is empty");
                    }
                    requireExpressionLength(expression, "argument for instance '" + name + "'");
                    totalExpressionLength += expression.length();
                    args.add(expression);
                }
                instances.add(new InstanceSpec(name, component, args));
            }
        }
        if (totalExpressionLength > AiCircuitLimits.MAX_EXPRESSION_TEXT) {
            throw new IllegalArgumentException("total expression text exceeds "
                    + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters");
        }
        for (String outputName : outputNames) {
            if (seenInputs.contains(outputName) || seenSignals.contains(outputName)
                    || instances.stream().anyMatch(instance -> instance.name().equals(outputName))) {
                throw new IllegalArgumentException("output name conflicts with another namespace name: "
                        + outputName);
            }
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
                List.copyOf(outputExpressions), List.copyOf(instances), specification);
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
