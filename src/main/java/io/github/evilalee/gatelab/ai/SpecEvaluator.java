package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compiles the model's small integer specification language into an executable,
 * independent oracle. This class has no Swing or engine dependencies.
 */
public final class SpecEvaluator {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    public record Bus(String name, List<String> pins) {
        public Bus {
            Objects.requireNonNull(name, "name");
            pins = List.copyOf(Objects.requireNonNull(pins, "pins"));
            if (pins.isEmpty()) throw new IllegalArgumentException("bus must contain at least one pin");
        }
    }

    public record Rule(String outputBus, List<String> outputPins, String expression) {
        public Rule {
            Objects.requireNonNull(outputBus, "outputBus");
            outputPins = List.copyOf(Objects.requireNonNull(outputPins, "outputPins"));
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record Evaluation(
            Map<String, Boolean> outputPins,
            Map<String, Long> outputBusValues) {
        public Evaluation {
            outputPins = Collections.unmodifiableMap(new LinkedHashMap<>(outputPins));
            outputBusValues = Collections.unmodifiableMap(new LinkedHashMap<>(outputBusValues));
        }
    }

    public static final class SpecException extends IllegalArgumentException {
        private final int position;

        public SpecException(int position, String message) {
            super((position >= 0 ? "at position " + position + ": " : "") + message);
            this.position = position;
        }

        public int position() {
            return position;
        }
    }

    private record CompiledRule(Rule rule, Expr expression) {
    }

    private final List<Bus> inputBuses;
    private final List<Bus> outputBuses;
    private final List<CompiledRule> compiledRules;
    private final Set<String> inputSymbols;

    private SpecEvaluator(
            List<Bus> inputBuses, List<Bus> outputBuses,
            List<CompiledRule> compiledRules, Set<String> inputSymbols) {
        this.inputBuses = List.copyOf(inputBuses);
        this.outputBuses = List.copyOf(outputBuses);
        this.compiledRules = List.copyOf(compiledRules);
        this.inputSymbols = Set.copyOf(inputSymbols);
    }

    public static SpecEvaluator parse(
            JsonObject specification, List<String> inputPins, List<String> outputPins) {
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(inputPins, "inputPins");
        Objects.requireNonNull(outputPins, "outputPins");
        JsonElement busesElement = specification.get("buses");
        if (busesElement == null || !busesElement.isJsonObject()) {
            throw new SpecException(-1, "spec.buses must be an object");
        }
        JsonElement rulesElement = specification.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) {
            throw new SpecException(-1, "spec.rules must be an array");
        }

        Set<String> inputSet = checkedPins(inputPins, "input");
        Set<String> outputSet = checkedPins(outputPins, "output");
        for (String input : inputSet) {
            if (outputSet.contains(input)) {
                throw new SpecException(-1, "pin is both an input and an output: " + input);
            }
        }

        LinkedHashMap<String, Bus> inputBusMap = new LinkedHashMap<>();
        LinkedHashMap<String, Bus> outputBusMap = new LinkedHashMap<>();
        Set<String> assignedInputPins = new LinkedHashSet<>();
        Set<String> assignedOutputPins = new LinkedHashSet<>();
        Set<String> busNames = new LinkedHashSet<>();

        JsonObject busObject = busesElement.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : busObject.entrySet()) {
            String busName = entry.getKey();
            requireName(busName, "bus name");
            if (!busNames.add(busName)) {
                throw new SpecException(-1, "duplicate bus name: " + busName);
            }
            if (!entry.getValue().isJsonArray()) {
                throw new SpecException(-1, "bus " + busName + " must be an array of pin names");
            }
            JsonArray pinArray = entry.getValue().getAsJsonArray();
            if (pinArray.isEmpty()) {
                throw new SpecException(-1, "bus " + busName + " must contain at least one pin");
            }
            List<String> pins = new ArrayList<>();
            Boolean isInputBus = null;
            for (JsonElement pinElement : pinArray) {
                String pin = requireString(pinElement, "pin in bus " + busName);
                requireName(pin, "pin in bus " + busName);
                boolean isInput = inputSet.contains(pin);
                boolean isOutput = outputSet.contains(pin);
                if (!isInput && !isOutput) {
                    throw new SpecException(-1, "bus " + busName
                            + " references undeclared pin: " + pin);
                }
                if (isInputBus != null && isInputBus != isInput) {
                    throw new SpecException(-1,
                            "bus " + busName + " cannot mix input and output pins");
                }
                isInputBus = isInput;
                Set<String> assigned = isInput ? assignedInputPins : assignedOutputPins;
                if (!assigned.add(pin)) {
                    throw new SpecException(-1, "pin belongs to more than one bus: " + pin);
                }
                pins.add(pin);
            }
            if (pins.size() > 1 && pins.contains(busName)) {
                throw new SpecException(-1, "multi-pin bus name cannot also be one of its pins: " + busName);
            }
            Bus bus = new Bus(busName, pins);
            (Boolean.TRUE.equals(isInputBus) ? inputBusMap : outputBusMap).put(busName, bus);
        }

