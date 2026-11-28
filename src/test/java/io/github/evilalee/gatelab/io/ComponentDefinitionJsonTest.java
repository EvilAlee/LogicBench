package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDefinitionJsonTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void definitionJsonRoundTripsOrderedPortsAndInternalCircuit() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("input-a", "input", 0, 0,
                Facing.EAST, java.util.Map.of(), "A"));
        internal.add(new PlacedComponent("input-b", "input", 0, 40,
                Facing.EAST, java.util.Map.of(), "B"));
        internal.add(new PlacedComponent("output-s", "output", 200, 0,
                Facing.EAST, java.util.Map.of(), "S"));
        internal.add(new PlacedComponent("output-c", "output", 200, 40,
                Facing.EAST, java.util.Map.of(), "C"));
        internal.add(new PlacedComponent("sum-xor", "xor", 100, 0, Facing.EAST));
        internal.add(new PlacedComponent("carry-and", "and", 100, 40, Facing.EAST));

        ComponentDefinition definition = ComponentDefinition.fromCircuit(
                "HA", "Half adder", "Arithmetic", null, internal);
        String json = ComponentDefinitionJson.toJson(definition);

        assertTrue(json.contains("\"internalCircuit\""));
        assertTrue(json.contains("\"version\": 1"));
        ComponentDefinition loaded = ComponentDefinitionJson.fromJson(json, GATES);

        assertEquals(definition.name(), loaded.name());
        assertEquals(definition.description(), loaded.description());
        assertEquals(definition.category(), loaded.category());
        assertEquals(definition.ports(), loaded.ports());
        assertEquals(definition.hash(), loaded.hash());
        assertEquals(definition.internalCircuit().components(),
                loaded.internalCircuit().components());
        assertEquals(definition.internalCircuit().wires(),
                loaded.internalCircuit().wires());
        assertEquals(json, ComponentDefinitionJson.toJson(loaded));
    }

    @Test
    void definitionJsonRoundTripsPhysicalPortNameContainingSlash() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("select", "input", 0, 0,
                Facing.EAST, java.util.Map.of(), "A/B"));
        internal.add(new PlacedComponent("result", "output", 200, 0,
                Facing.EAST, java.util.Map.of(), "Y"));

        ComponentDefinition definition = ComponentDefinition.fromCircuit(
                "MuxWithSlashPort", "Slash port test", "Test", "Y", internal);
        String json = ComponentDefinitionJson.toJson(definition);
        ComponentDefinition restored = ComponentDefinitionJson.fromJson(json, GATES);

        assertEquals("A/B", restored.ports().getFirst().name());
        assertEquals(definition.ports(), restored.ports());
        assertEquals(definition.hash(), restored.hash());
    }

    @Test
    void clockComponentsBecomeClockInputsAndPortOrderFollowsInternalComponentOrder() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("clk", "clock", 0, 0,
                Facing.EAST, java.util.Map.of(), "CLK"));
        internal.add(new PlacedComponent("data", "input", 0, 40,
                Facing.EAST, java.util.Map.of(), "D"));
        internal.add(new PlacedComponent("q", "output", 200, 0,
                Facing.EAST, java.util.Map.of(), "Q"));

        ComponentDefinition definition = ComponentDefinition.fromCircuit(
                "OneBit", "Clocked sample", "Sequential", "Q", internal);

        assertEquals(List.of(
                new ComponentDefinition.Port("CLK", ComponentDefinition.Direction.IN, true),
                new ComponentDefinition.Port("D", ComponentDefinition.Direction.IN, false),
                new ComponentDefinition.Port("Q", ComponentDefinition.Direction.OUT, false)),
                definition.ports());
        assertEquals("Q", definition.primaryOutput());
    }

    @Test
    void missingNamesAndDuplicatePortNamesAreRejected() {
        Schematic missing = new Schematic(GATES);
        missing.add(new PlacedComponent("unnamed", "input", 0, 0, Facing.EAST));
        IllegalArgumentException missingFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinition.fromCircuit(
                        "Bad", "Bad", "Test", null, missing));
        assertTrue(missingFailure.getMessage().contains("Name"));

        Schematic duplicate = new Schematic(GATES);
        duplicate.add(new PlacedComponent("first", "input", 0, 0,
                Facing.EAST, java.util.Map.of(), "A"));
        duplicate.add(new PlacedComponent("second", "clock", 0, 40,
                Facing.EAST, java.util.Map.of(), "A"));
        IllegalArgumentException duplicateFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinition.fromCircuit(
                        "Bad", "Bad", "Test", null, duplicate));
        assertTrue(duplicateFailure.getMessage().contains("duplicate port name"));
    }

    @Test
    void definitionNameAndPrimaryOutputAreValidated() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent("input-a", "input", 0, 0,
                Facing.EAST, java.util.Map.of(), "A"));
        internal.add(new PlacedComponent("output-y", "output", 200, 0,
                Facing.EAST, java.util.Map.of(), "Y"));
        ComponentDefinition.Port in = new ComponentDefinition.Port(
                "A", ComponentDefinition.Direction.IN, false);
        ComponentDefinition.Port out = new ComponentDefinition.Port(
                "Y", ComponentDefinition.Direction.OUT, false);

        assertThrows(IllegalArgumentException.class, () -> new ComponentDefinition(
                "bad-name", "Bad", "Test", List.of(in, out), "Y", internal));
        assertThrows(IllegalArgumentException.class, () -> new ComponentDefinition(
                "Good", "Good", "Test", List.of(in, out), "A", internal));
    }

    @Test
    void hashDoesNotDependOnLiveInputAndClockLevels() {
        Schematic low = new Schematic(GATES);
        low.add(new PlacedComponent("input-a", "input", 0, 0,
                Facing.EAST, java.util.Map.of(), "A"));
        low.add(new PlacedComponent("clock-c", "clock", 0, 40,
                Facing.EAST, java.util.Map.of(), "CLK"));
        low.add(new PlacedComponent("output-y", "output", 200, 0,
                Facing.EAST, java.util.Map.of(), "Y"));
        Schematic high = low.copy();
        high.setInputValue("input-a", true);
        high.setClockValue("clock-c", true);

        ComponentDefinition a = ComponentDefinition.fromCircuit(
                "StateFree", "Definition", "Test", null, low);
        ComponentDefinition b = ComponentDefinition.fromCircuit(
                "StateFree", "Definition", "Test", null, high);

        assertEquals(a.hash(), b.hash());
    }
}
