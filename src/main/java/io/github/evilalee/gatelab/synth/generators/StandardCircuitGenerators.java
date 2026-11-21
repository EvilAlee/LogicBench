package io.github.evilalee.gatelab.synth.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Registry and implementations for deterministic common-circuit templates. */
public final class StandardCircuitGenerators {
    private record Rule(String output, String expression) { }

    private record Definition(
            String id,
            Map<String, Parameter> parameters,
            Function<Map<String, String>, GeneratedCircuit> builder) implements TemplateGenerator {
        private Definition {
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        @Override
        public GeneratedCircuit build(Map<String, String> supplied) {
            Map<String, String> values = supplied == null ? Map.of() : supplied;
            if (!values.keySet().equals(parameters.keySet())) {
                List<String> missing = parameters.keySet().stream()
                        .filter(key -> !values.containsKey(key)).toList();
                List<String> unexpected = values.keySet().stream()
                        .filter(key -> !parameters.containsKey(key)).toList();
                throw new IllegalArgumentException("parameters for " + id
                        + " invalid; missing=" + missing + ", unexpected=" + unexpected);
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Parameter> parameter : parameters.entrySet()) {
                String value = values.get(parameter.getKey());
                if (!parameter.getValue().accepts(value)) {
                    throw new IllegalArgumentException("parameter '" + parameter.getKey()
                            + "' for " + id + " must be one of "
                            + parameter.getValue().values() + "; got " + value);
                }
                normalized.put(parameter.getKey(), value);
            }
            GeneratedCircuit answer = builder.apply(Collections.unmodifiableMap(normalized));
            validateAnswer(answer);
            return answer;
        }
    }

    private static final List<TemplateGenerator> GENERATORS = List.of(
            definition("ripple_adder", Map.of(
                    "n", Parameter.integerRange(1, 4),
                    "carryIn", Parameter.choices("yes", "no")), StandardCircuitGenerators::rippleAdder),
            definition("subtractor", Map.of("n", Parameter.integerRange(1, 4)),
                    StandardCircuitGenerators::subtractor),
            definition("comparator", Map.of("n", Parameter.integerRange(1, 4)),
                    StandardCircuitGenerators::comparator),
            definition("array_multiplier", Map.of(
                    "n", Parameter.integerRange(1, 4),
                    "m", Parameter.integerRange(1, 4)), StandardCircuitGenerators::arrayMultiplier),
            // k=3 requires 11 one-bit inputs (8 data + 3 select), exceeding the shared 10-input cap.
            definition("mux", Map.of("k", Parameter.integerRange(1, 2)),
                    StandardCircuitGenerators::mux),
            definition("decoder", Map.of(
                    "n", Parameter.integerRange(1, 3),
                    "enable", Parameter.choices("yes", "no")), StandardCircuitGenerators::decoder),
            definition("parity", Map.of(
                    "n", Parameter.integerRange(2, 8),
                    "mode", Parameter.choices("even", "odd")), StandardCircuitGenerators::parity),
            definition("majority", Map.of("n", Parameter.choices("3", "5")),
                    StandardCircuitGenerators::majority),
            definition("gray_to_binary", Map.of("n", Parameter.integerRange(2, 4)),
                    StandardCircuitGenerators::grayToBinary),
            definition("binary_to_gray", Map.of("n", Parameter.integerRange(2, 4)),
                    StandardCircuitGenerators::binaryToGray)
    );

    private StandardCircuitGenerators() { }

    private static Definition definition(
            String id, Map<String, Parameter> parameters,
            Function<Map<String, String>, GeneratedCircuit> builder) {
        return new Definition(id, parameters, builder);
    }

    public static List<TemplateGenerator> all() {
        return GENERATORS;
    }

    public static TemplateGenerator get(String id) {
        Objects.requireNonNull(id, "id");
        return GENERATORS.stream().filter(generator -> generator.id().equals(id))
                .findFirst().orElse(null);
    }

    public static TemplateGenerator require(String id) {
        TemplateGenerator generator = get(id);
        if (generator == null) {
            throw new IllegalArgumentException("unknown generator '" + id
                    + "'; supported generators: "
                    + String.join(", ", GENERATORS.stream().map(TemplateGenerator::id).toList()));
        }
        return generator;
    }

    private static void validateAnswer(GeneratedCircuit answer) {
        if (answer.inputs().isEmpty() || answer.inputs().size() > 10) {
            throw new IllegalArgumentException("generated circuit must have 1 to 10 inputs");
        }
        if (answer.outputs().isEmpty() || answer.outputs().size() > 16) {
            throw new IllegalArgumentException("generated circuit must have 1 to 16 outputs");
        }
        if (answer.signals().size() > 160) {
            throw new IllegalArgumentException("generated circuit exceeds 160 signals");
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>(answer.inputs());
        if (names.size() != answer.inputs().size()) {
            throw new IllegalArgumentException("generated circuit contains duplicate input names");
        }
        int total = 0;
        for (GeneratedCircuit.PinExpression expression : answer.signals()) {
            if (expression.expression().length() > 300) {
                throw new IllegalArgumentException("signal " + expression.name() + " exceeds 300 characters");
            }
            total += expression.expression().length();
        }
        for (GeneratedCircuit.PinExpression expression : answer.outputs()) {
            if (expression.expression().length() > 300) {
                throw new IllegalArgumentException("output " + expression.name() + " exceeds 300 characters");
            }
            total += expression.expression().length();
        }
        for (com.google.gson.JsonElement element : answer.spec().getAsJsonArray("rules")) {
            JsonObject rule = element.getAsJsonObject();
            String expression = rule.get("expression").getAsString();
            if (expression.length() > 300) {
                throw new IllegalArgumentException("spec rule exceeds 300 characters");
            }
            total += expression.length();
        }
        if (total > 3_000) {
            throw new IllegalArgumentException("generated circuit exceeds 3000 expression characters");
        }
    }

    private static GeneratedCircuit rippleAdder(Map<String, String> p) {
        int n = number(p, "n");
        boolean carryIn = yes(p, "carryIn");
        List<String> inputs = new ArrayList<>();
        inputs.addAll(bits("A", n));
        inputs.addAll(bits("B", n));
        if (carryIn) inputs.add("CIN");

        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) {
            signals.add(signal("G" + bit, "A" + bit + " & B" + bit));
        }
        for (int bit = 0; bit < n; bit++) {
            signals.add(signal("P" + bit, "A" + bit + " ^ B" + bit));
        }
        for (int bit = 0; bit < n; bit++) {
            String carry = bit == 0 ? (carryIn ? "CIN" : "0") : "C" + bit;
            signals.add(signal("C" + (bit + 1), carry.equals("0")
                    ? "G" + bit : "G" + bit + " | (P" + bit + " & " + carry + ")"));
        }

        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) {
            String carry = bit == 0 ? (carryIn ? "CIN" : "0") : "C" + bit;
            outputs.add(output("S" + bit, carry.equals("0")
                    ? "P" + bit : "P" + bit + " ^ " + carry));
        }
        outputs.add(output("COUT", "C" + n));
        String total = carryIn ? "A + B + CIN" : "A + B";
        return answer("ripple_adder", p, "Ripple-carry adder (" + n + " bits)",
                inputs, outputs, signals,
                spec(buses("A", reverseBits("A", n), "B", reverseBits("B", n),
                        "S", reverseBits("S", n)),
                        new Rule("S", total), new Rule("COUT", "(" + total + ") >> " + n)));
    }