        addImplicitBuses(inputPins, assignedInputPins, inputBusMap, busNames, "input");
        addImplicitBuses(outputPins, assignedOutputPins, outputBusMap, busNames, "output");

        Set<String> inputSymbols = new LinkedHashSet<>();
        inputSymbols.addAll(inputSet);
        inputSymbols.addAll(inputBusMap.keySet());

        JsonArray ruleArray = rulesElement.getAsJsonArray();
        if (ruleArray.isEmpty()) {
            throw new SpecException(-1, "spec.rules must not be empty");
        }
        if (ruleArray.size() > AiCircuitLimits.MAX_SPEC_RULES) {
            throw new SpecException(-1, "spec.rules has " + ruleArray.size()
                    + " entries; maximum is " + AiCircuitLimits.MAX_SPEC_RULES);
        }

        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> coveredOutputBuses = new LinkedHashSet<>();
        Set<String> coveredOutputPins = new LinkedHashSet<>();
        for (int index = 0; index < ruleArray.size(); index++) {
            JsonElement ruleElement = ruleArray.get(index);
            if (!ruleElement.isJsonObject()) {
                throw new SpecException(-1, "spec.rules[" + index + "] must be an object");
            }
            JsonObject ruleObject = ruleElement.getAsJsonObject();
            String out = requireString(ruleObject.get("out"), "spec.rules[" + index + "].out");
            requireName(out, "spec.rules[" + index + "].out");
            String expressionText = requireString(
                    ruleObject.get("expr"), "spec.rules[" + index + "].expr");
            if (expressionText.isBlank()) {
                throw new SpecException(-1, "spec rule for " + out + " has an empty expr");
            }
            Bus outputBus = outputBusMap.get(out);
            if (outputBus == null) {
                throw new SpecException(-1, "spec rule out '" + out
                        + "' is not a declared output bus or output pin");
            }
            if (!coveredOutputBuses.add(out)) {
                throw new SpecException(-1, "duplicate spec rule for output bus: " + out);
            }

            Parser parser = new Parser(expressionText);
            Expr expression = parser.parse();
            for (Map.Entry<String, Integer> variable : parser.variablePositions().entrySet()) {
                if (!inputSymbols.contains(variable.getKey())) {
                    throw new SpecException(variable.getValue(),
                            "unknown input bus or pin '" + variable.getKey() + "'");
                }
            }
            coveredOutputPins.addAll(outputBus.pins());
            compiled.add(new CompiledRule(
                    new Rule(out, outputBus.pins(), expressionText), expression));
        }

        for (Bus outputBus : outputBusMap.values()) {
            for (String pin : outputBus.pins()) {
                if (!coveredOutputPins.contains(pin)) {
                    throw new SpecException(-1,
                            "no spec rule covers output pin '" + pin + "'");
                }
            }
        }
        if (coveredOutputPins.size() != outputSet.size()) {
            throw new SpecException(-1, "spec rules do not cover every output pin");
        }

        return new SpecEvaluator(
                List.copyOf(inputBusMap.values()), List.copyOf(outputBusMap.values()),
                compiled, inputSymbols);
    }

    public List<Bus> inputBuses() {
        return inputBuses;
    }

    public List<Bus> outputBuses() {
        return outputBuses;
    }

    public List<Rule> rules() {
        return compiledRules.stream().map(CompiledRule::rule).toList();
    }

    public Evaluation evaluate(Map<String, Boolean> inputValues) {
        Objects.requireNonNull(inputValues, "inputValues");
        LinkedHashMap<String, Long> environment = new LinkedHashMap<>();
        for (Bus bus : inputBuses) {
            environment.put(bus.name(), packPins(bus.pins(), inputValues));
        }
        for (String pin : inputSymbols) {
            if (inputValues.containsKey(pin)) {
                environment.put(pin, inputValues.get(pin) ? 1L : 0L);
            }
        }

        LinkedHashMap<String, Boolean> outputValues = new LinkedHashMap<>();
        LinkedHashMap<String, Long> busValues = new LinkedHashMap<>();
        for (CompiledRule compiled : compiledRules) {
            Rule rule = compiled.rule();
            long value = compiled.expression().evaluate(environment) & mask(rule.outputPins().size());
            busValues.put(rule.outputBus(), value);
            int width = rule.outputPins().size();
            for (int index = 0; index < width; index++) {
                int shift = width - index - 1;
                outputValues.put(rule.outputPins().get(index), ((value >>> shift) & 1L) != 0);
            }
        }
        return new Evaluation(outputValues, busValues);
    }

