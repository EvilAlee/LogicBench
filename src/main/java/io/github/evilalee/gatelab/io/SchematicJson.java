package io.github.evilalee.gatelab.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.WireSegment;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned JSON text format for editable LogicBench schematics. */
public final class SchematicJson {
    public static final int CURRENT_VERSION = 1;
    public static final String FILE_EXTENSION = ".lbc";
    public static final String LEGACY_FILE_EXTENSION = ".logicbench.json";

    public record LoadResult(Schematic schematic, List<String> notices) {
        public LoadResult {
            Objects.requireNonNull(schematic, "schematic");
            notices = List.copyOf(Objects.requireNonNull(notices, "notices"));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SchematicJson() {
    }

    public static String toJson(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_VERSION);
        JsonObject options = new JsonObject();
        options.addProperty("undefinedInputs", schematic.undefinedInputMode().jsonValue());
        root.add("options", options);

        JsonArray components = new JsonArray();
        for (PlacedComponent component : schematic.components()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", component.id());
            item.addProperty("type", component.typeId());
            item.addProperty("x", component.x());
            item.addProperty("y", component.y());
            item.addProperty("facing", component.facing().name());
            if (!component.attributes().isEmpty()) {
                JsonObject attributes = new JsonObject();
                component.attributes().forEach(attributes::addProperty);
                item.add("attrs", attributes);
            }
            if (!component.label().isEmpty()) {
                item.addProperty("label", component.label());
            }
            if ("input".equals(component.typeId())) {
                item.addProperty("high", schematic.inputValue(component.id()));
            } else if ("clock".equals(component.typeId())) {
                item.addProperty("high", schematic.clockValue(component.id()));
            }
            components.add(item);
        }
        root.add("components", components);

        if (!schematic.componentInstances().isEmpty()) {
            JsonArray instances = new JsonArray();
            for (ComponentInstance instance : schematic.componentInstances()) {
                JsonObject item = new JsonObject();
                item.addProperty("definitionName", instance.definitionName());
                item.addProperty("definitionHash", instance.definitionHash());
                item.addProperty("id", instance.id());
                if (!instance.label().isEmpty()) {
                    item.addProperty("label", instance.label());
                }
                item.addProperty("x", instance.x());
                item.addProperty("y", instance.y());
                item.addProperty("rotation", instance.rotation().name());
                instances.add(item);
            }
            root.add("instances", instances);

            JsonArray embedded = new JsonArray();
            schematic.componentDefinitions().values().stream()
                    .sorted(java.util.Comparator.comparing(ComponentDefinition::name))
                    .forEach(definition -> embedded.add(
                            JsonParser.parseString(ComponentDefinitionJson.toJson(definition))));
            root.add("componentDefinitions", embedded);
        }

        JsonArray wires = new JsonArray();
        for (WireSegment wire : schematic.wires()) {
            JsonObject item = new JsonObject();
            item.addProperty("x1", wire.x1());
            item.addProperty("y1", wire.y1());
            item.addProperty("x2", wire.x2());
            item.addProperty("y2", wire.y2());
            wires.add(item);
        }
        root.add("wires", wires);
        return GSON.toJson(root);
    }

    /**
     * Parses and validates into a new schematic. The caller can replace its
     * current document only after this method returns successfully.
     */
    public static Schematic fromJson(String json, Collection<GateDef> definitions) {
        return fromJsonWithNotices(json, definitions, null).schematic();
    }

    /** Loads using the user library as fallback; embedded definitions always win. */
    public static Schematic fromJson(
            String json, Collection<GateDef> definitions, ComponentLibrary library) {
        return fromJsonWithNotices(json, definitions, library).schematic();
    }

    /** Loads a document and returns notices about embedded/library definition conflicts. */
    public static LoadResult fromJsonWithNotices(
            String json, Collection<GateDef> definitions, ComponentLibrary library) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(definitions, "definitions");

        final JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid LogicBench JSON: " + exception.getMessage(), exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("LogicBench document must be a JSON object");
        }