    private static GeneratedCircuit subtractor(Map<String, String> p) {
        int n = number(p, "n");
        List<String> inputs = combinedBits(n, n);
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) {
            signals.add(signal("P" + bit, "A" + bit + " ^ B" + bit));
            signals.add(signal("E" + bit, "!P" + bit));
            signals.add(signal("G" + bit, "!A" + bit + " & B" + bit));
        }
        for (int bit = 0; bit < n; bit++) {
            String expression = bit == 0 ? "G0"
                    : "G" + bit + " | (E" + bit + " & BR" + bit + ")";
            signals.add(signal("BR" + (bit + 1), expression));
        }
        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) {
            outputs.add(output("D" + bit, bit == 0 ? "P0" : "P" + bit + " ^ BR" + bit));
        }
        outputs.add(output("BOUT", "BR" + n));
        return answer("subtractor", p, "Unsigned ripple subtractor (" + n + " bits)",
                inputs, outputs, signals,
                spec(buses("A", reverseBits("A", n), "B", reverseBits("B", n),
                        "D", reverseBits("D", n)),
                        new Rule("D", "A - B"),
                        new Rule("BOUT", "(A < B) ? 1 : 0")));
    }

    private static GeneratedCircuit comparator(Map<String, String> p) {
        int n = number(p, "n");
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) {
            signals.add(signal("E" + bit, "!(A" + bit + " ^ B" + bit + ")"));
        }
        for (int bit = 0; bit < n; bit++) {
            String less = bit == 0 ? "!A0 & B0"
                    : "(!A" + bit + " & B" + bit + ") | (E" + bit + " & LT" + (bit - 1) + ")";
            String greater = bit == 0 ? "A0 & !B0"
                    : "(A" + bit + " & !B" + bit + ") | (E" + bit + " & GT" + (bit - 1) + ")";
            signals.add(signal("LT" + bit, less));
            signals.add(signal("GT" + bit, greater));
            signals.add(signal("EQ" + bit, bit == 0 ? "E0"
                    : "EQ" + (bit - 1) + " & E" + bit));
        }
        List<GeneratedCircuit.PinExpression> outputs = List.of(
                output("LT", "LT" + (n - 1)),
                output("EQ", "EQ" + (n - 1)),
                output("GT", "GT" + (n - 1)));
        return answer("comparator", p, "Unsigned magnitude comparator (" + n + " bits)",
                combinedBits(n, n), outputs, signals,
                spec(buses("A", reverseBits("A", n), "B", reverseBits("B", n)),
                        new Rule("LT", "A < B"),
                        new Rule("EQ", "A == B"),
                        new Rule("GT", "A > B")));
    }

    private static GeneratedCircuit arrayMultiplier(Map<String, String> p) {
        int n = number(p, "n");
        int m = number(p, "m");
        int width = n + m;
        List<String> inputs = combinedBits(n, m);
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        for (int multiplierBit = 0; multiplierBit < m; multiplierBit++) {
            for (int multiplicandBit = 0; multiplicandBit < n; multiplicandBit++) {
                signals.add(signal("P" + multiplicandBit + multiplierBit,
                        "A" + multiplicandBit + " & B" + multiplierBit));
            }
        }

        List<String> sumBits = new ArrayList<>();
        for (int bit = 0; bit < width; bit++) {
            sumBits.add(bit < n ? "P" + bit + "0" : "0");
        }
        for (int stage = 1; stage < m; stage++) {
            List<String> nextSum = new ArrayList<>(width);
            String carry = "0";
            for (int bit = 0; bit < width; bit++) {
                String left = sumBits.get(bit);
                String right = bit >= stage && bit - stage < n
                        ? "P" + (bit - stage) + stage : "0";
                String propagate = "PR" + stage + "_" + bit;
                String sum = "SS" + stage + "_" + bit;
                signals.add(signal(propagate, left + " ^ " + right));
                if (bit == 0) {
                    String firstCarry = "CS" + stage + "_1";
                    signals.add(signal(firstCarry, propagate + " & 0"));
                    signals.add(signal(sum, propagate));
                    carry = firstCarry;
                } else if (bit < width - 1) {
                    String generate = "GN" + stage + "_" + bit;
                    String nextCarry = "CS" + stage + "_" + (bit + 1);
                    signals.add(signal(generate, left + " & " + right));
                    signals.add(signal(nextCarry,
                            generate + " | (" + propagate + " & " + carry + ")"));
                    signals.add(signal(sum, propagate + " ^ " + carry));
                    carry = nextCarry;
                } else {
                    signals.add(signal(sum, propagate + " ^ " + carry));
                }
                nextSum.add(sum);
            }
            sumBits = List.copyOf(nextSum);
        }

        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        for (int bit = 0; bit < width; bit++) {
            outputs.add(output("P" + bit, sumBits.get(bit)));
        }
        return answer("array_multiplier", p, "Unsigned array multiplier (" + n + " by " + m + " bits)",
                inputs, outputs, signals,
                spec(buses("A", reverseBits("A", n), "B", reverseBits("B", m),
                        "P", reverseBits("P", width)),
                        new Rule("P", "A * B")));
    }

    private static GeneratedCircuit mux(Map<String, String> p) {
        int k = number(p, "k");
        int dataCount = 1 << k;
        List<String> inputs = bits("D", dataCount);
        inputs.addAll(bits("S", k));
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        List<String> level = new ArrayList<>(bits("D", dataCount));
        for (int stage = 1; stage <= k; stage++) {
            List<String> next = new ArrayList<>();
            for (int index = 0; index < level.size() / 2; index++) {
                String name = "M" + stage + "_" + index;
                String select = "S" + (stage - 1);
                String low = level.get(index * 2);
                String high = level.get(index * 2 + 1);
                signals.add(signal(name, "(!" + select + " & " + low + ") | ("
                        + select + " & " + high + ")"));
                next.add(name);
            }
            level = List.copyOf(next);
        }
        List<String> dataBus = reverseBits("D", dataCount);
        List<String> selectBus = reverseBits("S", k);
        return answer("mux", p, "2-to-" + dataCount + " multiplexer",
                inputs, List.of(output("Y", level.getFirst())), signals,
                spec(buses("D", dataBus, "S", selectBus),
                        new Rule("Y", "(D >> S) & 1")));
    }

    private static GeneratedCircuit decoder(Map<String, String> p) {
        int n = number(p, "n");
        boolean enabled = yes(p, "enable");
        List<String> inputs = new ArrayList<>(bits("A", n));
        if (enabled) inputs.add("EN");
        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        for (int value = 0; value < (1 << n); value++) {
            List<String> terms = new ArrayList<>();
            if (enabled) terms.add("EN");
            for (int bit = 0; bit < n; bit++) {
                terms.add(((value >>> bit) & 1) == 1 ? "A" + bit : "!A" + bit);
            }
            outputs.add(output("Y" + value, String.join(" & ", terms)));
        }
        String expected = enabled ? "EN * (1 << A)" : "1 << A";
        Map<String, List<String>> buses = new LinkedHashMap<>();
        buses.put("A", reverseBits("A", n));
        buses.put("Y", reverseBits("Y", 1 << n));
        return answer("decoder", p, "Active-high " + (enabled ? "enabled " : "")
                        + n + "-to-" + (1 << n) + " decoder",
                inputs, outputs, List.of(),
                spec(buses, new Rule("Y", expected)));
    }

    private static GeneratedCircuit parity(Map<String, String> p) {
        int n = number(p, "n");
        boolean even = p.get("mode").equals("even");
        List<String> inputs = bits("A", n);
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        String previous = "A0";
        for (int bit = 1; bit < n; bit++) {
            String name = "X" + bit;
            signals.add(signal(name, previous + " ^ A" + bit));
            previous = name;
        }
        String expression = even ? "!" + previous : previous;
        String expected = even
                ? "(popcount(A) % 2) == 0 ? 1 : 0"
                : "popcount(A) % 2";
        return answer("parity", p, (even ? "Even" : "Odd") + " parity (" + n + " bits)",
                inputs, List.of(output("PARITY", expression)), signals,
                spec(buses("A", reverseBits("A", n)), new Rule("PARITY", expected)));
    }

    private static GeneratedCircuit majority(Map<String, String> p) {
        int n = number(p, "n");
        int threshold = n / 2 + 1;
        List<List<Integer>> combinations = new ArrayList<>();
        combinations(n, threshold, 0, new ArrayList<>(), combinations);
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        for (int index = 0; index < combinations.size(); index++) {
            String name = "T" + index;
            List<String> literals = combinations.get(index).stream()
                    .map(bit -> "A" + bit).toList();
            signals.add(signal(name, String.join(" & ", literals)));
            terms.add(name);
        }
        String expected = "(popcount(A) >= " + threshold + ") ? 1 : 0";
        return answer("majority", p, n + "-input majority",
                bits("A", n), List.of(output("Y", String.join(" | ", terms))), signals,
                spec(buses("A", reverseBits("A", n)), new Rule("Y", expected)));
    }

    private static GeneratedCircuit grayToBinary(Map<String, String> p) {
        int n = number(p, "n");
        List<GeneratedCircuit.PinExpression> signals = new ArrayList<>();
        signals.add(signal("BX" + (n - 1), "G" + (n - 1)));
        for (int bit = n - 2; bit >= 0; bit--) {
            signals.add(signal("BX" + bit, "G" + bit + " ^ BX" + (bit + 1)));
        }
        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        for (int bit = 0; bit < n; bit++) outputs.add(output("B" + bit, "BX" + bit));
        return answer("gray_to_binary", p, "Gray-to-binary converter (" + n + " bits)",
                bits("G", n), outputs, signals,
                spec(buses("G", reverseBits("G", n), "B", reverseBits("B", n)),
                        new Rule("B", xorShiftExpression("G", n))));
    }

    private static GeneratedCircuit binaryToGray(Map<String, String> p) {
        int n = number(p, "n");
        List<GeneratedCircuit.PinExpression> outputs = new ArrayList<>();
        outputs.add(output("G" + (n - 1), "B" + (n - 1)));
        for (int bit = n - 2; bit >= 0; bit--) {
            outputs.add(output("G" + bit, "B" + (bit + 1) + " ^ B" + bit));
        }
        return answer("binary_to_gray", p, "Binary-to-Gray converter (" + n + " bits)",
                bits("B", n), outputs, List.of(),
                spec(buses("B", reverseBits("B", n), "G", reverseBits("G", n)),
                        new Rule("G", "B ^ (B >> 1)")));
    }

    private static String xorShiftExpression(String bus, int width) {
        List<String> terms = new ArrayList<>();
        for (int shift = 0; shift < width; shift++) {
            terms.add(shift == 0 ? bus : "(" + bus + " >> " + shift + ")");
        }
        return String.join(" ^ ", terms);
    }

    private static void combinations(
            int n, int remaining, int start, List<Integer> current, List<List<Integer>> result) {
        if (remaining == 0) {
            result.add(List.copyOf(current));
            return;
        }
        for (int bit = start; bit <= n - remaining; bit++) {
            current.add(bit);
            combinations(n, remaining - 1, bit + 1, current, result);
            current.removeLast();
        }
    }

    private static GeneratedCircuit answer(
            String id, Map<String, String> params, String summary, List<String> inputs,
            List<GeneratedCircuit.PinExpression> outputs,
            List<GeneratedCircuit.PinExpression> signals, JsonObject spec) {
        return new GeneratedCircuit(id, params, summary, inputs, outputs, signals, spec);
    }

    private static GeneratedCircuit.PinExpression signal(String name, String expression) {
        return new GeneratedCircuit.PinExpression(name, expression);
    }

    private static GeneratedCircuit.PinExpression output(String name, String expression) {
        return new GeneratedCircuit.PinExpression(name, expression);
    }

    private static int number(Map<String, String> params, String name) {
        return Integer.parseInt(params.get(name));
    }

    private static boolean yes(Map<String, String> params, String name) {
        return params.get(name).equals("yes");
    }

    private static List<String> bits(String prefix, int count) {
        List<String> result = new ArrayList<>(count);
        for (int bit = 0; bit < count; bit++) result.add(prefix + bit);
        return result;
    }

    private static List<String> combinedBits(int first, int second) {
        List<String> result = new ArrayList<>(bits("A", first));
        result.addAll(bits("B", second));
        return result;
    }

    private static List<String> reverseBits(String prefix, int count) {
        List<String> result = new ArrayList<>(count);
        for (int bit = count - 1; bit >= 0; bit--) result.add(prefix + bit);
        return result;
    }

    private static Map<String, List<String>> buses(Object... values) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            String name = (String) values[index];
            List<?> rawPins = (List<?>) values[index + 1];
            List<String> pins = rawPins.stream().map(String.class::cast).toList();
            result.put(name, pins);
        }
        return result;
    }

    private static JsonObject spec(Map<String, List<String>> buses, Rule... rules) {
        JsonObject root = new JsonObject();
        JsonObject busObject = new JsonObject();
        buses.forEach((name, pins) -> {
            JsonArray array = new JsonArray();
            pins.forEach(array::add);
            busObject.add(name, array);
        });
        root.add("buses", busObject);
        JsonArray ruleArray = new JsonArray();
        for (Rule rule : rules) {
            JsonObject item = new JsonObject();
            item.addProperty("out", rule.output());
            item.addProperty("expression", rule.expression());
            ruleArray.add(item);
        }
        root.add("rules", ruleArray);
        return root;
    }
}
