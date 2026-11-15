package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentAttributeTest {
    private static final GateLibrary LIBRARY = loadVariantLibrary();
    private static final List<GateDef> DEFINITIONS = LIBRARY.all();

    @Test
    void defaultsAndAttributeDefinitionsComeFromTheCentralRegistry() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent and = schematic.add("and", 100, 100, Facing.EAST);
        PlacedComponent not = schematic.add("not", 200, 100, Facing.EAST);
        PlacedComponent input = schematic.add("input", 300, 100, Facing.EAST);

        assertTrue(and.attributes().isEmpty());
        assertEquals("narrow", schematic.attributeValue(and.id(), "size"));
        assertEquals("2", schematic.attributeValue(and.id(), "inputs"));
        assertEquals("EAST", schematic.attributeValue(and.id(), "facing"));
        assertEquals(List.of("facing", "size", "inputs"),
                ComponentAttributeRegistry.forFamily("and").stream()
                        .map(ComponentAttributeRegistry.AttributeSpec::key).toList());
        assertEquals(List.of("facing", "size"),
                ComponentAttributeRegistry.forFamily("not").stream()
                        .map(ComponentAttributeRegistry.AttributeSpec::key).toList());
        assertEquals(List.of("facing"),
                ComponentAttributeRegistry.forFamily("input").stream()
                        .map(ComponentAttributeRegistry.AttributeSpec::key).toList());
        assertThrows(IllegalArgumentException.class,
                () -> schematic.attributeValue(not.id(), "inputs"));
        assertThrows(IllegalArgumentException.class,
                () -> schematic.attributeValue(input.id(), "size"));
    }

    @Test
    void sizeAndInputOptionsAreExactlyTheVariantsPresentInTheTestCatalogue() {
        Schematic schematic = new Schematic(DEFINITIONS);

        assertEquals(List.of("narrow", "normal", "wide"),
                schematic.attributeOptions("and", "size"));
        assertEquals(List.of("2", "3", "4"),
                schematic.attributeOptions("and", "inputs"));
        assertEquals(List.of("narrow"), schematic.attributeOptions("not", "size"));
        assertEquals(List.of("EAST", "NORTH", "WEST", "SOUTH"),
                schematic.attributeOptions("and", "facing"));
    }

    @Test
    void unavailableSizeAndInputCombinationIsRejectedWithoutMutation() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 100, Facing.EAST);
        assertTrue(schematic.setAttribute(gate.id(), "size", "wide"));
        long revision = schematic.modificationCount();
        PlacedComponent before = schematic.components().getFirst();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> schematic.setAttribute(gate.id(), "inputs", "4"));

        assertTrue(exception.getMessage().contains("Not available"), exception.getMessage());
        assertEquals(revision, schematic.modificationCount());
        assertEquals(before, schematic.components().getFirst());
        assertEquals("2", schematic.attributeValue(gate.id(), "inputs"));
        assertEquals("wide", schematic.attributeValue(gate.id(), "size"));
    }

    @Test
    void attributeChangesAreOneUndoRedoStepIncludingResolvedVariant() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 100, Facing.EAST);
        SchematicHistory history = new SchematicHistory();
        SchematicSnapshot before = SchematicSnapshot.capture(schematic);

        history.begin(schematic);
        assertTrue(schematic.setAttribute(gate.id(), "size", "wide"));
        assertTrue(schematic.setAttribute(gate.id(), "inputs", "3"));
        assertTrue(history.commit(schematic));
        SchematicSnapshot after = SchematicSnapshot.capture(schematic);

        assertEquals(Map.of("size", "wide", "inputs", "3"),
                schematic.components().getFirst().attributes());
        assertEquals(3, schematic.definitionFor(schematic.components().getFirst()).inputCount());
        assertEquals(1, history.undoDepth());
        assertTrue(history.undo(schematic));
        assertEquals(before, SchematicSnapshot.capture(schematic));
        assertTrue(history.redo(schematic));
        assertEquals(after, SchematicSnapshot.capture(schematic));
        assertEquals(3, schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(gate.id())
                        && pin.pinId().startsWith("in")).count());
    }

    @Test
    void attributesRoundTripAndOldFilesWithoutAttrsRemainCompatible() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 100, Facing.EAST);
        assertTrue(schematic.setAttribute(gate.id(), "size", "wide"));
        assertTrue(schematic.setAttribute(gate.id(), "inputs", "3"));
        assertTrue(schematic.setAttribute(gate.id(), "facing", "north"));

        String json = SchematicJson.toJson(schematic);
        assertTrue(json.contains("\"attrs\""));
        assertTrue(json.contains("\"size\": \"wide\""));
        assertTrue(json.contains("\"inputs\": \"3\""));
        assertTrue(json.contains("\"facing\": \"NORTH\""));

        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(schematic.components(), loaded.components());
        assertEquals("wide", loaded.attributeValue(gate.id(), "size"));
        assertEquals(3, loaded.definitionFor(loaded.components().getFirst()).inputCount());

        Schematic old = SchematicJson.fromJson(
                "{\"version\":1,\"components\":[{\"id\":\"legacy\","
                        + "\"type\":\"and\",\"x\":0,\"y\":0,\"facing\":\"EAST\"}],"
                        + "\"wires\":[]}",
                DEFINITIONS);
        assertTrue(old.components().getFirst().attributes().isEmpty());
        assertEquals("narrow", old.attributeValue("legacy", "size"));
        assertEquals("2", old.attributeValue("legacy", "inputs"));
    }

    @Test
    void threeInputAndVariantCompilesToAThreeInputEngineGate() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent first = schematic.add("input", 70, -20, Facing.EAST);
        PlacedComponent second = schematic.add("input", 70, 0, Facing.EAST);
        PlacedComponent third = schematic.add("input", 70, 20, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 160, 0, Facing.EAST);
        assertTrue(schematic.setAttribute(gate.id(), "inputs", "3"));

        for (PlacedComponent input : List.of(first, second, third)) {
            Connectivity.PinPosition outputPin = pin(schematic, input.id(), "out");
            Connectivity.PinPosition gatePin = pin(schematic, gate.id(),
                    "in" + List.of(first, second, third).indexOf(input));
            assertEquals(outputPin.x(), gatePin.x());
            assertEquals(outputPin.y(), gatePin.y());
        }
        schematic.addWire(100, 0, 160, 0);
        schematic.setInputValue(first.id(), true);
        schematic.setInputValue(second.id(), true);
        schematic.setInputValue(third.id(), true);
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(schematic)
                .outputValues().get(output.id()));

        schematic.setInputValue(third.id(), false);
        assertEquals(Value.ZERO, SchematicCompiler.compileAndSettle(schematic)
                .outputValues().get(output.id()));
    }

    private static Connectivity.PinPosition pin(Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(componentId)
                        && position.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    @Test
    void saveLoaderRejectsUnknownAttributeKeysValuesAndUnavailableCombinations() {
        String componentPrefix = "{\"id\":\"g\",\"type\":\"and\","
                + "\"x\":0,\"y\":0,\"facing\":\"EAST\",\"attrs\":";
        assertInvalid(componentPrefix + "{\"mystery\":\"x\"}}");
        assertInvalid(componentPrefix + "{\"size\":\"gigantic\"}}");
        assertInvalid(componentPrefix + "{\"size\":\"wide\",\"inputs\":\"4\"}}");
        assertInvalid(componentPrefix + "{\"facing\":\"NORTH\"}}");
    }

    private static void assertInvalid(String component) {
        String json = "{\"version\":1,\"components\":[" + component + "],\"wires\":[]}";
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SchematicJson.fromJson(json, DEFINITIONS));
        assertTrue(exception.getMessage().contains("components[0]"), exception.getMessage());
    }

    private static GateLibrary loadVariantLibrary() {
        try (InputStream stream = ComponentAttributeTest.class.getResourceAsStream(
                "/gates-variants.json")) {
            assertNotNull(stream, "test-only gates-variants.json fixture");
            return GateLibrary.fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("could not read gate variant fixture", exception);
        }
    }
}
