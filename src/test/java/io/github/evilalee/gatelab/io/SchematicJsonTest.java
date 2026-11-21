package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Path;

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
        original.add(new PlacedComponent(
                "clock-C", "clock", 200, 0, Facing.EAST, java.util.Map.of(), "CLK"));
        original.add(new PlacedComponent("input-empty", "input", 300, 0, Facing.EAST));
        original.add(new PlacedComponent(
                "gate-and", "and", 300, 0, Facing.EAST, java.util.Map.of(), "stage one"));

        String json = SchematicJson.toJson(original);
        assertTrue(json.contains("\"label\": \"A\""));
        assertTrue(json.contains("\"label\": \"F\""));
        assertTrue(json.contains("\"label\": \"CLK\""));
        assertTrue(json.contains("\"label\": \"stage one\""));
        assertEquals(4, occurrences(json, "\"label\""));

        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(original.components(), loaded.components());
        assertEquals("CLK", loaded.components().get(2).label());
        assertEquals("", loaded.components().get(3).label());
        assertEquals("stage one", loaded.components().getLast().label());
        assertEquals(json, SchematicJson.toJson(loaded),
                "labels remain in the existing version-1 label field");
    }

    @Test
    void legacyFilesWithoutLabelLoadWithEmptyLabels() {
        Schematic loaded = SchematicJson.fromJson(
                "{\"version\":1,\"components\":["
                        + "{\"id\":\"in\",\"type\":\"input\",\"x\":0,\"y\":0,"
                        + "\"facing\":\"EAST\"},"
                        + "{\"id\":\"gate\",\"type\":\"and\",\"x\":100,\"y\":0,"
                        + "\"facing\":\"EAST\"}],\"wires\":[]}", DEFINITIONS);
        assertEquals("", loaded.components().getFirst().label());
        assertEquals("", loaded.components().getLast().label(),
                "version-1 gate entries without labels remain valid");
        assertFalse(SchematicJson.toJson(loaded).contains("\"label\""),
                "legacy components without names remain unnamed when saved");
    }

    @Test
    void gateLabelsAreAcceptedAndNonStringLabelsAreRejected() {
        Schematic loaded = SchematicJson.fromJson(document(component("g", "and", 0, 0, "EAST")
                .replace("}", ",\"label\":\"sum stage\"}")), DEFINITIONS);
        assertEquals("sum stage", loaded.components().getFirst().label());
        assertInvalid("{\"version\":1,\"components\":["
                + "{\"id\":\"g\",\"type\":\"and\",\"x\":0,\"y\":0,"
                + "\"facing\":\"EAST\",\"label\":7}],\"wires\":[]}",
                "label must be a string");
    }

    @Test
    void clockLevelsRoundTripAndSchematicsWithoutClocksKeepTheirExistingJson() {
        Schematic clocked = new Schematic(DEFINITIONS);
        PlacedComponent highClock = clocked.add("clock", 0, 0, Facing.EAST);
        PlacedComponent lowClock = clocked.add("clock", 100, 0, Facing.EAST);
        clocked.setClockValue(highClock.id(), true);

        String clockJson = SchematicJson.toJson(clocked);
        assertTrue(clockJson.contains("\"type\": \"clock\""));
        assertTrue(clockJson.contains("\"high\": true"));
        assertTrue(clockJson.contains("\"high\": false"));
        Schematic loadedClocks = SchematicJson.fromJson(clockJson, DEFINITIONS);
        assertTrue(loadedClocks.clockValue(highClock.id()));
        assertFalse(loadedClocks.clockValue(lowClock.id()));
        assertEquals(clockJson, SchematicJson.toJson(loadedClocks));

        Schematic legacy = new Schematic(DEFINITIONS);
        PlacedComponent input = legacy.add("input", 0, 0, Facing.EAST);
        legacy.add("and", 100, 0, Facing.EAST);
        legacy.setInputValue(input.id(), true);
        String legacyJson = SchematicJson.toJson(legacy);
        assertFalse(legacyJson.contains("\"type\": \"clock\""));

        Schematic reloadedLegacy = SchematicJson.fromJson(legacyJson, DEFINITIONS);
        assertEquals(legacyJson, SchematicJson.toJson(reloadedLegacy));
        assertTrue(reloadedLegacy.components().stream()
                .noneMatch(component -> component.typeId().equals("clock")));
    }

    @Test
    void instancesAndTheirDefinitionsRoundTripInTheNewOptionalSections(@org.junit.jupiter.api.io.TempDir Path userDirectory)
            throws Exception {
        ComponentLibrary library = ComponentLibrary.load(DEFINITIONS, userDirectory);
        ComponentDefinition halfAdder = library.get("HA");
        Schematic original = new Schematic(DEFINITIONS);
        original.addInstance(halfAdder, "sum", 200, 300, Facing.EAST);

        String json = SchematicJson.toJson(original);
        assertTrue(json.contains("\\"instances\\""));
        assertTrue(json.contains("\\"componentDefinitions\\""));
        assertFalse(json.contains("\\"components\\" : ["));

        SchematicJson.LoadResult loaded = SchematicJson.fromJsonWithNotices(
                json, DEFINITIONS, library);
        assertTrue(loaded.notices().isEmpty());
        assertEquals(original.componentInstances(), loaded.schematic().componentInstances());
        assertEquals(halfAdder.hash(),
                loaded.schematic().componentDefinitionFor(
                        loaded.schematic().componentInstances().getFirst()).hash());
        assertEquals(json, SchematicJson.toJson(loaded.schematic()));
    }

    @Test
    void embeddedDefinitionWinsOverDifferentLibraryHashAndRecordsNotice(
            @org.junit.jupiter.api.io.TempDir Path userDirectory) throws Exception {
        ComponentLibrary library = ComponentLibrary.load(DEFINITIONS, userDirectory);
        ComponentDefinition libraryHalfAdder = library.get("HA");
        ComponentDefinition embeddedHalfAdder = new ComponentDefinition(
                libraryHalfAdder.name(), "Document-pinned half adder revision",
                libraryHalfAdder.category(), libraryHalfAdder.ports(),
                libraryHalfAdder.primaryOutput(), libraryHalfAdder.internalCircuit());
        assertNotEquals(libraryHalfAdder.hash(), embeddedHalfAdder.hash());

        Schematic original = new Schematic(DEFINITIONS);
        original.addInstance(embeddedHalfAdder, "U1", 200, 300, Facing.EAST);
        String json = SchematicJson.toJson(original);

        SchematicJson.LoadResult loaded = SchematicJson.fromJsonWithNotices(
                json, DEFINITIONS, library);

        assertEquals(1, loaded.notices().size());
        assertTrue(loaded.notices().getFirst().contains("overrides library version"));
        ComponentInstance instance = loaded.schematic().componentInstances().getFirst();
        assertEquals(embeddedHalfAdder.hash(),
                loaded.schematic().componentDefinitionFor(instance).hash());
        assertEquals(embeddedHalfAdder.description(),
                loaded.schematic().componentDefinitionFor(instance).description());
    }

    @Test
    void documentsWithoutInstancesDoNotEmitComponentDefinitionSections() {
        Schematic schematic = new Schematic(DEFINITIONS);
        schematic.add("and", 100, 100, Facing.EAST);
        String json = SchematicJson.toJson(schematic);
        assertFalse(json.contains("\\"instances\\""));
        assertFalse(json.contains("\\"componentDefinitions\\""));
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
