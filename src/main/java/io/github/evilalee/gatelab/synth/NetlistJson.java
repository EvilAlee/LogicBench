package io.github.evilalee.gatelab.synth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Gson-backed JSON reader and writer for validated {@link Netlist} values. */
public final class NetlistJson {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private NetlistJson() {
    }

    public static String toJson(Netlist netlist) {
        Objects.requireNonNull(netlist, "netlist");
        JsonObject root = new JsonObject();
        JsonArray inputs = new JsonArray();
        netlist.inputs().forEach(inputs::add);
        root.add("inputs", inputs);

        JsonArray outputs = new JsonArray();
        for (Netlist.Output output : netlist.outputs()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", output.name());
            item.addProperty("source", output.source());
            outputs.add(item);
        }
        root.add("outputs", outputs);

        JsonArray gates = new JsonArray();
        for (Netlist.Gate gate : netlist.gates()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", gate.id());
            item.addProperty("type", gate.type().name());
            JsonArray references = new JsonArray();
            gate.inputs().forEach(references::add);
            item.add("inputs", references);
            gates.add(item);
        }
        root.add("gates", gates);
        return GSON.toJson(root);
    }

    public static Netlist fromJson(String json) {
        Objects.requireNonNull(json, "json");
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(
                    "invalid netlist JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("netlist JSON root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();

        JsonArray inputArray = requireArray(root, "inputs", "netlist");
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < inputArray.size(); index++) {
            inputs.add(requireString(inputArray.get(index), "inputs[" + index + "]"));
        }

        JsonArray outputArray = requireArray(root, "outputs", "netlist");
        List<Netlist.Output> outputs = new ArrayList<>();
        for (int index = 0; index < outputArray.size(); index++) {
            String context = "outputs[" + index + "]";
            JsonObject item = requireObject(outputArray.get(index), context);
            outputs.add(new Netlist.Output(
                    requireString(item.get("name"), context + ".name"),
                    requireString(item.get("source"), context + ".source")));
        }

        JsonArray gateArray = requireArray(root, "gates", "netlist");
        List<Netlist.Gate> gates = new ArrayList<>();
        for (int index = 0; index < gateArray.size(); index++) {
            String context = "gates[" + index + "]";
            JsonObject item = requireObject(gateArray.get(index), context);
            String id = requireString(item.get("id"), context + ".id");
            String typeText = requireString(item.get("type"), context + ".type");
            final GateOperator type;
            try {
                type = GateOperator.valueOf(typeText.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        context + ".type has unknown gate type: " + typeText, exception);
            }
            JsonArray references = requireArray(item, "inputs", context);
            List<String> gateInputs = new ArrayList<>();
            for (int inputIndex = 0; inputIndex < references.size(); inputIndex++) {
                gateInputs.add(requireString(
                        references.get(inputIndex),
                        context + ".inputs[" + inputIndex + "]"));
            }
            gates.add(new Netlist.Gate(id, type, gateInputs));
        }
        return new Netlist(inputs, outputs, gates);
    }

    private static JsonArray requireArray(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(context + "." + field + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement element, String context) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requireString(JsonElement element, String context) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(context + " must be a non-empty string");
        }
        return element.getAsString();
    }
}
