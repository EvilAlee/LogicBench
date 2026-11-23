package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SequenceRunner;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.Expression;
import io.github.evilalee.gatelab.synth.ExpressionParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separate strict AI contract and engine verification path for clocked,
 * stateful circuits. The combinational AI schema and compiler are untouched.
 */
public final class AiSequentialCircuitService {
    private static final int MAX_INPUTS = 8;
    private static final int MAX_OUTPUTS = 8;
    private static final int MAX_INSTANCES = 16;
    private static final int MAX_STEPS = 40;
    private static final int MAX_CYCLES = 8;
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern RESET_NAME =
            Pattern.compile("(?i)^(RESET|RST|CLEAR|CLR)(_?N)?$");

    private final AiProvider provider;
    private final List<GateDef> gateDefinitions;
    private final List<ComponentDefinition> componentDefinitions;
    private final AiCache cache;
    private volatile String lastRawAnswer = "";

    public AiSequentialCircuitService(
            AiProvider provider,
            Collection<GateDef> gateDefinitions,
            Collection<ComponentDefinition> componentDefinitions,
            AiCache cache) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.gateDefinitions = List.copyOf(Objects.requireNonNull(gateDefinitions));
        this.componentDefinitions = List.copyOf(Objects.requireNonNull(componentDefinitions));
        this.cache = cache;
    }

    public String lastRawAnswer() {
        return lastRawAnswer;
    }

    public static JsonObject responseSchema() {
        JsonObject root = objectSchema();
        JsonObject properties = root.getAsJsonObject("properties");
        JsonObject kind = stringSchema();
        kind.add("enum", strings("sequential"));
        properties.add("kind", kind);
        properties.add("summary", stringSchema());
        properties.add("clock", stringSchema());
        properties.add("inputs", arraySchema(stringSchema()));
        properties.add("outputs", arraySchema(namedExpressionSchema()));
        properties.add("signals", arraySchema(namedExpressionSchema()));
        properties.add("instances", arraySchema(instanceSchema()));

        JsonObject binaryValue = numberSchema();
        binaryValue.add("enum", numbers(0, 1));
        JsonObject assignments = objectSchema();
        assignments.add("additionalProperties", binaryValue);

        JsonObject step = objectSchema();
        step.getAsJsonObject("properties").add("set", assignments);
        step.getAsJsonObject("properties").add("cycles", integerSchema(0, MAX_CYCLES));
        step.getAsJsonObject("properties").add("expect", assignments.deepCopy());
        step.add("required", strings("set", "cycles", "expect"));
        JsonObject sequence = arraySchema(step);
        sequence.addProperty("minItems", 1);
        sequence.addProperty("maxItems", MAX_STEPS);
        properties.add("sequence", sequence);
        root.add("required", strings(
                "kind", "summary", "clock", "inputs", "outputs",
                "signals", "instances", "sequence"));
        root.addProperty("additionalProperties", false);
        return root;
    }

    /**
     * The existing combinational instructions are deliberately reused verbatim
     * and only followed by this request-kind-specific contract.
     */
    public static String systemPrompt(
            Collection<ComponentDefinition> definitions, String request) {
        String catalog = sequentialCatalog(definitions, request);
        return AiCircuitService.systemPrompt() + """

                SEQUENTIAL CIRCUITS — this request was classified by LogicBench as sequential.
                Return JSON only with kind "sequential" and exactly these required fields:
                {kind, summary, clock, inputs, outputs, signals, instances, sequence}.
                Do not include a template, spec, truthTable, gate list, or any other field.
                clock is one identifier. It is a Clock source name, not a primary input and
                must never be declared by a signal or expression. Every DFF CLK argument
                must be this exact identifier as a bare name. Use one clock only.
                inputs are the non-clock, single-bit inputs. outputs are named expression
                values. signals are named intermediate Boolean expressions. instances are
                boxed library calls with name, component and ordered args.
                Expressions and arguments may refer to inputs, signals, and any instance
                output, including their own or later instances. Feedback is allowed only
                when every combinational cycle passes through a DFF or DLATCH. A cycle made
                only from signals, gates, or combinational boxes is invalid.
                DFF and DLATCH are the only permitted sequential components. Do not use TFF
                or JKFF: their state cannot be forced to a known value from the simulator's
                all-X startup. Implement a toggle with a DFF using D = Q XOR T, gated by
                reset, for example D = RESET_N & (Q ^ T).
                Every DFF/DLATCH D input must have a path from a declared reset input so
                the first reset step can force a known state. Prefer active-low RESET_N and
                D = RESET_N & NEXT_VALUE. A reset input with no path to D is not a reset.
                The first sequence step must assign every non-clock input, assert reset at
                its active level, and run at least one cycle. Use reset low for RESET_N/RST_N/
                CLEAR_N/CLR_N and reset high for RESET/RST/CLEAR/CLR.
                sequence contains 1..40 steps. Each step has exactly set, cycles, expect.
                set assigns only declared non-clock inputs with numeric 0 or 1; unspecified
                values retain their previous value. cycles is an integer from 0 through 8.
                expect assigns one or more declared outputs with numeric 0 or 1 only.
                Include expected values for the output registers at every step. Outputs are
                sampled after the final low clock phase. X never counts as either 0 or 1.

                Limits: at most 8 non-clock inputs, 8 outputs, 160 signals, 16 instances,
                400 gates after boxes are flattened, 300 characters per expression, 3000
                total expression characters and 40 sequence steps. Use only DFF/DLATCH as
                sequential boxes; ordinary combinational boxes such as FA remain available.
                Keep all names unique across clock, inputs, signals, instances and outputs.

                Worked example 1 — 2-bit counter using two DFF boxes:
                {
                  "kind":"sequential",
                  "summary":"2-bit modulo-four counter",
                  "clock":"CLK",
                  "inputs":["RESET_N"],
                  "signals":[],
                  "instances":[
                    {"name":"U0","component":"DFF","args":["RESET_N & NOT(U0.Q)","CLK"]},
                    {"name":"U1","component":"DFF","args":["RESET_N & (U1.Q ^ U0.Q)","CLK"]}
                  ],
                  "outputs":[
                    {"name":"Q0","expression":"U0.Q"},
                    {"name":"Q1","expression":"U1.Q"}
                  ],
                  "sequence":[
                    {"set":{"RESET_N":0},"cycles":1,"expect":{"Q0":0,"Q1":0}},
                    {"set":{"RESET_N":1},"cycles":1,"expect":{"Q0":1,"Q1":0}},
                    {"set":{"RESET_N":1},"cycles":1,"expect":{"Q0":0,"Q1":1}},
                    {"set":{"RESET_N":1},"cycles":1,"expect":{"Q0":1,"Q1":1}},
                    {"set":{"RESET_N":1},"cycles":1,"expect":{"Q0":0,"Q1":0}}
                  ]
                }

                Worked example 2 — 4-bit accumulator using four FA boxes and four DFF boxes:
                {
                  "kind":"sequential",
                  "summary":"4-bit accumulator",
                  "clock":"CLK",
                  "inputs":["RESET_N","IN3","IN2","IN1","IN0"],
                  "signals":[
                    {"name":"Q0_FB","expression":"U4.Q"},
                    {"name":"Q1_FB","expression":"U5.Q"},
                    {"name":"Q2_FB","expression":"U6.Q"},
                    {"name":"Q3_FB","expression":"U7.Q"}
                  ],
                  "instances":[
                    {"name":"U0","component":"FA","args":["Q0_FB","IN0","0"]},
                    {"name":"U1","component":"FA","args":["Q1_FB","IN1","U0.COUT"]},
                    {"name":"U2","component":"FA","args":["Q2_FB","IN2","U1.COUT"]},
                    {"name":"U3","component":"FA","args":["Q3_FB","IN3","U2.COUT"]},
                    {"name":"U4","component":"DFF","args":["RESET_N & U0.S","CLK"]},
                    {"name":"U5","component":"DFF","args":["RESET_N & U1.S","CLK"]},
                    {"name":"U6","component":"DFF","args":["RESET_N & U2.S","CLK"]},
                    {"name":"U7","component":"DFF","args":["RESET_N & U3.S","CLK"]}
                  ],
                  "outputs":[
                    {"name":"Q0","expression":"U4.Q"},
                    {"name":"Q1","expression":"U5.Q"},
                    {"name":"Q2","expression":"U6.Q"},
                    {"name":"Q3","expression":"U7.Q"}
                  ],
                  "sequence":[
                    {"set":{"RESET_N":0,"IN3":0,"IN2":0,"IN1":0,"IN0":0},
                     "cycles":1,"expect":{"Q3":0,"Q2":0,"Q1":0,"Q0":0}},
                    {"set":{"RESET_N":1,"IN3":0,"IN2":0,"IN1":1,"IN0":1},
                     "cycles":1,"expect":{"Q3":0,"Q2":0,"Q1":1,"Q0":1}},
                    {"set":{"RESET_N":1,"IN3":0,"IN2":1,"IN1":0,"IN0":1},
                     "cycles":1,"expect":{"Q3":1,"Q2":0,"Q1":0,"Q0":0}}
                  ]
                }

                The examples establish the required response shape and state feedback. Use
                the actual requested width and behavior; do not copy example pin names when
                they do not match the user's request.
                """ + "\n\n" + catalog;
    }

    public Outcome generate(String description, boolean cacheOnly,
                            boolean bypassCache, boolean invertingGates)
            throws InterruptedException {
        Objects.requireNonNull(description, "description");
        lastRawAnswer = "";
        List<String> log = new ArrayList<>();

        if (!bypassCache && cache != null) {
            for (AiCache.CacheHit hit : cache.lookupCandidates(description)) {
                Attempt cached = validate(provider.redact(hit.answerJson()), invertingGates);
                if (cached.passed()) {
                    lastRawAnswer = provider.redact(hit.answerJson());
                    log.add("FROM CACHE: Revalidated the complete sequential sequence.");
                    log.add("VERIFIED: matches the sequence in the answer");
                    return toOutcome(0, AiCircuitService.Status.VERIFIED, cached,
                            log, true, provider.redact(hit.model()), List.of());
                }
                List<String> invalid = cached.feedback();
                if (invalid.stream().anyMatch(line ->
                        line.toLowerCase(java.util.Locale.ROOT).contains("component")
                                && line.toLowerCase(java.util.Locale.ROOT).contains("not found"))) {
                    log.add("Ignored cached sequential answer because a component no longer exists: "
                            + String.join("; ", invalid.stream().limit(3).toList()));
                } else {
                    log.add("Ignored invalid cached sequential answer: "
                            + String.join("; ", invalid.stream().limit(3).toList()));
                }
            }
        }

        if (cacheOnly) {
            log.add("AI unavailable: cache-only mode is enabled and no valid sequential answer exists.");
            return unavailable("No valid cached sequential answer is available offline.", log);
        }

        String safeDescription = provider.redact(description);
        String prompt = systemPrompt(componentDefinitions, description);
        JsonObject schema = responseSchema();
        List<Turn> conversation = new ArrayList<>();
        conversation.add(new Turn(Turn.Role.USER, safeDescription));
        List<String> modelAttempts = new ArrayList<>();
        Attempt best = null;
        String bestModel = "";

        for (int attemptNumber = 1; attemptNumber <= AiCircuitLimits.MAX_ATTEMPTS; attemptNumber++) {
            ModelReply reply;
            try {
                reply = complete(prompt, schema, conversation, modelAttempts, log);
            } catch (IOException exception) {
                String reason = provider.redact(oneLine(exception.getMessage()));
                log.add("AI unavailable: " + (reason.isBlank()
                        ? "provider request failed." : reason));
                return unavailable(reason, log, modelAttempts, attemptNumber - 1);
            }
            String raw = provider.redact(reply.raw());
            lastRawAnswer = raw;
            conversation.add(new Turn(Turn.Role.MODEL, raw));
            Attempt current = validate(raw, invertingGates);
            if (best == null || current.score() < best.score()) {
                best = current;
                bestModel = reply.model();
            }
            if (current.passed()) {
                log.add("VERIFIED: matches the sequence in the answer");
                log.add("Verified " + current.data().steps().size()
                        + " sequence steps through the LogicBench engine.");
                if (cache != null) {
                    try {
                        cache.storeVerified(description, serialize(current.answer()), reply.model());
                        log.add("Saved verified sequential answer in the normal AI cache.");
                    } catch (IOException exception) {
                        log.add("Verified the sequence, but could not save the user cache.");
                    }
                }
                return toOutcome(attemptNumber, AiCircuitService.Status.VERIFIED,
                        current, log, false, reply.model(), modelAttempts);
            }

            List<String> feedback = current.feedback();
            if (feedback.isEmpty()) feedback = List.of("The sequential validation did not pass.");
            String retry = String.join("\n", feedback.stream().limit(8).toList());
            log.add("Validation failed: " + retry
                    + (attemptNumber < AiCircuitLimits.MAX_ATTEMPTS ? "; retrying" : ""));
            if (attemptNumber < AiCircuitLimits.MAX_ATTEMPTS) {
                conversation.add(new Turn(Turn.Role.USER,
                        "Your previous JSON was:\n" + raw
                                + "\nCorrect the sequential circuit. These named validation errors "
                                + "come from our compiler and simulator:\n" + retry
                                + "\nReturn the complete corrected sequential JSON object only."));
            }
        }

        if (best == null) {
            return unavailable("No model answer was received.", log, modelAttempts, 0);
        }
        return toOutcome(AiCircuitLimits.MAX_ATTEMPTS, AiCircuitService.Status.UNVERIFIED,
                best, log, false, bestModel, modelAttempts);
    }

    private ModelReply complete(String prompt, JsonObject schema,
                                List<Turn> conversation, List<String> attempts,
                                List<String> log) throws IOException, InterruptedException {
        List<String> models = provider.models();
        if (models == null || models.isEmpty()) models = List.of("default");
        IOException last = null;
        Set<String> seen = new LinkedHashSet<>();
        for (String configured : models) {
            String model = configured == null || configured.isBlank() ? "default" : configured;
            if (!seen.add(model)) continue;
            try {
                String raw = provider.complete(model, prompt, conversation, schema.deepCopy());
                attempts.add(provider.redact(model) + " (response received)");
                return new ModelReply(raw, model);
            } catch (IOException exception) {
                last = exception;
                String message = provider.redact(oneLine(exception.getMessage()));
                attempts.add(provider.redact(model) + " (" + (message.isBlank()
                        ? "request failed" : message) + ")");
                log.add("Sequential provider model " + provider.redact(model) + " failed: "
                        + (message.isBlank() ? "request failed." : message));
            }
        }
        throw last == null ? new IOException("No configured provider model is available.") : last;
    }

    private Attempt validate(String raw, boolean invertingGates) {
        Answer answer;
        try {
            answer = parse(raw);
        } catch (RuntimeException exception) {
            return Attempt.failed(List.of("Sequential JSON/schema error: "
                    + provider.redact(oneLine(exception.getMessage()))));
        }

        List<String> errors = validateComponents(answer);
        if (!errors.isEmpty()) return Attempt.failed(answer, errors);

        List<String> cycle = combinationalCycle(answer);
        if (!cycle.isEmpty()) {
            return Attempt.failed(answer, List.of(
                    "Combinational feedback cycle does not pass through DFF or DLATCH: "
                            + String.join(" -> ", cycle) + ". Break the cycle with a DFF or DLATCH."));
        }

        List<String> resetValidationErrors = resetErrors(answer);
        String source = expressionSource(answer);
        ComponentExpressionCompiler.Compilation compilation;
        try {
            compilation = ComponentExpressionCompiler.compile(
                    source, gateDefinitions, componentDefinitions,
                    ComponentExpressionCompiler.CompileOption.BOXED_INSTANCES,
                    true, invertingGates);
        } catch (RuntimeException exception) {
            if (Boolean.getBoolean("logicbench.debugSequential")) {
                exception.printStackTrace(System.out);
            }
            return Attempt.failed(answer, List.of("Sequential component compilation failed: "
                    + provider.redact(oneLine(exception.getMessage()))), source);
        }

        Set<String> declaredInputs = new LinkedHashSet<>(answer.inputs());
        Set<String> actualInputs = new LinkedHashSet<>(compilation.inputComponentIds().keySet());
        if (!declaredInputs.equals(actualInputs)) {
            Set<String> missing = new LinkedHashSet<>(actualInputs);
            missing.removeAll(declaredInputs);
            Set<String> unused = new LinkedHashSet<>(declaredInputs);
            unused.removeAll(actualInputs);
            List<String> inputErrors = new ArrayList<>();
            missing.forEach(name -> inputErrors.add(
                    "Compiler-created input '" + name + "' is not declared in inputs."));
            unused.forEach(name -> inputErrors.add(
                    "Declared input '" + name + "' is not used by the circuit."));
            List<String> combined = new ArrayList<>(resetValidationErrors);
            combined.addAll(inputErrors);
            return Attempt.failed(answer, combined, source);
        }
        if (compilation.clockComponentIds().size() > 1
                || (!compilation.clockComponentIds().isEmpty()
                    && !compilation.clockComponentIds().containsKey(answer.clock()))) {
            return Attempt.failed(answer, List.of(
                    "The circuit must use exactly one clock named '" + answer.clock()
                            + "'; found clock sources " + compilation.clockComponentIds().keySet()), source);
        }

        Schematic schematic = compilation.schematic();
        if (compilation.clockComponentIds().isEmpty()) {
            GateDef clockDefinition = gateDefinitions.stream()
                    .filter(definition -> definition.family().equals("clock")
                            && definition.inputCount() == 0
                            && definition.size().equals("narrow"))
                    .findFirst().orElse(null);
            if (clockDefinition == null) {
                return Attempt.failed(answer, List.of(
                        "The gate catalogue has no Clock component for sequence execution."), source);
            }
            schematic.add(new PlacedComponent(
                    "ai-sequence-clock", "clock", 0, 0, Facing.EAST,
                    Map.of(), answer.clock()));
        }

        SchematicCompiler.CompiledCircuit flattened;
        try {
            flattened = SchematicCompiler.compileCircuit(schematic);
        } catch (RuntimeException exception) {
            return Attempt.failed(answer, List.of("Component flattening failed: "
                    + provider.redact(oneLine(exception.getMessage()))), source);
        }
        long gateCount = flattened.circuit().components().stream()
                .filter(Gate.class::isInstance).count();
        if (gateCount > AiCircuitLimits.MAX_GATES) {
            return Attempt.failed(answer, List.of("Flattened circuit has " + gateCount
                    + " gates; maximum is " + AiCircuitLimits.MAX_GATES), source);
        }

        Map<String, InputPin> namedInputs = new LinkedHashMap<>();
        for (String name : answer.inputs()) {
            String id = compilation.inputComponentIds().get(name);
            InputPin input = flattened.inputs().get(id);
            if (input == null) {
                return Attempt.failed(answer, List.of(
                        "Flattened circuit is missing declared input '" + name + "'."), source);
            }
            namedInputs.put(name, input);
        }
        String clockId = compilation.clockComponentIds().get(answer.clock());
        if (clockId == null) {
            clockId = schematic.components().stream()
                    .filter(component -> component.typeId().equals("clock")
                            && component.label().equals(answer.clock()))
                    .map(PlacedComponent::id).findFirst().orElse(null);
        }
        InputPin clockPin = clockId == null ? null : flattened.inputs().get(clockId);
        if (clockPin == null) {
            return Attempt.failed(answer, List.of(
                    "The Clock component '" + answer.clock() + "' was not found after flattening."), source);
        }
        namedInputs.put(answer.clock(), clockPin);

        Map<String, OutputProbe> namedOutputs = new LinkedHashMap<>();
        for (OutputSpec output : answer.outputs()) {
            String id = compilation.outputComponentIds().get(output.name());
            OutputProbe probe = flattened.outputs().get(id);
            if (probe == null) {
                return Attempt.failed(answer, List.of(
                        "Flattened circuit is missing output '" + output.name() + "'."), source);
            }
            namedOutputs.put(output.name(), probe);
        }

        List<String> resetErrors = new ArrayList<>(resetValidationErrors);
        List<SequenceRunner.Step> runnerSteps = new ArrayList<>();
        for (int index = 0; index < answer.sequence().size(); index++) {
            SequenceStep step = answer.sequence().get(index);
            runnerSteps.add(new SequenceRunner.Step("Step " + (index + 1),
                    step.set(), step.cycles(), step.expect()));
        }

        SequenceRunner.SequenceResult observed;
        try {
            observed = new SequenceRunner(flattened.circuit(), namedInputs,
                    answer.clock(), namedOutputs, flattened.fixedInputs()).run(runnerSteps);
        } catch (RuntimeException exception) {
            return Attempt.failed(answer, List.of("Sequence execution failed: "
                    + provider.redact(oneLine(exception.getMessage()))), source);
        }

        List<StepView> views = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        if (!observed.steps().isEmpty()) {
            SequenceRunner.StepObservation resetObservation = observed.steps().getFirst();
            List<String> unknownAfterReset = resetObservation.observedOutputs().entrySet().stream()
                    .filter(entry -> entry.getValue() == Value.X)
                    .map(Map.Entry::getKey).toList();
            if (!unknownAfterReset.isEmpty()
                    && resetErrors.stream().noneMatch(line -> line.contains("did not establish"))) {
                resetErrors.add("The first reset step did not establish a known state: "
                        + unknownAfterReset + " remain X. Ensure reset reaches every D path "
                        + "(for example D = RESET_N & ...).");
            }
        }
        for (int index = 0; index < observed.steps().size(); index++) {
            SequenceRunner.StepObservation row = observed.steps().get(index);
            SequenceStep step = answer.sequence().get(index);
            Set<String> mismatchNames = new LinkedHashSet<>();
            row.mismatches().forEach(mismatch -> mismatchNames.add(mismatch.outputName()));
            views.add(new StepView(index + 1, step.set(), step.cycles(),
                    step.expect(), row.observedOutputs(), mismatchNames));
            for (SequenceRunner.Mismatch mismatch : row.mismatches()) {
                if (mismatches.size() >= 8) break;
                mismatches.add(formatMismatch(answer, step, index, mismatch));
            }
            if (mismatches.size() >= 8) break;
        }
        // Preserve every row for the dialog even when only eight mismatch lines
        // are sent back to the model.
        if (views.size() != observed.steps().size()) {
            views.clear();
            for (int index = 0; index < observed.steps().size(); index++) {
                SequenceRunner.StepObservation row = observed.steps().get(index);
                SequenceStep step = answer.sequence().get(index);
                Set<String> mismatchNames = new LinkedHashSet<>();
                row.mismatches().forEach(mismatch -> mismatchNames.add(mismatch.outputName()));
                views.add(new StepView(index + 1, step.set(), step.cycles(),
                        step.expect(), row.observedOutputs(), mismatchNames));
            }
        }

        List<String> feedback = new ArrayList<>(resetErrors);
        feedback.addAll(mismatches);
        Map<String, Integer> componentUseCounts = new LinkedHashMap<>();
        answer.instances().forEach(instance ->
                componentUseCounts.merge(instance.component(), 1, Integer::sum));
        SequenceData data = new SequenceData(
                answer.clock(), answer.inputs(), answer.outputs().stream()
                        .map(OutputSpec::name).toList(), views);
        return new Attempt(answer, schematic, data, source,
                componentUseCounts, feedback, mismatches);
    }

    private List<String> validateComponents(Answer answer) {
        List<String> errors = new ArrayList<>();
        Map<String, ComponentDefinition> byName = new LinkedHashMap<>();
        componentDefinitions.forEach(definition -> byName.put(definition.name(), definition));
        Map<String, InstanceSpec> instances = new LinkedHashMap<>();
        answer.instances().forEach(instance -> instances.put(instance.name(), instance));

        for (OutputSpec output : answer.outputs()) {
            try {
                Expression expression = ExpressionParser.parse(output.expression());
                if (expression.variables().contains(answer.clock())
                        || containsComponentOutput(expression, answer.clock())) {
                    errors.add("Clock '" + answer.clock()
                            + "' may appear only as the CLK argument of a DFF.");
                }
            } catch (RuntimeException ignored) {
                // The component compiler reports the parser's detailed position.
            }
        }

        for (InstanceSpec instance : answer.instances()) {
            ComponentDefinition definition = byName.get(instance.component());
            if (definition == null) {
                errors.add("Component '" + instance.component() + "' for " + instance.name()
                        + " was not found in the current library.");
                continue;
            }
            if (instance.component().equals("TFF")) {
                errors.add("TFF cannot be reset from the all-X start. Use a DFF with D = Q XOR T "
                        + "(for example D = RESET_N & (Q ^ T)) and include reset on the D path.");
                continue;
            }
            if (instance.component().equals("JKFF")) {
                errors.add("JKFF cannot be reset from the all-X start. Use a DFF with "
                        + "D = (J & NOT(Q)) | (NOT(K) & Q), gated by the reset path.");
                continue;
            }
            boolean sequential = AiComponentCatalog.isSequential(definition);
            if (sequential && !Set.of("DFF", "DLATCH").contains(definition.name())) {
                errors.add("Only DFF and DLATCH are supported sequential components; "
                        + definition.name() + " is not permitted.");
                continue;
            }
            if (!sequential && !AiComponentCatalog.isCombinational(definition)) {
                errors.add("Component " + definition.name()
                        + " contains feedback and is not a supported combinational box.");
                continue;
            }
            if (sequential && !Set.of("DFF", "DLATCH").contains(instance.component())) {
                errors.add("Only DFF and DLATCH are supported sequential components.");
                continue;
            }
            if (instance.args().size() != definition.inputPorts().size()) {
                errors.add("Instance " + instance.name() + " calls " + definition.name()
                        + " with " + instance.args().size() + " arguments; expected "
                        + definition.inputPorts().stream()
                                .map(ComponentDefinition.Port::name).toList() + ".");
                continue;
            }
            for (int index = 0; index < instance.args().size(); index++) {
                try {
                    ExpressionParser.parse(instance.args().get(index));
                } catch (RuntimeException exception) {
                    errors.add("Instance " + instance.name() + " argument "
                            + definition.inputPorts().get(index).name()
                            + " has invalid expression: " + provider.redact(oneLine(exception.getMessage())));
                }
            }
            for (int index = 0; index < definition.inputPorts().size(); index++) {
                ComponentDefinition.Port port = definition.inputPorts().get(index);
                if (port.isClock() && !instance.args().get(index).trim().equals(answer.clock())) {
                    errors.add("Every clocked component must use the single clock '"
                            + answer.clock() + "' as its plain CLK argument; "
                            + instance.name() + "." + port.name() + " uses '"
                            + instance.args().get(index) + "'.");
                }
            }
        }

        if (answer.clock().isBlank() || !NAME.matcher(answer.clock()).matches()) {
            errors.add("clock must be one valid identifier.");
        }
        if (answer.inputs().contains(answer.clock())) {
            errors.add("Clock '" + answer.clock()
                    + "' is a Clock component, not a primary input; remove it from inputs.");
        }
        if (answer.signals().stream().anyMatch(signal -> signal.name().equals(answer.clock()))
                || instances.containsKey(answer.clock())) {
            errors.add("Clock '" + answer.clock()
                    + "' must not be defined by a signal or an instance expression.");
        }

        for (SignalSpec signal : answer.signals()) {
            try {
                Expression expression = ExpressionParser.parse(signal.expression());
                if (expression.variables().contains(answer.clock())
                        || containsComponentOutput(expression, answer.clock())) {
                    errors.add("Clock '" + answer.clock()
                            + "' may appear only as the CLK argument of a DFF.");
                }
            } catch (RuntimeException ignored) {
                // The component compiler reports the parser's detailed position.
            }
        }
        for (InstanceSpec instance : answer.instances()) {
            ComponentDefinition definition = byName.get(instance.component());
            if (definition == null) continue;
            for (int index = 0; index < instance.args().size(); index++) {
                ComponentDefinition.Port port = index < definition.inputPorts().size()
                        ? definition.inputPorts().get(index) : null;
                if (port != null && port.isClock()) continue;
                try {
                    Expression expression = ExpressionParser.parse(instance.args().get(index));
                    if (expression.variables().contains(answer.clock())
                            || containsComponentOutput(expression, answer.clock())) {
                        errors.add("Clock '" + answer.clock()
                                + "' may appear only as the CLK argument of a DFF.");
                    }
                } catch (RuntimeException ignored) {
                    // Reported by the expression compiler below.
                }
            }
        }

        return List.copyOf(errors);
    }

    private List<String> resetErrors(Answer answer) {
        List<String> errors = new ArrayList<>();
        List<String> resetInputs = answer.inputs().stream()
                .filter(name -> RESET_NAME.matcher(name).matches()).toList();
        if (resetInputs.isEmpty()) {
            errors.add("No reset input can force a known state from the all-X start. "
                    + "Add RESET_N and make it reach every D path (for example D = RESET_N & ...).");
            return List.copyOf(errors);
        }
        if (resetInputs.size() > 1) {
            errors.add("More than one reset input is ambiguous: " + resetInputs
                    + ". Use one reset signal for the D paths.");
            return List.copyOf(errors);
        }

        String reset = resetInputs.getFirst();
        boolean activeLow = reset.toUpperCase(java.util.Locale.ROOT).endsWith("N");
        Value activeValue = activeLow ? Value.ZERO : Value.ONE;
        Map<String, SignalSpec> signals = new LinkedHashMap<>();
        answer.signals().forEach(signal -> signals.put(signal.name(), signal));
        Map<String, InstanceSpec> instances = new LinkedHashMap<>();
        answer.instances().forEach(instance -> instances.put(instance.name(), instance));
        Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
        componentDefinitions.forEach(definition -> definitions.put(definition.name(), definition));

        for (InstanceSpec instance : answer.instances()) {
            if (!Set.of("DFF", "DLATCH").contains(instance.component())) continue;
            ComponentDefinition definition = definitions.get(instance.component());
            if (definition == null) continue;
            int dIndex = -1;
            for (int index = 0; index < definition.inputPorts().size(); index++) {
                if (definition.inputPorts().get(index).name().equals("D")) dIndex = index;
            }
            if (dIndex < 0 || dIndex >= instance.args().size()
                    || !dependsOnInput(instance.args().get(dIndex), reset,
                            signals, instances, definitions, new HashSet<>())) {
                errors.add("Reset '" + reset + "' must reach the D path of "
                        + instance.name() + " (for example D = " + reset + " & ...).");
            }
        }

        SequenceStep first = answer.sequence().getFirst();
        Set<String> missing = new LinkedHashSet<>(answer.inputs());
        missing.removeAll(first.set().keySet());
        if (!missing.isEmpty()) {
            errors.add("The first reset step must set every non-clock input; missing " + missing + ".");
        }
        if (first.set().get(reset) != activeValue) {
            errors.add("The first sequence step must hold reset '" + reset + "' at its active level "
                    + (activeLow ? "0" : "1") + ".");
        }
        if (first.cycles() < 1) {
            errors.add("The first reset step must run reset for at least one full clock cycle.");
        }
        return List.copyOf(errors);
    }

    private boolean dependsOnInput(
            String expressionText, String inputName,
            Map<String, SignalSpec> signals, Map<String, InstanceSpec> instances,
            Map<String, ComponentDefinition> definitions, Set<String> visited) {
        Expression expression;
        try {
            expression = ExpressionParser.parse(expressionText);
        } catch (RuntimeException exception) {
            return false;
        }
        return dependsOn(expression, inputName, signals, instances, definitions, visited);
    }

    private boolean dependsOn(
            Expression expression, String inputName,
            Map<String, SignalSpec> signals, Map<String, InstanceSpec> instances,
            Map<String, ComponentDefinition> definitions, Set<String> visited) {
        if (expression instanceof Expression.Variable variable) {
            if (variable.name().equals(inputName)) return true;
            SignalSpec signal = signals.get(variable.name());
            if (signal != null && visited.add("signal:" + variable.name())) {
                return dependsOnInput(signal.expression(), inputName,
                        signals, instances, definitions, visited);
            }
            InstanceSpec instance = instances.get(variable.name());
            if (instance != null && visited.add("instance:" + variable.name())) {
                return instanceDDependsOn(instance, inputName,
                        signals, instances, definitions, visited);
            }
            return false;
        }
        if (expression instanceof Expression.ComponentOutput output) {
            InstanceSpec instance = instances.get(output.instanceName());
            return instance != null && visited.add("instance:" + output.instanceName())
                    && instanceDDependsOn(instance, inputName,
                            signals, instances, definitions, visited);
        }
        if (expression instanceof Expression.Unary unary) {
            return dependsOn(unary.operand(), inputName, signals, instances, definitions, visited);
        }
        if (expression instanceof Expression.Binary binary) {
            return dependsOn(binary.left(), inputName, signals, instances, definitions, visited)
                    || dependsOn(binary.right(), inputName, signals, instances, definitions, visited);
        }
        if (expression instanceof Expression.ComponentCall call) {
            return call.arguments().stream().anyMatch(argument ->
                    dependsOn(argument, inputName, signals, instances, definitions, visited));
        }
        if (expression instanceof Expression.PartCall call) {
            return call.arguments().stream().anyMatch(argument ->
                    dependsOn(argument, inputName, signals, instances, definitions, visited));
        }
        return false;
    }

    private boolean instanceDDependsOn(
            InstanceSpec instance, String inputName,
            Map<String, SignalSpec> signals, Map<String, InstanceSpec> instances,
            Map<String, ComponentDefinition> definitions, Set<String> visited) {
        ComponentDefinition definition = definitions.get(instance.component());
        if (definition == null) return false;
        for (int index = 0; index < definition.inputPorts().size(); index++) {
            if (definition.inputPorts().get(index).name().equals("D")) {
                return dependsOnInput(instance.args().get(index), inputName,
                        signals, instances, definitions, visited);
            }
        }
        return false;
    }

    private List<String> combinationalCycle(Answer answer) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Set<String> sequentialNodes = new HashSet<>();
        for (SignalSpec signal : answer.signals()) {
            graph.put("signal:" + signal.name(), dependencies(
                    parseOrEmpty(signal.expression()), answer));
        }
        for (InstanceSpec instance : answer.instances()) {
            String node = "instance:" + instance.name();
            graph.put(node, new LinkedHashSet<>());
            if (Set.of("DFF", "DLATCH").contains(instance.component())) {
                sequentialNodes.add(node);
            }
            for (String argument : instance.args()) {
                graph.get(node).addAll(dependencies(parseOrEmpty(argument), answer));
            }
        }
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (String node : graph.keySet()) {
            List<String> cycle = findCombinationalCycle(
                    node, graph, sequentialNodes, state, path);
            if (!cycle.isEmpty()) return cycle;
        }
        return List.of();
    }

    private List<String> findCombinationalCycle(
            String node, Map<String, Set<String>> graph, Set<String> sequentialNodes,
            Map<String, Integer> state, List<String> path) {
        if (sequentialNodes.contains(node) || !graph.containsKey(node)) return List.of();
        int mark = state.getOrDefault(node, 0);
        if (mark == 2) return List.of();
        if (mark == 1) {
            int start = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(Math.max(0, start), path.size()));
            cycle.add(node);
            return cycle.stream().map(this::friendlyNodeName).toList();
        }
        state.put(node, 1);
        path.add(node);
        for (String dependency : graph.getOrDefault(node, Set.of())) {
            List<String> cycle = findCombinationalCycle(
                    dependency, graph, sequentialNodes, state, path);
            if (!cycle.isEmpty()) return cycle;
        }
        path.removeLast();
        state.put(node, 2);
        return List.of();
    }

    private String friendlyNodeName(String node) {
        return node.startsWith("signal:")
                ? node.substring("signal:".length())
                : node.substring("instance:".length());
    }

    private Set<String> dependencies(Expression expression, Answer answer) {
        Set<String> names = new LinkedHashSet<>();
        collectDependencies(expression, names);
        Set<String> result = new LinkedHashSet<>();
        Set<String> knownSignals = answer.signals().stream()
                .map(SignalSpec::name).collect(java.util.stream.Collectors.toSet());
        Set<String> knownInstances = answer.instances().stream()
                .map(InstanceSpec::name).collect(java.util.stream.Collectors.toSet());
        for (String name : names) {
            if (knownSignals.contains(name)) result.add("signal:" + name);
            else if (knownInstances.contains(name)) result.add("instance:" + name);
        }
        return result;
    }

    private static void collectDependencies(Expression expression, Set<String> names) {
        switch (expression) {
            case Expression.Variable variable -> names.add(variable.name());
            case Expression.ComponentOutput output -> names.add(output.instanceName());
            case Expression.Unary unary -> collectDependencies(unary.operand(), names);
            case Expression.Binary binary -> {
                collectDependencies(binary.left(), names);
                collectDependencies(binary.right(), names);
            }
            case Expression.ComponentCall call ->
                    call.arguments().forEach(argument -> collectDependencies(argument, names));
            case Expression.PartCall call ->
                    call.arguments().forEach(argument -> collectDependencies(argument, names));
            case Expression.Constant ignored -> { }
        }
    }

    private static Expression parseOrEmpty(String text) {
        try {
            return ExpressionParser.parse(text);
        } catch (RuntimeException exception) {
            return new Expression.Constant(false);
        }
    }

    private static boolean containsComponentOutput(Expression expression, String name) {
        if (expression instanceof Expression.ComponentOutput output) {
            return output.instanceName().equals(name);
        }
        if (expression instanceof Expression.Unary unary) {
            return containsComponentOutput(unary.operand(), name);
        }
        if (expression instanceof Expression.Binary binary) {
            return containsComponentOutput(binary.left(), name)
                    || containsComponentOutput(binary.right(), name);
        }
        if (expression instanceof Expression.ComponentCall call) {
            return call.arguments().stream().anyMatch(argument ->
                    containsComponentOutput(argument, name));
        }
        if (expression instanceof Expression.PartCall call) {
            return call.arguments().stream().anyMatch(argument ->
                    containsComponentOutput(argument, name));
        }
        return false;
    }

    private Answer parse(String raw) {
        JsonObject root = JsonParser.parseString(stripCodeFence(
                Objects.requireNonNull(raw, "model response"))).getAsJsonObject();
        Set<String> required = Set.of(
                "kind", "summary", "clock", "inputs", "outputs",
                "signals", "instances", "sequence");
        Set<String> actual = root.keySet();
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        Set<String> extra = new LinkedHashSet<>(actual);
        extra.removeAll(required);
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IllegalArgumentException(
                    "sequential response fields invalid; required fields are "
                            + new java.util.TreeSet<>(required) + "; fields sent are "
                            + new java.util.TreeSet<>(actual) + "; missing="
                            + new java.util.TreeSet<>(missing) + ", unexpected="
                            + new java.util.TreeSet<>(extra)
                            + ". Do not return the hybrid expressions/template schema; "
                            + "return only a sequential answer with clock and sequence.");
        }
        if (!"sequential".equals(requireString(root.get("kind"), "kind"))) {
            throw new IllegalArgumentException("kind must be 'sequential' for this request.");
        }
        String summary = requireString(root.get("summary"), "summary");
        String clock = requireString(root.get("clock"), "clock");
        requireName(clock, "clock");

        JsonArray inputArray = requireArray(root, "inputs");
        if (inputArray.isEmpty() || inputArray.size() > MAX_INPUTS) {
            throw new IllegalArgumentException(
                    "inputs must contain 1.." + MAX_INPUTS + " non-clock input names.");
        }
        List<String> inputs = new ArrayList<>();
        Set<String> namespace = new LinkedHashSet<>();
        namespace.add(clock);
        for (JsonElement item : inputArray) {
            String name = requireString(item, "input name");
            requireName(name, "input name");
            if (!namespace.add(name)) {
                throw new IllegalArgumentException("duplicate or clock-conflicting input name: " + name);
            }
            inputs.add(name);
        }

        List<SignalSpec> signals = parseNamedExpressions(
                requireArray(root, "signals"), "signal", AiCircuitLimits.MAX_SIGNALS,
                namespace, false);
        List<InstanceSpec> instances = parseInstances(
                requireArray(root, "instances"), namespace);
        List<OutputSpec> outputs = parseOutputs(
                requireArray(root, "outputs"), namespace);
        List<SequenceStep> sequence = parseSequence(
                requireArray(root, "sequence"), inputs, outputs);

        int expressionCharacters = 0;
        for (SignalSpec signal : signals) expressionCharacters += signal.expression().length();
        for (InstanceSpec instance : instances) {
            for (String argument : instance.args()) expressionCharacters += argument.length();
        }
        for (OutputSpec output : outputs) expressionCharacters += output.expression().length();
        if (expressionCharacters > AiCircuitLimits.MAX_EXPRESSION_TEXT) {
            throw new IllegalArgumentException("total sequential expression text exceeds "
                    + AiCircuitLimits.MAX_EXPRESSION_TEXT + " characters.");
        }
        return new Answer(summary, clock, List.copyOf(inputs), signals, instances,
                outputs, sequence);
    }

    private List<SignalSpec> parseNamedExpressions(
            JsonArray array, String label, int maximum,
            Set<String> namespace, boolean allowEmpty) {
        if (array.size() > maximum) {
            throw new IllegalArgumentException(label + " count exceeds " + maximum + ".");
        }
        List<SignalSpec> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = requireObject(element, label);
            requireExactKeys(item, Set.of("name", "expression"), label);
            String name = requireString(item.get("name"), label + " name");
            String expression = requireString(item.get("expression"), label + " expression");
            requireName(name, label + " name");
            if (!namespace.add(name)) {
                throw new IllegalArgumentException(label + " name conflicts with another name: " + name);
            }
            requireExpression(expression, label + " '" + name + "'");
            result.add(new SignalSpec(name, expression));
        }
        return List.copyOf(result);
    }

    private List<OutputSpec> parseOutputs(
            JsonArray array, Set<String> namespace) {
        if (array.isEmpty() || array.size() > MAX_OUTPUTS) {
            throw new IllegalArgumentException(
                    "outputs must contain 1.." + MAX_OUTPUTS + " named outputs.");
        }
        List<OutputSpec> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = requireObject(element, "output");
            requireExactKeys(item, Set.of("name", "expression"), "output");
            String name = requireString(item.get("name"), "output name");
            String expression = requireString(item.get("expression"), "output expression");
            requireName(name, "output name");
            if (!namespace.add(name)) {
                throw new IllegalArgumentException("output name conflicts with another name: " + name);
            }
            requireExpression(expression, "output '" + name + "'");
            result.add(new OutputSpec(name, expression));
        }
        return List.copyOf(result);
    }

    private List<InstanceSpec> parseInstances(
            JsonArray array, Set<String> namespace) {
        if (array.isEmpty() || array.size() > MAX_INSTANCES) {
            throw new IllegalArgumentException(
                    "instances must contain 1.." + MAX_INSTANCES + " boxed instances.");
        }
        List<InstanceSpec> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = requireObject(element, "instance");
            requireExactKeys(item, Set.of("name", "component", "args"), "instance");
            String name = requireString(item.get("name"), "instance name");
            String component = requireString(item.get("component"), "instance component");
            requireName(name, "instance name");
            requireName(component, "instance component");
            if (!namespace.add(name)) {
                throw new IllegalArgumentException("instance name conflicts with another name: " + name);
            }
            JsonArray arguments = requireArray(item, "args");
            List<String> args = new ArrayList<>();
            for (JsonElement argument : arguments) {
                String text = requireString(argument, "argument for " + name);
                requireExpression(text, "argument for instance '" + name + "'");
                args.add(text);
            }
            result.add(new InstanceSpec(name, component, args));
        }
        return List.copyOf(result);
    }

    private List<SequenceStep> parseSequence(
            JsonArray array, List<String> inputs, List<OutputSpec> outputs) {
        if (array.isEmpty() || array.size() > MAX_STEPS) {
            throw new IllegalArgumentException(
                    "sequence must contain 1.." + MAX_STEPS + " steps.");
        }
        Set<String> inputNames = Set.copyOf(inputs);
        Set<String> outputNames = outputs.stream()
                .map(OutputSpec::name).collect(java.util.stream.Collectors.toSet());
        List<SequenceStep> result = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonObject item = requireObject(array.get(index), "sequence step " + (index + 1));
            requireExactKeys(item, Set.of("set", "cycles", "expect"),
                    "sequence step " + (index + 1));
            Map<String, Value> set = parseAssignments(
                    requireObject(item.get("set"), "sequence[" + index + "].set"),
                    inputNames, "sequence step " + (index + 1) + " set");
            JsonElement cyclesElement = item.get("cycles");
            if (!cyclesElement.isJsonPrimitive()
                    || !cyclesElement.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("sequence step " + (index + 1)
                        + " cycles must be an integer from 0 to " + MAX_CYCLES + ".");
            }
            double cyclesNumber = cyclesElement.getAsDouble();
            int cycles = (int) cyclesNumber;
            if (cyclesNumber != cycles || cycles < 0 || cycles > MAX_CYCLES) {
                throw new IllegalArgumentException("sequence step " + (index + 1)
                        + " cycles must be an integer from 0 to " + MAX_CYCLES + ".");
            }
            Map<String, Value> expect = parseAssignments(
                    requireObject(item.get("expect"), "sequence[" + index + "].expect"),
                    outputNames, "sequence step " + (index + 1) + " expect");
            if (expect.isEmpty()) {
                throw new IllegalArgumentException("sequence step " + (index + 1)
                        + " must expect at least one output.");
            }
            result.add(new SequenceStep(set, cycles, expect));
        }
        return List.copyOf(result);
    }

    private Map<String, Value> parseAssignments(
            JsonObject object, Set<String> knownNames, String label) {
        Map<String, Value> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!knownNames.contains(entry.getKey())) {
                throw new IllegalArgumentException(label + " names unknown value '"
                        + entry.getKey() + "'; valid names: " + new java.util.TreeSet<>(knownNames));
            }
            JsonElement value = entry.getValue();
            if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(label + " value for " + entry.getKey()
                        + " must be numeric 0 or 1.");
            }
            double number = value.getAsDouble();
            if (number == 0) result.put(entry.getKey(), Value.ZERO);
            else if (number == 1) result.put(entry.getKey(), Value.ONE);
            else throw new IllegalArgumentException(label + " value for "
                    + entry.getKey() + " must be 0 or 1; X is not allowed.");
        }
        return immutableMap(result);
    }

    private static String expressionSource(Answer answer) {
        List<String> lines = new ArrayList<>();
        for (SignalSpec signal : answer.signals()) {
            lines.add(signal.name() + " := " + signal.expression());
        }
        for (InstanceSpec instance : answer.instances()) {
            lines.add(instance.name() + " := " + instance.component()
                    + "(" + String.join(", ", instance.args()) + ")");
        }
        for (OutputSpec output : answer.outputs()) {
            lines.add(output.name() + " = " + output.expression());
        }
        return String.join("\n", lines);
    }

    private String formatMismatch(
            Answer answer, SequenceStep step, int index,
            SequenceRunner.Mismatch mismatch) {
        return "Step " + (index + 1) + " (" + formatInputs(answer.inputs(), step.set())
                + ", " + step.cycles() + (step.cycles() == 1 ? " cycle" : " cycles")
                + "): " + mismatch.outputName() + " claimed "
                + bit(mismatch.expected()) + ", circuit gives " + bit(mismatch.observed());
    }

    public static String formatInputs(List<String> order, Map<String, Value> assignments) {
        Map<String, List<Map.Entry<Integer, String>>> groups = new LinkedHashMap<>();
        for (String name : order) {
            if (!assignments.containsKey(name)) continue;
            Matcher matcher = Pattern.compile("^(.*?)(\\d+)$").matcher(name);
            if (matcher.matches()) {
                int bit;
                try { bit = Integer.parseInt(matcher.group(2)); }
                catch (NumberFormatException ignored) { continue; }
                groups.computeIfAbsent(matcher.group(1), unused -> new ArrayList<>())
                        .add(Map.entry(bit, bit(assignments.get(name))));
            }
        }
        Set<String> groupedNames = new HashSet<>();
        List<String> values = new ArrayList<>();
        Set<String> usedPrefixes = new HashSet<>();
        for (String name : order) {
            if (!assignments.containsKey(name)) continue;
            Matcher matcher = Pattern.compile("^(.*?)(\\d+)$").matcher(name);
            if (matcher.matches()) {
                String prefix = matcher.group(1);
                List<Map.Entry<Integer, String>> bits = groups.get(prefix);
                if (bits != null && bits.size() > 1 && usedPrefixes.add(prefix)) {
                    bits.sort(Map.Entry.comparingByKey(Comparator.reverseOrder()));
                    StringBuilder joined = new StringBuilder();
                    bits.forEach(entry -> joined.append(entry.getValue()));
                    values.add(prefix + "=" + joined);
                    for (String candidate : order) {
                        if (candidate.matches(Pattern.quote(prefix) + "\\d+")) {
                            groupedNames.add(candidate);
                        }
                    }
                }
            }
            if (!groupedNames.contains(name)) {
                values.add(name + "=" + bit(assignments.get(name)));
            }
        }
        return String.join(", ", values);
    }

    private static String bit(Value value) {
        return switch (value) {
            case ZERO -> "0";
            case ONE -> "1";
            case X -> "X";
        };
    }

    private static String serialize(Answer answer) {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "sequential");
        root.addProperty("summary", answer.summary());
        root.addProperty("clock", answer.clock());
        root.add("inputs", strings(answer.inputs().toArray(String[]::new)));
        root.add("signals", serializeNamed(answer.signals()));
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
        root.add("outputs", serializeNamed(answer.outputs().stream()
                .map(output -> new SignalSpec(output.name(), output.expression())).toList()));
        JsonArray sequence = new JsonArray();
        for (SequenceStep step : answer.sequence()) {
            JsonObject item = new JsonObject();
            item.add("set", serializeValues(step.set()));
            item.addProperty("cycles", step.cycles());
            item.add("expect", serializeValues(step.expect()));
            sequence.add(item);
        }
        root.add("sequence", sequence);
        return root.toString();
    }

    private static JsonArray serializeNamed(List<SignalSpec> definitions) {
        JsonArray array = new JsonArray();
        for (SignalSpec definition : definitions) {
            JsonObject item = new JsonObject();
            item.addProperty("name", definition.name());
            item.addProperty("expression", definition.expression());
            array.add(item);
        }
        return array;
    }

    private static JsonObject serializeValues(Map<String, Value> values) {
        JsonObject object = new JsonObject();
        values.forEach((name, value) ->
                object.addProperty(name, value == Value.ONE ? 1 : 0));
        return object;
    }

    private Outcome toOutcome(
            int attempts, AiCircuitService.Status status, Attempt attempt,
            List<String> log, boolean fromCache, String model, List<String> models) {
        return new Outcome(attempts, status,
                status == AiCircuitService.Status.VERIFIED
                        ? "VERIFIED: matches the sequence in the answer"
                        : attempt.answer() == null ? "Sequential answer could not be built."
                                : attempt.answer().summary(),
                attempt.schematic(), attempt.data(), attempt.feedback(),
                log, fromCache, provider.redact(model),
                models.stream().map(provider::redact).toList(),
                attempt.source(), attempt.componentUseCounts());
    }

    private Outcome unavailable(String reason, List<String> log) {
        return unavailable(reason, log, List.of(), 0);
    }

    private Outcome unavailable(String reason, List<String> log,
                                List<String> models, int attempts) {
        return new Outcome(attempts, AiCircuitService.Status.UNAVAILABLE,
                "AI unavailable: " + provider.redact(reason),
                null, null, List.of(provider.redact(reason)), log,
                false, "", models.stream().map(provider::redact).toList(), "", Map.of());
    }

    private static String sequentialCatalog(
            Collection<ComponentDefinition> definitions, String request) {
        List<String> rows = new ArrayList<>();
        Map<String, ComponentDefinition> byName = new LinkedHashMap<>();
        definitions.forEach(definition -> byName.put(definition.name(), definition));
        for (String name : List.of("DFF", "DLATCH", "TFF", "JKFF")) {
            ComponentDefinition definition = byName.get(name);
            if (definition == null) {
                rows.add(name + " — not installed");
            } else if (Set.of("DFF", "DLATCH").contains(name)) {
                rows.add(name + " | IN: " + portNames(definition.inputPorts())
                        + " | OUT: " + portNames(definition.outputPorts())
                        + " | permitted sequential component");
            } else {
                rows.add(name + " | IN: " + portNames(definition.inputPorts())
                        + " | OUT: " + portNames(definition.outputPorts())
                        + " | rejected: cannot reset its state from all-X startup; use a DFF");
            }
        }
        List<ComponentDefinition> combinational = definitions.stream()
                .filter(AiComponentCatalog::isCombinational).toList();
        rows.add(AiComponentCatalog.promptBlock(combinational, request));
        return "Sequential component catalog:\n" + String.join("\n", rows);
    }

    private static String portNames(List<ComponentDefinition.Port> ports) {
        return ports.stream().map(ComponentDefinition.Port::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static void requireExpression(String text, String label) {
        if (text.isBlank()) throw new IllegalArgumentException(label + " is empty.");
        if (text.length() > AiCircuitLimits.MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds "
                    + AiCircuitLimits.MAX_EXPRESSION_LENGTH
                    + " characters; split it into named signals.");
        }
    }

    private static void requireName(String name, String label) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(label
                    + " must match [A-Za-z][A-Za-z0-9_]*: " + name);
        }
    }

    private static String requireString(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label + " must be a string.");
        }
        return value.getAsString();
    }

    private static JsonArray requireArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an array.");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement value, String label) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(label + " must be an object.");
        }
        return value.getAsJsonObject();
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

    private static String stripCodeFence(String text) {
        String trimmed = text.trim();
        String fence = String.valueOf((char) 96).repeat(3);
        if (!trimmed.startsWith(fence)) return trimmed;
        int newline = trimmed.indexOf('\n');
        int closing = trimmed.lastIndexOf(fence);
        return newline < 0 || closing <= newline ? trimmed
                : trimmed.substring(newline + 1, closing).trim();
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static JsonObject objectSchema() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "OBJECT");
        object.add("properties", new JsonObject());
        object.addProperty("additionalProperties", false);
        return object;
    }

    private static JsonObject stringSchema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "STRING");
        return value;
    }

    private static JsonObject numberSchema() {
        JsonObject value = new JsonObject();
        value.addProperty("type", "INTEGER");
        return value;
    }

    private static JsonObject integerSchema(int minimum, int maximum) {
        JsonObject value = numberSchema();
        value.addProperty("minimum", minimum);
        value.addProperty("maximum", maximum);
        return value;
    }

    private static JsonObject arraySchema(JsonObject item) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "ARRAY");
        value.add("items", item);
        return value;
    }

    private static JsonObject namedExpressionSchema() {
        JsonObject item = objectSchema();
        item.getAsJsonObject("properties").add("name", stringSchema());
        item.getAsJsonObject("properties").add("expression", stringSchema());
        item.add("required", strings("name", "expression"));
        return item;
    }

    private static JsonObject instanceSchema() {
        JsonObject item = objectSchema();
        JsonObject properties = item.getAsJsonObject("properties");
        properties.add("name", stringSchema());
        properties.add("component", stringSchema());
        properties.add("args", arraySchema(stringSchema()));
        item.add("required", strings("name", "component", "args"));
        return item;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) result.add(value);
        return result;
    }

    private static JsonArray numbers(int... values) {
        JsonArray result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public record StepView(
            int step,
            Map<String, Value> set,
            int cycles,
            Map<String, Value> expected,
            Map<String, Value> observed,
            Set<String> mismatchedOutputs) {
        public StepView {
            set = immutableMap(set);
            expected = immutableMap(expected);
            observed = immutableMap(observed);
            mismatchedOutputs = Set.copyOf(mismatchedOutputs);
        }
    }

    public record SequenceData(
            String clockName,
            List<String> inputNames,
            List<String> outputNames,
            List<StepView> steps) {
        public SequenceData {
            inputNames = List.copyOf(inputNames);
            outputNames = List.copyOf(outputNames);
            steps = List.copyOf(steps);
        }
    }

    public record Outcome(
            int attemptCount,
            AiCircuitService.Status status,
            String summary,
            Schematic schematic,
            SequenceData sequenceData,
            List<String> mismatchingRows,
            List<String> attemptLog,
            boolean fromCache,
            String answeredBy,
            List<String> modelAttempts,
            String expressionSource,
            Map<String, Integer> componentUseCounts) {
        public Outcome {
            mismatchingRows = List.copyOf(mismatchingRows);
            attemptLog = List.copyOf(attemptLog);
            modelAttempts = List.copyOf(modelAttempts);
            expressionSource = expressionSource == null ? "" : expressionSource;
            componentUseCounts = Map.copyOf(componentUseCounts);
        }
    }

    private record ModelReply(String raw, String model) { }
    private record SignalSpec(String name, String expression) { }
    private record OutputSpec(String name, String expression) { }
    private record InstanceSpec(String name, String component, List<String> args) {
        private InstanceSpec { args = List.copyOf(args); }
    }
    private record SequenceStep(
            Map<String, Value> set, int cycles, Map<String, Value> expect) {
        private SequenceStep {
            set = immutableMap(set);
            expect = immutableMap(expect);
        }
    }
    private record Answer(
            String summary, String clock, List<String> inputs,
            List<SignalSpec> signals, List<InstanceSpec> instances,
            List<OutputSpec> outputs, List<SequenceStep> sequence) {
    }
    private record Attempt(
            Answer answer, Schematic schematic, SequenceData data, String source,
            Map<String, Integer> componentUseCounts,
            List<String> errors, List<String> mismatches) {
        private Attempt {
            componentUseCounts = Map.copyOf(componentUseCounts);
            errors = List.copyOf(errors);
            mismatches = List.copyOf(mismatches);
        }
        private static Attempt failed(List<String> errors) {
            return failed(null, errors, "");
        }
        private static Attempt failed(Answer answer, List<String> errors) {
            return failed(answer, errors, "");
        }
        private static Attempt failed(Answer answer, List<String> errors, String source) {
            return new Attempt(answer, null, null, source, Map.of(), errors, List.of());
        }
        private boolean passed() {
            return errors.isEmpty() && mismatches.isEmpty();
        }
        private int score() {
            return (answer == null ? 1_000 : 0) + errors.size() + mismatches.size();
        }
        private List<String> feedback() {
            List<String> all = new ArrayList<>(errors);
            all.addAll(mismatches);
            return all.stream().limit(8).toList();
        }
    }
}
