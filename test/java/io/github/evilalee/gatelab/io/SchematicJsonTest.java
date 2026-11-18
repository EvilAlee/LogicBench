package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchematicJsonTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void roundTripPreservesEveryComponentTypeFacingWireAndInputState() {
        Schematic original = new Schematic(DEFINITIONS);
        int x = 100;
        for (GateDef definition : DEFINITIONS) {
            for (Facing facing : Facing.values()) {
                PlacedComponent placed = original.add(
                        definition.id(), x, 100 + facing.ordinal() * 60, facing);
                if ("input".equals(definition.id()) && facing.ordinal() % 2 == 1) {
                    original.setInputValue(placed.id(), true);
                }
                x += 100;
            }
        }
        original.addWire(new WireSegment(0, 0, 40, 0));
        original.addWire(new WireSegment(40, 0, 40, 30));

        String json = SchematicJson.toJson(original);
        assertFalse(json.contains("netValues"));
        assertFalse(json.contains("simulationResult"));
        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);

        assertEquals(original.components(), loaded.components());
        assertEquals(original.wires(), loaded.wires());
        for (PlacedComponent component : original.components()) {
            if ("input".equals(component.typeId())) {
                assertEquals(original.inputValue(component.id()), loaded.inputValue(component.id()));
            }
        }
    }

    @Test
    void optionalIoLabelsRoundTripAndAreOmittedWhenEmpty() {
        Schematic original = new Schematic(DEFINITIONS);
        original.add(new PlacedComponent(
                "input-A", "input", 0, 0, Facing.EAST, java.util.Map.of(), "A"));
        original.add(new PlacedComponent(
                "output-F", "output", 100, 0, Facing.EAST, java.util.Map.of(), "F"));
        original.add(new PlacedComponent("input-empty", "input", 200, 0, Facing.EAST));

        String json = SchematicJson.toJson(original);
        assertTrue(json.contains("\"label\": \"A\""));
        assertTrue(json.contains("\"label\": \"F\""));
        assertEquals(2, occurrences(json, "\"label\""));

        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(original.components(), loaded.components());
        assertEquals("", loaded.components().getLast().label());
    }

    @Test
    void legacyFilesWithoutLabelLoadWithEmptyLabels() {
        Schematic loaded = SchematicJson.fromJson(
                "{\"version\":1,\"components\":["
                        + "{\"id\":\"in\",\"type\":\"input\",\"x\":0,\"y\":0,"
                        + "\"facing\":\"EAST\"}],\"wires\":[]}", DEFINITIONS);
        assertEquals("", loaded.components().getFirst().label());
    }

    @Test
    void rejectsLabelsOnNonIoComponentsAndNonStringLabels() {
        assertInvalid(document(component("g", "and", 0, 0, "EAST")
                .replace("}", ",\"label\":\"not allowed\"}")),
                "only valid for input and output");
        assertInvalid("{\"version\":1,\"components\":["
                + "{\"id\":\"in\",\"type\":\"input\",\"x\":0,\"y\":0,"
                + "\"facing\":\"EAST\",\"label\":7}],\"wires\":[]}",
                "label must be a string");
    }

    @Test
    void undefinedInputModeRoundTripsAndLegacyDocumentsDefaultToIgnore() {
        Schematic original = new Schematic(DEFINITIONS);
        original.setUndefinedInputMode(UndefinedInputMode.ERROR);
        String json = SchematicJson.toJson(original);
        assertTrue(json.contains("\"undefinedInputs\": \"error\""));
        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(UndefinedInputMode.ERROR, loaded.undefinedInputMode());

        Schematic legacy = SchematicJson.fromJson(
                "{\"version\":1,\"components\":[],\"wires\":[]}", DEFINITIONS);
        assertEquals(UndefinedInputMode.IGNORE, legacy.undefinedInputMode());
    }

    @Test
    void rejectsUnknownUndefinedInputMode() {
        assertInvalid("{\"version\":1,\"options\":{\"undefinedInputs\":\"x\"},"
                + "\"components\":[],\"wires\":[]}", "ignore' or 'error");
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertInvalid("{\"version\":2,\"components\":[],\"wires\":[]}", "version");
    }

    @Test
    void rejectsUnknownComponentType() {
        assertInvalid(document(component("a", "mystery", 0, 0, "EAST")), "unknown component type");
    }

    @Test
    void rejectsOffGridComponentCoordinates() {
        assertInvalid(document(component("a", "and", 11, 0, "EAST")), "multiple of 10");
    }

    @Test
    void rejectsDuplicateComponentIds() {
        String item = component("same", "input", 0, 0, "EAST");
        assertInvalid("{\"version\":1,\"components\":["+item+","+item+"],\"wires\":[]}",
                "Duplicate component id");
    }

    @Test
    void rejectsNonOrthogonalWire() {
        assertInvalid("{\"version\":1,\"components\":[],\"wires\":["
                +"{\"x1\":0,\"y1\":0,\"x2\":10,\"y2\":10}]}", "axis-aligned");
    }

    @Test
    void rejectsZeroLengthWire() {
        assertInvalid("{\"version\":1,\"components\":[],\"wires\":["
                +"{\"x1\":0,\"y1\":0,\"x2\":0,\"y2\":0}]}", "non-zero");
    }

    @Test
    void rejectsOffGridWireCoordinates() {
        assertInvalid("{\"version\":1,\"components\":[],\"wires\":["
                +"{\"x1\":0,\"y1\":0,\"x2\":11,\"y2\":0}]}", "multiple of 10");
    }

    @Test
    void rejectsHighFlagOnNonInputComponent() {
        assertInvalid("{\"version\":1,\"components\":["
                +"{\"id\":\"o\",\"type\":\"output\",\"x\":0,\"y\":0,"
                +"\"facing\":\"EAST\",\"high\":true}],\"wires\":[]}", "only valid for input");
    }

    @Test
    void generatedIdsDoNotCollideWithIdsLoadedFromJson() {
        String loadedId = "component-1";
        Schematic loaded = SchematicJson.fromJson(document(
                component(loadedId, "input", 0, 0, "EAST")), DEFINITIONS);
        PlacedComponent placed = loaded.add("output", 100, 0, Facing.EAST);
        assertNotEquals(loadedId, placed.id());
    }

    private static void assertInvalid(String json, String messagePart) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> SchematicJson.fromJson(json, DEFINITIONS));
        assertTrue(exception.getMessage().toLowerCase().contains(messagePart.toLowerCase()),
                exception.getMessage());
    }

    private static String document(String component) {
        return "{\"version\":1,\"components\":["+component+"],\"wires\":[]}";
    }

    private static String component(String id, String type, int x, int y, String facing) {
        return "{\"id\":\""+id+"\",\"type\":\""+type+"\",\"x\":"+x
                +",\"y\":"+y+",\"facing\":\""+facing+"\"}";
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