    public String formatInputContext(Map<String, Boolean> inputValues) {
        StringBuilder result = new StringBuilder();
        for (Bus bus : inputBuses) {
            if (!result.isEmpty()) result.append(' ');
            result.append(bus.name()).append('=').append(packPins(bus.pins(), inputValues));
        }
        return result.toString();
    }

    public static long packPins(List<String> pins, Map<String, Boolean> values) {
        long result = 0L;
        for (String pin : pins) {
            Boolean value = values.get(pin);
            if (value == null) throw new IllegalArgumentException("missing pin value: " + pin);
            result = (result << 1) | (value ? 1L : 0L);
        }
        return result;
    }

    private static long mask(int width) {
        if (width >= Long.SIZE) return -1L;
        return (1L << width) - 1L;
    }

    private static void addImplicitBuses(
            List<String> pins, Set<String> assigned, Map<String, Bus> buses,
            Set<String> allBusNames, String label) {
        for (String pin : pins) {
            if (assigned.contains(pin)) continue;
            if (allBusNames.contains(pin)) {
                throw new SpecException(-1, label + " pin conflicts with a bus name: " + pin);
            }
            allBusNames.add(pin);
            Bus singleton = new Bus(pin, List.of(pin));
            buses.put(pin, singleton);
        }
    }

    private static Set<String> checkedPins(List<String> pins, String label) {
        Set<String> result = new LinkedHashSet<>();
        for (String pin : pins) {
            requireName(pin, label + " pin");
            if (!result.add(pin)) throw new SpecException(-1, "duplicate " + label + " pin: " + pin);
        }
        return result;
    }

