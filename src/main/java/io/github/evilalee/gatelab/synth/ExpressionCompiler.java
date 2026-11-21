package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Wire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Parses named expressions and synthesizes a shared two-input-gate netlist. */
public final class ExpressionCompiler {
    private record Statement(String text, int sourceOffset) {
    }

    private enum AssignmentKind {
        OUTPUT,
        INTERNAL
    }

    private record Assignment(String name, AssignmentKind kind, Expression expression) {
    }

    private record GateKey(GateOperator type, List<String> inputs) {
        private GateKey {
            inputs = List.copyOf(inputs);
        }
    }

    /** Named pins for a compiled gate-level sequential circuit. */
    public record SequentialCircuit(
            Circuit circuit,
            Map<String, InputPin> inputs,
            Map<String, OutputProbe> outputs,
            Map<InputPin, Value> fixedInputs) {
        public SequentialCircuit {
            Objects.requireNonNull(circuit, "circuit");
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(inputs, "inputs")));
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(outputs, "outputs")));
            fixedInputs = Collections.unmodifiableMap(new IdentityHashMap<>(
                    Objects.requireNonNull(fixedInputs, "fixedInputs")));
        }
    }

    /** Parsed ASTs are retained so the engine truth table can be checked independently. */
    public record Compilation(
            Netlist netlist,
            List<NamedExpression> expressions,
            List<String> reusedOutputNames,
            Optional<FeedbackCircuitExtractor.Extraction> feedbackExtraction,
            boolean hasSequentialParts,
            Optional<String> clockName,
            Optional<SequentialCircuit> sequentialCircuit) {
        public Compilation(Netlist netlist, List<NamedExpression> expressions) {
            this(netlist, expressions, List.of(), Optional.empty(),
                    false, Optional.empty(), Optional.empty());
        }

        public Compilation(
                Netlist netlist, List<NamedExpression> expressions,
                List<String> reusedOutputNames) {
            this(netlist, expressions, reusedOutputNames, Optional.empty(),
                    false, Optional.empty(), Optional.empty());
        }

        public Compilation(
                Netlist netlist, List<NamedExpression> expressions,
                List<String> reusedOutputNames,
                Optional<FeedbackCircuitExtractor.Extraction> feedbackExtraction) {
            this(netlist, expressions, reusedOutputNames, feedbackExtraction,
                    false, Optional.empty(), Optional.empty());
        }

        public Compilation {
            Objects.requireNonNull(netlist, "netlist");
            expressions = List.copyOf(Objects.requireNonNull(expressions, "expressions"));
            reusedOutputNames = List.copyOf(
                    Objects.requireNonNull(reusedOutputNames, "reusedOutputNames"));
            feedbackExtraction = Objects.requireNonNull(feedbackExtraction, "feedbackExtraction");
            clockName = Objects.requireNonNull(clockName, "clockName");
            sequentialCircuit = Objects.requireNonNull(sequentialCircuit, "sequentialCircuit");
            if (hasSequentialParts != sequentialCircuit.isPresent()) {
                throw new IllegalArgumentException(
                        "sequential circuit data must be present exactly when parts are used");
            }
            if (expressions.size() != netlist.outputs().size()) {
                throw new IllegalArgumentException(
                        "expression count must match the netlist output count");
            }
            for (int index = 0; index < expressions.size(); index++) {
                if (!expressions.get(index).name().equals(netlist.outputs().get(index).name())) {
                    throw new IllegalArgumentException(
                            "expression output order must match netlist output order");
                }
            }
        }

        public boolean hasFeedback() {
            return feedbackExtraction.isPresent();
        }
    }

    private ExpressionCompiler() {
    }

    /**
     * Accepts one expression per line or semicolon-delimited statement.
     * "name = expression" creates an output; "name := expression" defines an
     * internal signal that can be referenced by later statements without a pin.
     * An unnamed statement receives the default output name "F".
     */
    /**
     * Preserves the original expression-dialog/#38 feedback behavior.
     * Callers that require a combinational DAG must use compile(source, false).
     */
    public static Compilation compile(String source) {
        return compile(source, true);
    }

    /**
     * Compiles named Boolean expressions. Raw expression-level feedback is
     * opt-in; cycles contained by a DLATCH/DFF/TFF/JKFF call are always valid.
     */
    public static Compilation compile(String source, boolean allowRawFeedback) {
        return compile(source, allowRawFeedback, false);
    }

    /**
     * Compiles expressions and optionally applies semantics-preserving inverting-gate mapping.
     * The mapper is opt-in so the default netlist stays byte-for-byte stable.
     */
    public static Compilation compile(
            String source, boolean allowRawFeedback, boolean invertingGates) {
        Objects.requireNonNull(source, "source");
        List<Statement> statements = splitStatements(source);
        if (statements.isEmpty()) {
            throw new ParseException(0, "expression list is empty");
        }

        List<Assignment> assignments = new ArrayList<>();
        Map<String, Assignment> assignmentsByName = new LinkedHashMap<>();
        for (Statement statement : statements) {
            Assignment assignment = parseStatement(statement);
            assignment = new Assignment(assignment.name(), assignment.kind(),
                    expandLegacyPartCalls(assignment.expression()));
            if (assignmentsByName.putIfAbsent(assignment.name(), assignment) != null) {
                throw new IllegalArgumentException(
                        assignment.kind() == AssignmentKind.OUTPUT
                                ? "duplicate output name: " + assignment.name()
                                : "duplicate signal definition: " + assignment.name());
            }
            assignments.add(assignment);
        }

        List<Assignment> outputAssignments = assignments.stream()
                .filter(assignment -> assignment.kind() == AssignmentKind.OUTPUT)
                .toList();
        LinkedHashSet<String> reachableAssignments = reachableAssignments(
                outputAssignments, assignmentsByName);
        LinkedHashSet<String> clockNames = new LinkedHashSet<>();
        for (String name : reachableAssignments) {
            collectClockNames(assignmentsByName.get(name).expression(), clockNames);
        }
        if (clockNames.size() > 1) {
            throw new IllegalArgumentException(
                    "sequential expressions must use one clock; found "
                            + String.join(", ", clockNames));
        }
        for (String clock : clockNames) {
            if (assignmentsByName.containsKey(clock)) {
                throw new IllegalArgumentException(
                        "clock identifier '" + clock
                                + "' cannot be defined by an expression line");
            }
        }

        List<String> reusedOutputNames = findReusedOutputNames(assignments);
        List<String> rawCycle = findRawFeedbackCycle(outputAssignments, assignmentsByName);
        if (!allowRawFeedback && !rawCycle.isEmpty()) {
            throw new IllegalArgumentException(
                    "raw feedback is disabled; cycle bypasses sequential parts: "
                            + String.join(" -> ", rawCycle));
        }

        boolean hasSequentialParts = reachableAssignments.stream()
                .map(assignmentsByName::get)
                .anyMatch(assignment -> containsPartCall(assignment.expression()));
        Map<String, Expression> expandedAssignments = new HashMap<>();
        List<NamedExpression> expressions = new ArrayList<>();
        for (Assignment assignment : outputAssignments) {
            Expression expanded = expandAssignment(
                    assignment.name(), assignmentsByName, expandedAssignments, new HashSet<>());
            expressions.add(new NamedExpression(assignment.name(), expanded));
        }

        if (hasSequentialParts) {
            SequentialBuilder builder = new SequentialBuilder(assignmentsByName);
            List<Netlist.Output> outputs = new ArrayList<>();
            for (Assignment assignment : outputAssignments) {
                String signal = builder.buildOutput(assignment.name());
                outputs.add(new Netlist.Output(assignment.name(), signal));
            }
            SequentialBuild build = builder.finish(outputs);
            if (build.netlist().inputs().size() > 12) {
                throw new IllegalArgumentException(
                        "netlist has " + build.netlist().inputs().size()
                                + " inputs; maximum is 12");
            }
            if (build.netlist().gates().size() > 500) {
                throw new IllegalArgumentException("sequential circuit has too many gates");
            }
            FeedbackCircuitExtractor.Extraction rawExtraction =
                    FeedbackCircuitExtractor.extract(build.netlist()).orElseThrow(() ->
                            new IllegalArgumentException(
                                    "sequential part expansion did not produce a feedback circuit"));
            Map<String, String> clocks = new LinkedHashMap<>();
            clockNames.forEach(name -> clocks.put(name, "expr-clock-" + name));
            FeedbackCircuitExtractor.Extraction extraction =
                    new FeedbackCircuitExtractor.Extraction(
                            rawExtraction.dagNetlist(),
                            rawExtraction.feedbackEdges(),
                            rawExtraction.inputComponentIds(),
                            clocks,
                            rawExtraction.outputComponentIds(),
                            rawExtraction.gateComponentIds());
            return new Compilation(
                    build.netlist(), expressions, reusedOutputNames, Optional.of(extraction),
                    true, clockNames.stream().findFirst(), Optional.of(build.sequentialCircuit()));
        }

        boolean hasFeedback = hasReachableFeedback(assignments, assignmentsByName);
        if (hasFeedback) {
            FeedbackBuilder builder = new FeedbackBuilder(assignmentsByName);
            List<Netlist.Output> outputs = new ArrayList<>();
            for (Assignment assignment : outputAssignments) {
                outputs.add(new Netlist.Output(
                        assignment.name(), builder.signalForAssignment(assignment.name())));
            }
            if (builder.inputs().size() > 12) {
                throw new IllegalArgumentException(
                        "netlist has " + builder.inputs().size() + " inputs; maximum is 12");
            }
            Netlist netlist = new Netlist(
                    builder.inputs(), outputs, builder.gates(), true);
            FeedbackCircuitExtractor.Extraction extraction =
                    FeedbackCircuitExtractor.extract(netlist).orElseThrow(() ->
                            new IllegalArgumentException(
                                    "feedback definitions must contain a gate-level cycle"));
            return new Compilation(
                    netlist, expressions, reusedOutputNames, Optional.of(extraction));
        }

        LinkedHashSet<String> variables = new LinkedHashSet<>();
        expressions.forEach(expression -> variables.addAll(expression.expression().variables()));
        if (variables.size() > 12) {
            throw new IllegalArgumentException(
                    "netlist has " + variables.size() + " inputs; maximum is 12");
        }

        Builder builder = new Builder(variables);
        List<Netlist.Output> outputs = new ArrayList<>();
        for (NamedExpression expression : expressions) {
            outputs.add(new Netlist.Output(
                    expression.name(), builder.signalFor(expression.expression())));
        }
        Netlist netlist = new Netlist(
                List.copyOf(variables), outputs, builder.gates());
        if (invertingGates) {
            netlist = InvertingGateMapper.optimize(netlist);
        }
        return new Compilation(netlist, expressions, reusedOutputNames);
    }


    /*
     * The low-level netlist compiler deliberately remains the legacy gate-expansion
     * path. The typed dialog uses ComponentExpressionCompiler for boxed instances.
     */
    private static Expression expandLegacyPartCalls(Expression expression) {
        return switch (expression) {
            case Expression.Constant constant -> constant;
            case Expression.Variable variable -> variable;
            case Expression.Unary unary -> new Expression.Unary(
                    unary.operator(), expandLegacyPartCalls(unary.operand()));
            case Expression.Binary binary -> new Expression.Binary(
                    binary.operator(), expandLegacyPartCalls(binary.left()),
                    expandLegacyPartCalls(binary.right()));
            case Expression.ComponentOutput output -> throw new IllegalArgumentException(
                    "instance output references require boxed component-call compilation: "
                            + output.instanceName() + "." + output.outputName());
            case Expression.ComponentCall call -> {
                List<Expression> arguments = call.arguments().stream()
                        .map(ExpressionCompiler::expandLegacyPartCalls).toList();
                Expression.PartKind kind;
                try {
                    kind = Expression.PartKind.valueOf(
                            call.componentName().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "component call '" + call.componentName()
                                    + "' requires the component library instance compiler");
                }
                yield new Expression.PartCall(kind, arguments);
            }
            case Expression.PartCall part -> part;
        };
    }

    private record SequentialBuild(Netlist netlist, SequentialCircuit sequentialCircuit) {
    }

    private static LinkedHashSet<String> reachableAssignments(
            List<Assignment> outputs, Map<String, Assignment> assignmentsByName) {
        LinkedHashSet<String> reachable = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        outputs.forEach(output -> pending.add(output.name()));
        for (int index = 0; index < pending.size(); index++) {
            String name = pending.get(index);
            if (!reachable.add(name)) {
                continue;
            }
            Assignment assignment = assignmentsByName.get(name);
            if (assignment == null) {
                continue;
            }
            for (String reference : assignment.expression().variables()) {
                if (assignmentsByName.containsKey(reference) && !reachable.contains(reference)) {
                    pending.add(reference);
                }
            }
        }
        return reachable;
    }

    private static boolean containsPartCall(Expression expression) {
        return switch (expression) {
            case Expression.Constant ignored -> false;
            case Expression.Variable ignored -> false;
            case Expression.Unary unary -> containsPartCall(unary.operand());
            case Expression.Binary binary ->
                    containsPartCall(binary.left()) || containsPartCall(binary.right());
            case Expression.PartCall ignored -> true;
            case Expression.ComponentCall ignored -> true;
            case Expression.ComponentOutput ignored -> false;
        };
    }

    private static boolean containsConstant(Expression expression) {
        return switch (expression) {
            case Expression.Constant ignored -> true;
            case Expression.Variable ignored -> false;
            case Expression.Unary unary -> containsConstant(unary.operand());
            case Expression.Binary binary ->
                    containsConstant(binary.left()) || containsConstant(binary.right());
            case Expression.PartCall call -> call.arguments().stream()
                    .anyMatch(ExpressionCompiler::containsConstant);
            case Expression.ComponentCall call -> call.arguments().stream()
                    .anyMatch(ExpressionCompiler::containsConstant);
            case Expression.ComponentOutput ignored -> false;
        };
    }

    private static void collectClockNames(Expression expression, Set<String> names) {
        switch (expression) {
            case Expression.Constant ignored -> {
            }
            case Expression.Variable ignored -> {
            }
            case Expression.Unary unary -> collectClockNames(unary.operand(), names);
            case Expression.Binary binary -> {
                collectClockNames(binary.left(), names);
                collectClockNames(binary.right(), names);
            }
            case Expression.PartCall call -> {
                int argumentLimit = call.arguments().size();
                if (call.kind().clocked()) {
                    Expression clock = call.arguments().getLast();
                    if (!(clock instanceof Expression.Variable variable)) {
                        throw new IllegalArgumentException(
                                "CLK argument to " + call.kind()
                                        + " must be a plain identifier");
                    }
                    names.add(variable.name());
                    argumentLimit--;
                }
                for (int index = 0; index < argumentLimit; index++) {
                    collectClockNames(call.arguments().get(index), names);
                }
            }
            case Expression.ComponentCall call -> call.arguments().forEach(argument ->
                    collectClockNames(argument, names));
            case Expression.ComponentOutput ignored -> {
            }
        }
    }

    private static List<String> findRawFeedbackCycle(
            List<Assignment> outputs, Map<String, Assignment> assignmentsByName) {
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        for (Assignment output : outputs) {
            List<String> cycle = visitRawDefinition(
                    output.name(), assignmentsByName, state, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> visitRawDefinition(
            String name,
            Map<String, Assignment> assignmentsByName,
            Map<String, Integer> state,
            List<String> path) {
        int current = state.getOrDefault(name, 0);
        if (current == 2) {
            return List.of();
        }
        if (current == 1) {
            int start = path.indexOf(name);
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(name);
            return List.copyOf(cycle);
        }

        Assignment assignment = assignmentsByName.get(name);
        if (assignment == null) {
            return List.of();
        }
        state.put(name, 1);
        path.add(name);
        List<String> rawReferences = new ArrayList<>();
        collectRawReferences(assignment.expression(), false, rawReferences);
        for (String reference : rawReferences) {
            if (!assignmentsByName.containsKey(reference)) {
                continue;
            }
            List<String> cycle = visitRawDefinition(
                    reference, assignmentsByName, state, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.removeLast();
        state.put(name, 2);
        return List.of();
    }

    /**
     * References nested anywhere inside a part argument cross a sequential
     * boundary and therefore do not form a raw-feedback dependency edge.
     */
    private static void collectRawReferences(
            Expression expression, boolean insidePart, List<String> references) {
        switch (expression) {
            case Expression.Constant ignored -> {
            }
            case Expression.Variable variable -> {
                if (!insidePart) {
                    references.add(variable.name());
                }
            }
            case Expression.Unary unary ->
                    collectRawReferences(unary.operand(), insidePart, references);
            case Expression.Binary binary -> {
                collectRawReferences(binary.left(), insidePart, references);
                collectRawReferences(binary.right(), insidePart, references);
            }
            case Expression.PartCall call -> call.arguments().forEach(argument ->
                    collectRawReferences(argument, true, references));
            case Expression.ComponentCall call -> call.arguments().forEach(argument ->
                    collectRawReferences(argument, true, references));
            case Expression.ComponentOutput output -> {
                if (!insidePart) {
                    references.add(output.instanceName());
                }
            }
        }
    }

    private static final class SequentialBuilder {
        private final Map<String, Assignment> assignments;
        private final io.github.evilalee.gatelab.model.Circuit circuit =
                new io.github.evilalee.gatelab.model.Circuit();
        private final Map<String, io.github.evilalee.gatelab.model.InputPin> inputs =
                new LinkedHashMap<>();
        private final Map<String, io.github.evilalee.gatelab.model.OutputProbe> outputs =
                new LinkedHashMap<>();
        private final Map<io.github.evilalee.gatelab.model.InputPin, Value> fixedInputs =
                new IdentityHashMap<>();
        private final Map<String, Gate> assignmentGates = new LinkedHashMap<>();
        private final Map<Gate, String> assignmentSignals = new IdentityHashMap<>();
        private final Map<String, Integer> assignmentState = new HashMap<>();
        private int nextExpressionGate = 1;
        private int nextPart = 1;
        private int nextConstant = 1;

        private SequentialBuilder(Map<String, Assignment> assignments) {
            this.assignments = assignments;
        }

        private String buildOutput(String name) {
            Pin source = signalForAssignment(name);
            io.github.evilalee.gatelab.model.OutputProbe probe =
                    new io.github.evilalee.gatelab.model.OutputProbe("expr-output-" + name);
            circuit.addComponent(probe);
            circuit.connect(source, probe.input());
            outputs.put(name, probe);
            return name;
        }

        private SequentialBuild finish(List<Netlist.Output> netlistOutputs) {
            Netlist netlist = toNetlist(netlistOutputs);
            SequentialCircuit sequentialCircuit =
                    new SequentialCircuit(circuit, inputs, outputs, fixedInputs);
            return new SequentialBuild(netlist, sequentialCircuit);
        }

        private Pin signalForAssignment(String name) {
            Gate alias = assignmentGates.computeIfAbsent(name, this::createAssignmentGate);
            int state = assignmentState.getOrDefault(name, 0);
            if (state == 1 || state == 2) {
                return alias.output();
            }

            Assignment assignment = assignments.get(name);
            if (assignment == null) {
                throw new IllegalArgumentException("unknown signal definition: " + name);
            }
            assignmentState.put(name, 1);
            Pin expression = signalForExpression(assignment.expression());
            circuit.connect(expression, alias.input(0));
            assignmentState.put(name, 2);
            return alias.output();
        }

        private Gate createAssignmentGate(String name) {
            Gate gate = new Gate("expr-assignment-" + name, GateType.BUFFER, 1);
            circuit.addComponent(gate);
            assignmentSignals.put(gate, name);
            return gate;
        }

        private Pin signalForExpression(Expression expression) {
            Expression normalized = containsConstant(expression)
                    ? Builder.simplify(expression) : expression;
            return switch (normalized) {
                case Expression.Constant constant -> constantPin(constant.value());
                case Expression.Variable variable -> assignments.containsKey(variable.name())
                        ? signalForAssignment(variable.name())
                        : inputPin(variable.name()).output();
                case Expression.Unary unary -> {
                    Gate gate = expressionGate(GateType.NOT, 1);
                    circuit.connect(signalForExpression(unary.operand()), gate.input(0));
                    yield gate.output();
                }
                case Expression.Binary binary -> {
                    Gate gate = expressionGate(
                            GateType.valueOf(binary.operator().name()), 2);
                    circuit.connect(signalForExpression(binary.left()), gate.input(0));
                    circuit.connect(signalForExpression(binary.right()), gate.input(1));
                    yield gate.output();
                }
                case Expression.PartCall call -> expandPart(call);
                case Expression.ComponentCall call -> throw new IllegalArgumentException(
                        "component call requires instance-aware compilation: " + call.componentName());
                case Expression.ComponentOutput output -> throw new IllegalArgumentException(
                        "component output requires instance-aware compilation: "
                                + output.instanceName() + "." + output.outputName());
            };
        }

        private Pin expandPart(Expression.PartCall call) {
            List<Expression> arguments = call.arguments();
            String prefix = "expr-part-" + nextPart++;
            return switch (call.kind()) {
                case DLATCH -> SequentialParts.dLatch(
                        circuit, prefix,
                        signalForExpression(arguments.get(0)),
                        signalForExpression(arguments.get(1))).q();
                case DFF -> SequentialParts.dff(
                        circuit, prefix,
                        signalForExpression(arguments.get(0)),
                        clockPin(arguments.getLast())).q();
                case TFF -> SequentialParts.tff(
                        circuit, prefix,
                        signalForExpression(arguments.get(0)),
                        clockPin(arguments.getLast())).q();
                case JKFF -> SequentialParts.jkff(
                        circuit, prefix,
                        signalForExpression(arguments.get(0)),
                        signalForExpression(arguments.get(1)),
                        clockPin(arguments.getLast())).q();
            };
        }

        private Pin clockPin(Expression expression) {
            if (!(expression instanceof Expression.Variable variable)) {
                throw new IllegalArgumentException("CLK argument must be a plain identifier");
            }
            if (assignments.containsKey(variable.name())) {
                throw new IllegalArgumentException(
                        "clock identifier '" + variable.name()
                                + "' cannot be defined by an expression line");
            }
            return inputPin(variable.name()).output();
        }

        private io.github.evilalee.gatelab.model.InputPin inputPin(String name) {
            io.github.evilalee.gatelab.model.InputPin existing = inputs.get(name);
            if (existing != null) {
                return existing;
            }
            io.github.evilalee.gatelab.model.InputPin input =
                    new io.github.evilalee.gatelab.model.InputPin("expr-input-" + name);
            circuit.addComponent(input);
            inputs.put(name, input);
            return input;
        }

        private Pin constantPin(boolean value) {
            io.github.evilalee.gatelab.model.InputPin constant =
                    new io.github.evilalee.gatelab.model.InputPin(
                            "expr-constant-" + nextConstant++);
            Value fixedValue = value ? Value.ONE : Value.ZERO;
            constant.setValue(fixedValue);
            circuit.addComponent(constant);
            fixedInputs.put(constant, fixedValue);
            return constant.output();
        }

        private Gate expressionGate(GateType type, int inputCount) {
            Gate gate = new Gate(
                    "expr-gate-" + nextExpressionGate++, type, inputCount);
            circuit.addComponent(gate);
            return gate;
        }

        private Netlist toNetlist(List<Netlist.Output> netlistOutputs) {
            Map<Pin, Pin> parents = new IdentityHashMap<>();
            circuit.pins().forEach(pin -> parents.put(pin, pin));
            for (Wire wire : circuit.wires()) {
                union(parents, wire.first(), wire.second());
            }

            Map<Pin, String> signalByDriver = new IdentityHashMap<>();
            inputs.forEach((name, input) -> signalByDriver.put(input.output(), name));
            fixedInputs.forEach((input, value) ->
                    signalByDriver.put(input.output(), value == Value.ONE ? "1" : "0"));

            Set<String> reserved = new HashSet<>(inputs.keySet());
            reserved.addAll(assignments.keySet());
            int generatedSignal = 1;
            Map<Gate, String> signalByGate = new IdentityHashMap<>();
            for (Component component : circuit.components()) {
                if (!(component instanceof Gate gate)) {
                    continue;
                }
                String signal = assignmentSignals.get(gate);
                if (signal == null) {
                    do {
                        signal = "g" + generatedSignal++;
                    } while (!reserved.add(signal));
                }
                signalByGate.put(gate, signal);
                signalByDriver.put(gate.output(), signal);
            }

            Map<Pin, String> signalByNet = new IdentityHashMap<>();
            for (Map.Entry<Pin, String> driver : signalByDriver.entrySet()) {
                Pin root = find(parents, driver.getKey());
                String previous = signalByNet.putIfAbsent(root, driver.getValue());
                if (previous != null && !previous.equals(driver.getValue())) {
                    throw new IllegalArgumentException(
                            "sequential expansion joined distinct drivers: "
                                    + previous + " and " + driver.getValue());
                }
            }

            List<Netlist.Gate> netlistGates = new ArrayList<>();
            for (Component component : circuit.components()) {
                if (!(component instanceof Gate gate)) {
                    continue;
                }
                List<String> gateInputs = new ArrayList<>();
                for (Pin input : gate.inputs()) {
                    String source = signalByNet.get(find(parents, input));
                    if (source == null) {
                        throw new IllegalStateException(
                                "sequential part left an unconnected gate input: " + input.id());
                    }
                    gateInputs.add(source);
                }
                netlistGates.add(new Netlist.Gate(
                        signalByGate.get(gate),
                        GateOperator.valueOf(gate.type().name()),
                        gateInputs));
            }
            return new Netlist(
                    List.copyOf(inputs.keySet()), netlistOutputs, netlistGates, true);
        }

        private static void union(Map<Pin, Pin> parents, Pin first, Pin second) {
            Pin a = find(parents, first);
            Pin b = find(parents, second);
            if (a != b) {
                parents.put(a, b);
            }
        }

        private static Pin find(Map<Pin, Pin> parents, Pin pin) {
            Pin parent = parents.get(pin);
            if (parent == null) {
                throw new IllegalArgumentException("pin is not part of the compiled circuit: " + pin);
            }
            if (parent != pin) {
                parent = find(parents, parent);
                parents.put(pin, parent);
            }
            return parent;
        }
    }

    private static boolean hasReachableFeedback(
            List<Assignment> assignments,
            Map<String, Assignment> assignmentsByName) {
        Map<String, Integer> state = new HashMap<>();
        for (Assignment assignment : assignments) {
            if (assignment.kind() == AssignmentKind.OUTPUT
                    && visitDefinition(assignment.name(), assignmentsByName, state)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitDefinition(
            String name,
            Map<String, Assignment> assignmentsByName,
            Map<String, Integer> state) {
        int current = state.getOrDefault(name, 0);
        if (current == 1) {
            return true;
        }
        if (current == 2) {
            return false;
        }

        state.put(name, 1);
        Assignment definition = assignmentsByName.get(name);
        for (String reference : definition.expression().variables()) {
            if (assignmentsByName.containsKey(reference)
                    && visitDefinition(reference, assignmentsByName, state)) {
                return true;
            }
        }
        state.put(name, 2);
        return false;
    }

    private static Expression expandAssignment(
            String name,
            Map<String, Assignment> assignmentsByName,
            Map<String, Expression> expandedAssignments,
            Set<String> expanding) {
        Expression cached = expandedAssignments.get(name);
        if (cached != null) {
            return cached;
        }
        if (!expanding.add(name)) {
            // Feedback ASTs are retained only for metadata; their simulation is
            // verified structurally rather than through a combinational evaluator.
            return new Expression.Variable(name);
        }
        Expression expanded = expandReferences(
                assignmentsByName.get(name).expression(),
                assignmentsByName, expandedAssignments, expanding);
        expanding.remove(name);
        expandedAssignments.put(name, expanded);
        return expanded;
    }

    private static Expression expandReferences(
            Expression expression,
            Map<String, Assignment> assignmentsByName,
            Map<String, Expression> expandedAssignments,
            Set<String> expanding) {
        return switch (expression) {
            case Expression.Constant constant -> constant;
            case Expression.Variable variable -> assignmentsByName.containsKey(variable.name())
                    ? expandAssignment(variable.name(), assignmentsByName,
                            expandedAssignments, expanding)
                    : variable;
            case Expression.Unary unary -> new Expression.Unary(
                    unary.operator(), expandReferences(
                            unary.operand(), assignmentsByName, expandedAssignments, expanding));
            case Expression.Binary binary -> new Expression.Binary(
                    binary.operator(),
                    expandReferences(binary.left(), assignmentsByName,
                            expandedAssignments, expanding),
                    expandReferences(binary.right(), assignmentsByName,
                            expandedAssignments, expanding));
            case Expression.PartCall call -> new Expression.PartCall(
                    call.kind(), call.arguments().stream()
                            .map(argument -> expandReferences(argument, assignmentsByName,
                                    expandedAssignments, expanding))
                            .toList());
            case Expression.ComponentCall call -> new Expression.ComponentCall(
                    call.componentName(), call.arguments().stream()
                            .map(argument -> expandReferences(argument, assignmentsByName,
                                    expandedAssignments, expanding))
                            .toList());
            case Expression.ComponentOutput output -> output;
        };
    }

    private static List<String> findReusedOutputNames(List<Assignment> assignments) {
        LinkedHashSet<String> reused = new LinkedHashSet<>();
        for (int index = 0; index < assignments.size(); index++) {
            Assignment definition = assignments.get(index);
            if (definition.kind() != AssignmentKind.OUTPUT) {
                continue;
            }
            for (int referenceIndex = 0; referenceIndex < assignments.size(); referenceIndex++) {
                if (referenceIndex != index
                        && assignments.get(referenceIndex).expression().variables()
                                .contains(definition.name())) {
                    reused.add(definition.name());
                    break;
                }
            }
        }
        return List.copyOf(reused);
    }

    private static Assignment parseStatement(Statement statement) {
        String text = statement.text();
        int leading = 0;
        while (leading < text.length() && Character.isWhitespace(text.charAt(leading))) {
            leading++;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new ParseException(statement.sourceOffset(), "empty expression statement");
        }

        int equals = trimmed.indexOf('=');
        String name = "F";
        String expressionText = trimmed;
        int expressionOffset = statement.sourceOffset() + leading;
        AssignmentKind kind = AssignmentKind.OUTPUT;
        if (equals >= 0) {
            int operatorStart = equals;
            if (equals > 0 && trimmed.charAt(equals - 1) == ':') {
                operatorStart = equals - 1;
                kind = AssignmentKind.INTERNAL;
            }
            if (trimmed.indexOf('=', equals + 1) >= 0) {
                throw new ParseException(
                        statement.sourceOffset() + leading + equals + 1,
                        "unexpected second assignment operator");
            }
            String candidate = trimmed.substring(0, operatorStart).trim();
            if (!candidate.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new ParseException(
                        statement.sourceOffset() + leading,
                        "expected a signal name before the assignment operator");
            }
            name = candidate;
            int expressionLeading = equals + 1;
            while (expressionLeading < trimmed.length()
                    && Character.isWhitespace(trimmed.charAt(expressionLeading))) {
                expressionLeading++;
            }
            expressionText = trimmed.substring(expressionLeading);
            expressionOffset += expressionLeading;
        }

        if (expressionText.isBlank()) {
            throw new ParseException(
                    statement.sourceOffset() + text.length(),
                    "expected an expression after signal name");
        }
        try {
            return new Assignment(name, kind, ExpressionParser.parse(expressionText));
        } catch (ParseException exception) {
            throw new ParseException(
                    expressionOffset + exception.position(), exception.detail());
        }
    }

    private static List<Statement> splitStatements(String source) {
        List<Statement> statements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && depth > 0) {
                depth--;
            } else if ((current == ';' || current == '\n') && depth == 0) {
                addStatement(statements, source, start, index);
                start = index + 1;
            }
        }
        addStatement(statements, source, start, source.length());
        return List.copyOf(statements);
    }

    private static void addStatement(
            List<Statement> statements, String source, int start, int end) {
        String text = source.substring(start, end);
        if (!text.isBlank()) {
            statements.add(new Statement(text, start));
        }
    }

    private static final class FeedbackBuilder {
        private final Map<String, Assignment> assignments;
        private final Set<String> reservedIds = new HashSet<>();
        private final Map<GateKey, String> sharedGates = new LinkedHashMap<>();
        private final Map<String, Integer> assignmentState = new HashMap<>();
        private final Map<String, String> assignmentSignals = new HashMap<>();
        private final LinkedHashSet<String> inputs = new LinkedHashSet<>();
        private final List<Netlist.Gate> gates = new ArrayList<>();
        private int nextGateNumber = 1;

        private FeedbackBuilder(Map<String, Assignment> assignments) {
            this.assignments = assignments;
            reservedIds.addAll(assignments.keySet());
            assignments.values().forEach(assignment ->
                    reservedIds.addAll(assignment.expression().variables()));
        }

        private List<String> inputs() {
            return List.copyOf(inputs);
        }

        private List<Netlist.Gate> gates() {
            return List.copyOf(gates);
        }

        private String signalForAssignment(String name) {
            int state = assignmentState.getOrDefault(name, 0);
            if (state == 1) {
                // The named gate output is reserved before descending into its inputs.
                return name;
            }
            if (state == 2) {
                return assignmentSignals.get(name);
            }

            Assignment assignment = assignments.get(name);
            if (assignment == null) {
                throw new IllegalArgumentException("unknown signal definition: " + name);
            }
            assignmentState.put(name, 1);
            Expression expression = Builder.simplify(assignment.expression());
            final String signal;
            if (expression instanceof Expression.Unary unary) {
                String input = signalForExpression(unary.operand());
                gates.add(new Netlist.Gate(name, GateOperator.NOT, List.of(input)));
                signal = name;
            } else if (expression instanceof Expression.Binary binary) {
                String left = signalForExpression(binary.left());
                String right = signalForExpression(binary.right());
                GateOperator operator = GateOperator.valueOf(binary.operator().name());
                gates.add(new Netlist.Gate(name, operator, List.of(left, right)));
                signal = name;
            } else if (expression instanceof Expression.Variable variable) {
                String input = signalForExpression(variable);
                if (assignment.kind() == AssignmentKind.OUTPUT && !input.equals(name)) {
                    // An Output is a probe, not another logic gate. Preserve the signal alias
                    // directly so feedback simulation has the same gate-to-gate timing.
                    signal = input;
                } else {
                    gates.add(new Netlist.Gate(name, GateOperator.BUFFER, List.of(input)));
                    signal = name;
                }
            } else {
                signal = signalForExpression(expression);
            }
            assignmentSignals.put(name, signal);
            assignmentState.put(name, 2);
            return signal;
        }

        private String signalForExpression(Expression expression) {
            Expression normalized = Builder.simplify(expression);
            if (normalized instanceof Expression.Constant constant) {
                return constant.value() ? "1" : "0";
            }
            if (normalized instanceof Expression.Variable variable) {
                if (assignments.containsKey(variable.name())) {
                    return signalForAssignment(variable.name());
                }
                inputs.add(variable.name());
                return variable.name();
            }
            if (normalized instanceof Expression.Unary unary) {
                return intern(GateOperator.NOT,
                        List.of(signalForExpression(unary.operand())));
            }
            if (normalized instanceof Expression.Binary binary) {
                return intern(GateOperator.valueOf(binary.operator().name()), List.of(
                        signalForExpression(binary.left()),
                        signalForExpression(binary.right())));
            }
            throw new IllegalStateException("unknown expression node: " + normalized);
        }

        private String intern(GateOperator type, List<String> inputs) {
            GateKey key = new GateKey(type, inputs);
            String existing = sharedGates.get(key);
            if (existing != null) {
                return existing;
            }
            String id;
            do {
                id = "g" + nextGateNumber++;
            } while (!reservedIds.add(id));
            gates.add(new Netlist.Gate(id, type, inputs));
            sharedGates.put(key, id);
            return id;
        }
    }

    private static final class Builder {
        private final Set<String> reservedIds = new HashSet<>();
        private final Map<Expression, String> expressionSignals = new HashMap<>();
        private final Map<GateKey, String> sharedGates = new LinkedHashMap<>();
        private final List<Netlist.Gate> gates = new ArrayList<>();
        private int nextGateNumber = 1;

        private Builder(Set<String> inputNames) {
            reservedIds.addAll(inputNames);
        }

        private List<Netlist.Gate> gates() {
            return List.copyOf(gates);
        }

        private String signalFor(Expression expression) {
            Expression normalized = simplify(expression);
            String cached = expressionSignals.get(normalized);
            if (cached != null) {
                return cached;
            }

            final String signal;
            if (normalized instanceof Expression.Constant constant) {
                signal = constant.value() ? "1" : "0";
            } else if (normalized instanceof Expression.Variable variable) {
                signal = variable.name();
            } else if (normalized instanceof Expression.Unary unary) {
                String operand = signalFor(unary.operand());
                signal = intern(GateOperator.NOT, List.of(operand));
            } else if (normalized instanceof Expression.Binary binary) {
                String left = signalFor(binary.left());
                String right = signalFor(binary.right());
                GateOperator operator = GateOperator.valueOf(binary.operator().name());
                signal = intern(operator, List.of(left, right));
            } else {
                throw new IllegalStateException("unknown expression node: " + normalized);
            }

            expressionSignals.put(normalized, signal);
            return signal;
        }

        private String intern(GateOperator type, List<String> inputs) {
            GateKey key = new GateKey(type, inputs);
            String existing = sharedGates.get(key);
            if (existing != null) {
                return existing;
            }
            String id;
            do {
                id = "g" + nextGateNumber++;
            } while (!reservedIds.add(id));
            gates.add(new Netlist.Gate(id, type, inputs));
            sharedGates.put(key, id);
            return id;
        }

        private static Expression simplify(Expression expression) {
            return switch (expression) {
                case Expression.Constant constant -> constant;
                case Expression.Variable variable -> variable;
                case Expression.Unary unary ->
                        negate(simplify(unary.operand()));
                case Expression.Binary binary ->
                        simplifyBinary(binary.operator(),
                                simplify(binary.left()), simplify(binary.right()));
                case Expression.PartCall call -> new Expression.PartCall(
                        call.kind(), call.arguments().stream()
                                .map(Builder::simplify).toList());
                case Expression.ComponentCall call -> new Expression.ComponentCall(
                        call.componentName(), call.arguments().stream()
                                .map(Builder::simplify).toList());
                case Expression.ComponentOutput output -> output;
            };
        }

        private static Expression simplifyBinary(
                Expression.BinaryOperator operator, Expression left, Expression right) {
            if (left.equals(right)) {
                return switch (operator) {
                    case AND, OR -> left;
                    case NAND, NOR -> negate(left);
                    case XOR -> new Expression.Constant(false);
                    case XNOR -> new Expression.Constant(true);
                };
            }

            if (left instanceof Expression.Constant leftConstant
                    && right instanceof Expression.Constant rightConstant) {
                boolean leftValue = leftConstant.value();
                boolean rightValue = rightConstant.value();
                boolean result = switch (operator) {
                    case AND -> leftValue && rightValue;
                    case NAND -> !(leftValue && rightValue);
                    case XOR -> leftValue ^ rightValue;
                    case XNOR -> leftValue == rightValue;
                    case OR -> leftValue || rightValue;
                    case NOR -> !(leftValue || rightValue);
                };
                return new Expression.Constant(result);
            }

            if (left instanceof Expression.Constant constant) {
                return simplifyWithConstant(operator, constant.value(), right);
            }
            if (right instanceof Expression.Constant constant) {
                return simplifyWithConstant(operator, constant.value(), left);
            }
            return new Expression.Binary(operator, left, right);
        }

        private static Expression simplifyWithConstant(
                Expression.BinaryOperator operator, boolean constant, Expression other) {
            return switch (operator) {
                case AND -> constant ? other : new Expression.Constant(false);
                case NAND -> constant ? negate(other) : new Expression.Constant(true);
                case OR -> constant ? new Expression.Constant(true) : other;
                case NOR -> constant ? new Expression.Constant(false) : negate(other);
                case XOR -> constant ? negate(other) : other;
                case XNOR -> constant ? other : negate(other);
            };
        }

        private static Expression negate(Expression expression) {
            if (expression instanceof Expression.Constant constant) {
                return new Expression.Constant(!constant.value());
            }
            if (expression instanceof Expression.Unary unary
                    && unary.operator() == Expression.UnaryOperator.NOT) {
                return unary.operand();
            }
            return new Expression.Unary(Expression.UnaryOperator.NOT, expression);
        }
    }
}
