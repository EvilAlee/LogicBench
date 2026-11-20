package io.github.evilalee.gatelab.synth;

import java.util.ArrayList;
import java.util.HashMap;
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

    /** Parsed ASTs are retained so the engine truth table can be checked independently. */
    public record Compilation(
            Netlist netlist,
            List<NamedExpression> expressions,
            List<String> reusedOutputNames,
            Optional<FeedbackCircuitExtractor.Extraction> feedbackExtraction) {
        public Compilation(Netlist netlist, List<NamedExpression> expressions) {
            this(netlist, expressions, List.of(), Optional.empty());
        }

        public Compilation(
                Netlist netlist, List<NamedExpression> expressions,
                List<String> reusedOutputNames) {
            this(netlist, expressions, reusedOutputNames, Optional.empty());
        }

        public Compilation {
            Objects.requireNonNull(netlist, "netlist");
            expressions = List.copyOf(Objects.requireNonNull(expressions, "expressions"));
            reusedOutputNames = List.copyOf(
                    Objects.requireNonNull(reusedOutputNames, "reusedOutputNames"));
            feedbackExtraction = Objects.requireNonNull(feedbackExtraction, "feedbackExtraction");
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
    public static Compilation compile(String source) {
        Objects.requireNonNull(source, "source");
        List<Statement> statements = splitStatements(source);
        if (statements.isEmpty()) {
            throw new ParseException(0, "expression list is empty");
        }

        List<Assignment> assignments = new ArrayList<>();
        Map<String, Assignment> assignmentsByName = new LinkedHashMap<>();
        for (Statement statement : statements) {
            Assignment assignment = parseStatement(statement);
            if (assignmentsByName.putIfAbsent(assignment.name(), assignment) != null) {
                throw new IllegalArgumentException(
                        assignment.kind() == AssignmentKind.OUTPUT
                                ? "duplicate output name: " + assignment.name()
                                : "duplicate signal definition: " + assignment.name());
            }
            assignments.add(assignment);
        }

        List<String> reusedOutputNames = findReusedOutputNames(assignments);
        boolean hasFeedback = hasReachableFeedback(assignments, assignmentsByName);
        Map<String, Expression> expandedAssignments = new HashMap<>();
        List<NamedExpression> expressions = new ArrayList<>();
        List<Assignment> outputAssignments = assignments.stream()
                .filter(assignment -> assignment.kind() == AssignmentKind.OUTPUT)
                .toList();
        for (Assignment assignment : outputAssignments) {
            Expression expanded = expandAssignment(
                    assignment.name(), assignmentsByName, expandedAssignments, new HashSet<>());
            expressions.add(new NamedExpression(assignment.name(), expanded));
        }

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
        return new Compilation(netlist, expressions, reusedOutputNames);
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
                gates.add(new Netlist.Gate(name, GateOperator.BUFFER, List.of(input)));
                signal = name;
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
