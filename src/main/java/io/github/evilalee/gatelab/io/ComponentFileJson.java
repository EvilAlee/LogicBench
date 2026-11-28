package io.github.evilalee.gatelab.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.Schematic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Versioned portable .lbcomp file codec. */
public final class ComponentFileJson {
    public static final int CURRENT_FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ComponentFileJson() { }

    public static String toJson(ComponentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", CURRENT_FORMAT_VERSION);
        root.addProperty("name", definition.name());
        root.addProperty("description", definition.description());
        root.addProperty("category", definition.category());
        JsonArray ports = new JsonArray();
        for (ComponentDefinition.Port port : definition.ports()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", port.name());
            item.addProperty("direction", port.direction().name());
            item.addProperty("isClock", port.isClock());
            ports.add(item);
        }
        root.add("ports", ports);
        if (definition.primaryOutput() != null) {
            root.addProperty("primaryOutput", definition.primaryOutput());
        }
        root.addProperty("hash", definition.hash());
        root.add("internalCircuit",
                JsonParser.parseString(SchematicJson.toJson(definition.internalCircuit())));
        return GSON.toJson(root);
    }

    public static ComponentDefinition fromJson(
            String json, Collection<GateDef> gateDefinitions) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(gateDefinitions, "gateDefinitions");
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid .lbcomp JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException(".lbcomp document must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int version;
        if (root.has("formatVersion")) {
            version = integer(root, "formatVersion");
        } else {
            // Version-1 files created before the .lbcomp extension remain importable.
            version = integer(root, "version");
        }
        if (version != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported .lbcomp formatVersion " + version);
        }
        String name = string(root, "name");
        String description = string(root, "description");
        String category = root.has("category") ? string(root, "category") : "Custom";
        String hash = string(root, "hash");
        String primaryOutput = optionalString(root, "primaryOutput");
        JsonElement portElement = root.get("ports");
        if (portElement == null || !portElement.isJsonArray()) {
            throw new IllegalArgumentException(".lbcomp ports must be an array");
        }
        List<ComponentDefinition.Port> ports = new ArrayList<>();
        JsonArray array = portElement.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement itemElement = array.get(i);
            String context = ".lbcomp ports[" + i + "]";
            if (!itemElement.isJsonObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            JsonObject item = itemElement.getAsJsonObject();
            String portName = string(item, "name");
            ComponentDefinition.Direction direction;
            try {
                direction = ComponentDefinition.Direction.valueOf(string(item, "direction"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(context + ".direction must be IN or OUT", exception);
            }
            boolean isClock = item.has("isClock") && booleanValue(item, "isClock");
            ports.add(new ComponentDefinition.Port(portName, direction, isClock));
        }
        JsonElement circuitElement = root.has("internalCircuit")
                ? root.get("internalCircuit") : root.get("circuit");
        if (circuitElement == null || !circuitElement.isJsonObject()) {
            throw new IllegalArgumentException(".lbcomp internalCircuit must be a circuit object");
        }
        Schematic circuit = SchematicJson.fromJson(circuitElement.toString(), gateDefinitions);
        ComponentDefinition definition = new ComponentDefinition(
                name, description, category, ports, primaryOutput, circuit);
        if (!definition.hash().equalsIgnoreCase(hash)) {
            throw new IllegalArgumentException("component hash mismatch for " + name
                    + ": stored=" + hash + ", calculated=" + definition.hash());
        }
        return definition;
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(".lbcomp " + field + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(".lbcomp " + field + " must be a 32-bit integer", exception);
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(".lbcomp " + field + " must be a non-empty string");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(".lbcomp " + field + " must be a string");
        }
        String result = value.getAsString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean booleanValue(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(".lbcomp " + field + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}
