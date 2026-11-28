package io.github.evilalee.gatelab.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.Schematic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** JSON codec for reusable component definitions and their internal .lbc circuit. */
public final class ComponentDefinitionJson {
    public static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ComponentDefinitionJson() {
    }

    public static String toJson(ComponentDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        root.addProperty("name", definition.name());
        root.addProperty("description", definition.description());
        root.addProperty("category", definition.category());
        if (definition.packageType() != null) {
            root.addProperty("package", definition.packageType());
        }

        JsonArray ports = new JsonArray();
        for (ComponentDefinition.Port port : definition.ports()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", port.name());
            item.addProperty("direction", port.direction().name());
            item.addProperty("isClock", port.isClock());
            if (port.pin() != null) {
                item.addProperty("pin", port.pin());
            }
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
        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException(
                    "Invalid component definition JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("component definition must be a JSON object");
        }

        JsonObject root = parsed.getAsJsonObject();
        int version = integer(root, "version");
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported component definition version " + version
                            + " (supported version: " + CURRENT_VERSION + ")");
        }
        String name = string(root, "name");
        String description = string(root, "description");
        String category = string(root, "category");
        String primaryOutput = optionalString(root, "primaryOutput");
        String storedHash = string(root, "hash");

        JsonElement portsElement = root.get("ports");
        if (portsElement == null || !portsElement.isJsonArray()) {
            throw new IllegalArgumentException("component definition.ports must be an array");
        }
        JsonArray portArray = portsElement.getAsJsonArray();
        List<ComponentDefinition.Port> ports = new ArrayList<>(portArray.size());
        for (int index = 0; index < portArray.size(); index++) {
            JsonElement element = portArray.get(index);
            String context = "component definition.ports[" + index + "]";
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            String portName = string(item, "name", context);
            String directionText = string(item, "direction", context);
            ComponentDefinition.Direction direction;
            try {
                direction = ComponentDefinition.Direction.valueOf(directionText);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        context + ".direction must be IN or OUT", exception);
            }
            boolean clock = booleanValue(item, "isClock", context);
            Integer pin = optionalInteger(item, "pin", context);
            try {
                ports.add(new ComponentDefinition.Port(portName, direction, clock, pin));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(context + ": " + exception.getMessage(), exception);
            }
        }

        JsonElement circuitElement = root.get("internalCircuit");
        if (circuitElement == null || !circuitElement.isJsonObject()) {
            throw new IllegalArgumentException(
                    "component definition.internalCircuit must be a circuit-file object");
        }
        Schematic internal = SchematicJson.fromJson(
                circuitElement.toString(), gateDefinitions);
        String packageType = optionalString(root, "package");
        ComponentDefinition definition = new ComponentDefinition(
                name, description, category, ports, primaryOutput, packageType, internal);
        if (!definition.hash().equalsIgnoreCase(storedHash)) {
            throw new IllegalArgumentException(
                    "component definition hash mismatch for " + name
                            + ": stored=" + storedHash + ", calculated=" + definition.hash());
        }
        return definition;
    }

    private static int integer(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("component definition." + field + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "component definition." + field + " must be a 32-bit integer", exception);
        }
    }

    private static Integer optionalInteger(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(context + "." + field + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(context + "." + field + " must be a 32-bit integer",
                    exception);
        }
    }

    private static String string(JsonObject object, String field) {
        return string(object, field, "component definition");
    }

    private static String string(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException(context + "." + field + " must be a non-empty string");
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("component definition." + field + " must be a string");
        }
        String text = value.getAsString();
        return text.isBlank() ? null : text;
    }

    private static boolean booleanValue(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(context + "." + field + " must be a boolean");
        }
        return value.getAsBoolean();
    }
}
