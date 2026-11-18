package io.github.evilalee.gatelab.synth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            List<String> reusedOutputNames) {
        public Compilation(Netlist netlist, List<NamedExpression> expressions) {
            this(netlist, expressions, List.of());
        }

        public Compilation {
            Objects.requireNonNull(netlist, "netlist");
            expressions = List.copyOf(Objects.requireNonNull(expressions, "expressions"));
            reusedOutputNames = List.copyOf(
                    Objects.requireNonNull(reusedOutputNames, "reusedOutputNames"));
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
        Set<String> definedNames = new HashSet<>();
        for (Statement statement : statements) {
            Assignment assignment = parseStatement(statement);
            if (!definedNames.add(assignment.name())) {
                throw new IllegalArgumentException(
                        assignment.kind() == AssignmentKind.OUTPUT
                                ? "duplicate output name: " + assignment.name()
                                : "duplicate signal definition: " + assignment.name());
            }
            assignments.add(assignment);
        }

        List<String> reusedOutputNames = findReusedOutputNames(assignments);
        Map<String, Expression> internalSignals = new LinkedHashMap<>();
        List<NamedExpression> expressions = new ArrayList<>();
        LinkedHashSet<String> variables = new LinkedHashSet<>();
        for (Assignment assignment : assignments) {
            Expression resolved = resolveInternalSignals(
                    assignment.expression(), internalSignals);
            if (assignment.kind() == AssignmentKind.INTERNAL) {
                internalSignals.put(assignment.name(), resolved);
            } else {
                NamedExpression named = new NamedExpression(assignment.name(), resolved);
                expressions.add(named);
                variables.addAll(resolved.variables());
            }
        }

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

    private static List<String> findReusedOutputNames(List<Assignment> assignments) {
        LinkedHashSet<String> reused = new LinkedHashSet<>();
        for (int index = 0; index < assignments.size(); index++) {
            Assignment definition = assignments.get(index);
            if (definition.kind() != AssignmentKind.OUTPUT) {
                continue;
            }
            for (int later = index + 1; later < assignments.size(); later++) {
                if (assignments.get(later).expression().variables()
                        .contains(definition.name())) {
                    reused.add(definition.name());
                    break;
                }
            }
        }
        return List.copyOf(reused);
    }

    private static Expression resolveInternalSignals(
            Expression expression, Map<String, Expression> internalSignals) {
        return switch (expression) {
            case Expression.Constant constant -> constant;
            case Expression.Variable variable ->
                    internalSignals.getOrDefault(variable.name(), variable);
            case Expression.Unary unary -> new Expression.Unary(
                    unary.operator(), resolveInternalSignals(unary.operand(), internalSignals));
            case Expression.Binary binary -> new Expression.Binary(
                    binary.operator(),
                    resolveInternalSignals(binary.left(), internalSignals),
                    resolveInternalSignals(binary.right(), internalSignals));
        };
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