    private static String requireString(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new SpecException(-1, label + " must be a string");
        }
        return element.getAsString();
    }

    private static void requireName(String name, String label) {
        if (!NAME.matcher(name).matches()) {
            throw new SpecException(-1, label + " must match [A-Za-z][A-Za-z0-9_]*: " + name);
        }
    }

    @FunctionalInterface
    private interface Expr {
        long evaluate(Map<String, Long> values);
    }

    private static final class Parser {
        private final String text;
        private final Map<String, Integer> variablePositions = new LinkedHashMap<>();
        private int position;

        private Parser(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        private Map<String, Integer> variablePositions() {
            return Map.copyOf(variablePositions);
        }

        private Expr parse() {
            Expr expression = conditional();
            skipSpace();
            if (position != text.length()) {
                throw error("unexpected token '" + text.charAt(position) + "'");
            }
            return expression;
        }

        private Expr conditional() {
            Expr condition = bitOr();
            if (take("?")) {
                Expr whenTrue = conditional();
                require(":");
                Expr whenFalse = conditional();
                return values -> condition.evaluate(values) != 0
                        ? whenTrue.evaluate(values) : whenFalse.evaluate(values);
            }
            return condition;
        }

        private Expr bitOr() {
            Expr left = bitXor();
            while (takeSingle("|")) {
                Expr right = bitXor();
                Expr previous = left;
                left = values -> previous.evaluate(values) | right.evaluate(values);
            }
            return left;
        }

        private Expr bitXor() {
            Expr left = bitAnd();
            while (take("^")) {
                Expr right = bitAnd();
                Expr previous = left;
                left = values -> previous.evaluate(values) ^ right.evaluate(values);
            }
            return left;
        }

        private Expr bitAnd() {
            Expr left = equality();
            while (takeSingle("&")) {
                Expr right = equality();
                Expr previous = left;
                left = values -> previous.evaluate(values) & right.evaluate(values);
            }
            return left;
        }

        private Expr equality() {
            Expr left = relational();
            while (true) {
                if (take("==")) {
                    Expr right = relational();
                    Expr previous = left;
                    left = values -> previous.evaluate(values) == right.evaluate(values) ? 1L : 0L;
                } else if (take("!=")) {
                    Expr right = relational();
                    Expr previous = left;
                    left = values -> previous.evaluate(values) != right.evaluate(values) ? 1L : 0L;
                } else {
                    return left;
                }
            }
        }

        private Expr relational() {
            Expr left = shift();
            while (true) {
                String operator = takeAny("<=", ">=", "<", ">");
                if (operator == null) return left;
                Expr right = shift();
                Expr previous = left;
                left = switch (operator) {
                    case "<" -> values -> previous.evaluate(values) < right.evaluate(values) ? 1L : 0L;
                    case "<=" -> values -> previous.evaluate(values) <= right.evaluate(values) ? 1L : 0L;
                    case ">" -> values -> previous.evaluate(values) > right.evaluate(values) ? 1L : 0L;
                    case ">=" -> values -> previous.evaluate(values) >= right.evaluate(values) ? 1L : 0L;
                    default -> throw new IllegalStateException(operator);
                };
            }
        }

        private Expr shift() {
            Expr left = additive();
            while (true) {
                String operator = takeAny("<<", ">>");
                if (operator == null) return left;
                Expr right = additive();
                Expr previous = left;
                left = operator.equals("<<")
                        ? values -> previous.evaluate(values) << right.evaluate(values)
                        : values -> previous.evaluate(values) >> right.evaluate(values);
            }
        }

        private Expr additive() {
            Expr left = multiplicative();
            while (true) {
                String operator = takeAny("+", "-");
                if (operator == null) return left;
                Expr right = multiplicative();
                Expr previous = left;
                left = operator.equals("+")
                        ? values -> previous.evaluate(values) + right.evaluate(values)
                        : values -> previous.evaluate(values) - right.evaluate(values);
            }
        }

        private Expr multiplicative() {
            Expr left = unary();
            while (true) {
                String operator = takeAny("*", "/", "%");
                if (operator == null) return left;
                int operatorPosition = position - operator.length();
                Expr right = unary();
                Expr previous = left;
                left = switch (operator) {
                    case "*" -> values -> previous.evaluate(values) * right.evaluate(values);
                    case "/" -> values -> {
                        long divisor = right.evaluate(values);
                        if (divisor == 0) throw new SpecException(operatorPosition, "division by zero");
                        return previous.evaluate(values) / divisor;
                    };
                    case "%" -> values -> {
                        long divisor = right.evaluate(values);
                        if (divisor == 0) throw new SpecException(operatorPosition, "modulo by zero");
                        return previous.evaluate(values) % divisor;
                    };
                    default -> throw new IllegalStateException(operator);
                };
            }
        }

        private Expr unary() {
            if (take("!")) {
                Expr value = unary();
                return values -> value.evaluate(values) == 0 ? 1L : 0L;
            }
            if (take("-")) {
                Expr value = unary();
                return values -> -value.evaluate(values);
            }
            if (take("+")) return unary();
            return primary();
        }

        private Expr primary() {
            skipSpace();
            if (take("(")) {
                Expr nested = conditional();
                require(")");
                return nested;
            }
            if (position < text.length() && Character.isDigit(text.charAt(position))) {
                int start = position;
                while (position < text.length() && Character.isDigit(text.charAt(position))) position++;
                String digits = text.substring(start, position);
                try {
                    return ignored -> Long.parseLong(digits);
                } catch (NumberFormatException exception) {
                    throw new SpecException(start, "decimal constant is outside signed 64-bit range");
                }
            }
            if (position < text.length()
                    && (Character.isLetter(text.charAt(position)) || text.charAt(position) == '_')) {
                int start = position++;
                while (position < text.length()) {
                    char current = text.charAt(position);
                    if (!Character.isLetterOrDigit(current) && current != '_') break;
                    position++;
                }
                String name = text.substring(start, position);
                if (name.equals("popcount")) {
                    require("(");
                    Expr argument = conditional();
                    require(")");
                    return values -> Long.bitCount(argument.evaluate(values));
                }
                variablePositions.putIfAbsent(name, start);
                return values -> {
                    Long value = values.get(name);
                    if (value == null) throw new SpecException(start, "no value for identifier '" + name + "'");
                    return value;
                };
            }
            throw error("expected a decimal constant, pin/bus name, or parenthesized expression");
        }

        private boolean takeSingle(String token) {
            skipSpace();
            if (!text.startsWith(token, position)) return false;
            int next = position + token.length();
            if (next < text.length() && text.charAt(next) == token.charAt(0)) return false;
            position = next;
            return true;
        }

        private boolean take(String token) {
            skipSpace();
            if (!text.startsWith(token, position)) return false;
            position += token.length();
            return true;
        }

        private String takeAny(String... operators) {
            skipSpace();
            for (String operator : operators) {
                if (text.startsWith(operator, position)) {
                    position += operator.length();
                    return operator;
                }
            }
            return null;
        }

        private void require(String token) {
            if (!take(token)) throw error("expected '" + token + "'");
        }

        private void skipSpace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++;
        }

        private SpecException error(String message) {
            return new SpecException(position, message);
        }
    }
}
