package io.github.evilalee.gatelab.synth.generators;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable template output in the same JSON shape consumed by AiCircuitService. */
public record GeneratedCircuit(
        String generator,
        Map<String, String> params,
        String summary,
        List<String> inputs,
        List<PinExpression> outputs,
        List<PinExpression> signals,
        JsonObject spec) {

    public record PinExpression(String name, String expression) {
        public PinExpression {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("pin name is blank");
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("expression for " + name + " is blank");
            }
        }
    }

    public GeneratedCircuit {
        if (generator == null || generator.isBlank()) throw new IllegalArgumentException("generator is blank");
        params = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(params, "params")));
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is blank");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        spec = Objects.requireNonNull(spec, "spec").deepCopy();
    }

    public JsonObject spec() {
        return spec.deepCopy();
    }

    /** Ordered source for ExpressionCompiler: internal definitions precede output pins. */
    public String expressionSource() {
        List<String> statements = new ArrayList<>();
        for (PinExpression signal : signals) {
            statements.add(signal.name() + " := " + signal.expression());
        }
        for (PinExpression output : outputs) {
            statements.add(output.name() + " = " + output.expression());
        }
        return String.join("\n", statements);
    }

    /** Serializes exactly the free-form answer fields already accepted by the AI pipeline. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("summary", summary);
        JsonArray inputArray = new JsonArray();
        inputs.forEach(inputArray::add);
        root.add("inputs", inputArray);

        JsonArray outputArray = new JsonArray();
        for (PinExpression output : outputs) {
            JsonObject item = new JsonObject();
            item.addProperty("name", output.name());
            item.addProperty("expression", output.expression());
            outputArray.add(item);
        }
        root.add("outputs", outputArray);

        JsonArray signalArray = new JsonArray();
        for (PinExpression signal : signals) {
            JsonObject item = new JsonObject();
            item.addProperty("name", signal.name());
            item.addProperty("expression", signal.expression());
            signalArray.add(item);
        }
        root.add("signals", signalArray);
        root.add("spec", spec.deepCopy());
        return root;
    }

    public String json() {
        return new com.google.gson.Gson().toJson(toJson());
    }
}