        JsonObject root = parsed.getAsJsonObject();
        int version = integer(root, "version", "document");
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported LogicBench file version " + version
                            + " (supported version: " + CURRENT_VERSION + ")");
        }

        UndefinedInputMode undefinedInputMode = readUndefinedInputMode(root);

        Set<String> knownTypes = new HashSet<>();
        for (GateDef definition : definitions) {
            knownTypes.add(definition.family());
        }
        Schematic schematic = new Schematic(definitions);
        schematic.setUndefinedInputMode(undefinedInputMode);
        List<String> notices = new ArrayList<>();
        Map<String, ComponentDefinition> resolvedDefinitions = new LinkedHashMap<>();
        if (root.has("componentDefinitions")) {
            JsonElement definitionsElement = root.get("componentDefinitions");
            if (definitionsElement == null || !definitionsElement.isJsonArray()) {
                throw new IllegalArgumentException("document.componentDefinitions must be an array");
            }
            JsonArray embeddedDefinitions = definitionsElement.getAsJsonArray();
            for (int index = 0; index < embeddedDefinitions.size(); index++) {
                ComponentDefinition embedded = ComponentDefinitionJson.fromJson(
                        embeddedDefinitions.get(index).toString(), definitions);
                ComponentDefinition previous = resolvedDefinitions.putIfAbsent(
                        embedded.name(), embedded);
                if (previous != null && !previous.hash().equals(embedded.hash())) {
                    throw new IllegalArgumentException(
                            "document.componentDefinitions contains duplicate name with "
                                    + "different hashes: " + embedded.name());
                }
                if (library != null) {
                    ComponentDefinition libraryDefinition = library.find(embedded.name()).orElse(null);
                    if (libraryDefinition != null
                            && !libraryDefinition.hash().equals(embedded.hash())) {
                        notices.add("Embedded component definition '" + embedded.name()
                                + "' (" + embedded.hash().substring(0, 12)
                                + ") overrides library version ("
                                + libraryDefinition.hash().substring(0, 12) + ").");
                    }
                }
            }
        }
        JsonArray componentArray = array(root, "components", "document");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < componentArray.size(); index++) {
            String context = "components[" + index + "]";
            JsonElement element = componentArray.get(index);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            String id = string(item, "id", context);
            String type = string(item, "type", context);
            if (!knownTypes.contains(type)) {
                throw new IllegalArgumentException(context + " has unknown component type: " + type);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate component id: " + id);
            }

            int x = integer(item, "x", context);
            int y = integer(item, "y", context);
            String facingText = string(item, "facing", context);
            Facing facing;
            try {
                facing = Facing.valueOf(facingText);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        context + " has unknown facing: " + facingText, exception);
            }

            boolean high = false;
            if (item.has("high")) {
                if (!"input".equals(type) && !"clock".equals(type)) {
                    throw new IllegalArgumentException(
                            context + ".high is only valid for input or clock components");
                }
                JsonElement highElement = item.get("high");
                if (highElement == null || !highElement.isJsonPrimitive()
                        || !highElement.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException(context + ".high must be a boolean");
                }
                high = highElement.getAsBoolean();
            }

            String label = "";
            if (item.has("label")) {
                JsonElement labelElement = item.get("label");
                if (labelElement == null || !labelElement.isJsonPrimitive()
                        || !labelElement.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(context + ".label must be a string");
                }
                label = labelElement.getAsString();
                // Labels are optional presentation metadata for any placed component.
            }

            Map<String, String> attributes = new LinkedHashMap<>();
            if (item.has("attrs")) {
                JsonElement attrsElement = item.get("attrs");
                if (attrsElement == null || !attrsElement.isJsonObject()) {
                    throw new IllegalArgumentException(context + ".attrs must be an object");
                }
                JsonObject attrsObject = attrsElement.getAsJsonObject();
                for (Map.Entry<String, JsonElement> attribute : attrsObject.entrySet()) {
                    JsonElement attributeValue = attribute.getValue();
                    if (attributeValue == null || !attributeValue.isJsonPrimitive()
                            || !attributeValue.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException(
                                context + ".attrs." + attribute.getKey() + " must be a string");
                    }
                    attributes.put(attribute.getKey(), attributeValue.getAsString());
                }
            }

            try {
                PlacedComponent component = new PlacedComponent(
                        id, type, x, y, facing, attributes, label);
                schematic.add(component);
                if (high) {
                    if ("input".equals(type)) {
                        schematic.setInputValue(id, true);
                    } else if ("clock".equals(type)) {
                        schematic.setClockValue(id, true);
                    }
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(context + ": " + exception.getMessage(), exception);
            }
        }

        if (root.has("instances")) {
            JsonElement instancesElement = root.get("instances");
            if (instancesElement == null || !instancesElement.isJsonArray()) {
                throw new IllegalArgumentException("document.instances must be an array");
            }
            JsonArray instanceArray = instancesElement.getAsJsonArray();
            for (int index = 0; index < instanceArray.size(); index++) {
                String context = "instances[" + index + "]";
                JsonElement element = instanceArray.get(index);
                if (element == null || !element.isJsonObject()) {
                    throw new IllegalArgumentException(context + " must be an object");
                }
                JsonObject item = element.getAsJsonObject();
                String definitionName = string(item, "definitionName", context);
                String definitionHash = string(item, "definitionHash", context);
                String id = string(item, "id", context);
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("Duplicate component id: " + id);
                }
                String label = item.has("label") ? string(item, "label", context) : "";
                int x = integer(item, "x", context);
                int y = integer(item, "y", context);
                String rotationText = string(item, "rotation", context);
                Facing rotation;
                try {
                    rotation = Facing.valueOf(rotationText);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            context + ".rotation has unknown facing: " + rotationText, exception);
                }
                ComponentDefinition definition = resolvedDefinitions.get(definitionName);
                if (definition == null && library != null) {
                    definition = library.find(definitionName).orElse(null);
                    if (definition != null) {
                        resolvedDefinitions.put(definitionName, definition);
                    }
                }
                if (definition == null) {
                    throw new IllegalArgumentException(
                            context + " references missing component definition: " + definitionName);
                }
                if (!definition.hash().equalsIgnoreCase(definitionHash)) {
                    throw new IllegalArgumentException(
                            context + " definition hash mismatch for " + definitionName
                                    + ": instance=" + definitionHash
                                    + ", resolved=" + definition.hash());
                }
                try {
                    schematic.addInstance(new ComponentInstance(
                            definitionName, definitionHash, id, label, x, y, rotation), definition);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(context + ": " + exception.getMessage(), exception);
                }
            }
        }

        JsonArray wireArray = array(root, "wires", "document");
        for (int index = 0; index < wireArray.size(); index++) {
            String context = "wires[" + index + "]";
            JsonElement element = wireArray.get(index);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(context + " must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            int x1 = integer(item, "x1", context);
            int y1 = integer(item, "y1", context);
            int x2 = integer(item, "x2", context);
            int y2 = integer(item, "y2", context);
            try {
                schematic.addWire(new WireSegment(x1, y1, x2, y2));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(context + ": " + exception.getMessage(), exception);
            }
        }
        return new LoadResult(schematic, notices);
    }

    private static UndefinedInputMode readUndefinedInputMode(JsonObject root) {
        if (!root.has("options")) {
            return UndefinedInputMode.IGNORE;
        }
        JsonElement optionsElement = root.get("options");
        if (optionsElement == null || !optionsElement.isJsonObject()) {
            throw new IllegalArgumentException("document.options must be an object");
        }
        JsonObject options = optionsElement.getAsJsonObject();
        if (!options.has("undefinedInputs")) {
            return UndefinedInputMode.IGNORE;
        }
        JsonElement value = options.get("undefinedInputs");
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(
                    "document.options.undefinedInputs must be a string");
        }
        try {
            return UndefinedInputMode.fromJsonValue(value.getAsString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "document.options.undefinedInputs " + exception.getMessage(), exception);
        }
    }

    private static JsonArray array(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(context + "." + field + " must be an array");
        }
        return value.getAsJsonArray();
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

    private static int integer(JsonObject object, String field, String context) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(context + "." + field + " must be an integer");
        }
        try {
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    context + "." + field + " must be a 32-bit integer", exception);
        }
    }
}
