package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentDefinitionBuilderTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void createsPortsInSelectedOrderExcludesUnnamedByDefaultAndKeepsSourceUnchanged() {
        Schematic source = new Schematic(GATES);
        source.add(new PlacedComponent(
                "input-data", "input", 100, 100, Facing.EAST, Map.of(), "A"));
        source.add(new PlacedComponent(
                "clock", "clock", 100, 200, Facing.EAST, Map.of(), "CLK"));
        source.add(new PlacedComponent(
                "unnamed-input", "input", 100, 300, Facing.EAST));
        source.add(new PlacedComponent(
                "output-result", "output", 400, 100, Facing.EAST, Map.of(), "Y"));

        ComponentDefinition definition = ComponentDefinitionBuilder.create(
                "OrderedUnit", "Ordered test", "Test",
                List.of(
                        new ComponentDefinitionBuilder.PortSelection(
                                "output-result", true, "RESULT"),
                        new ComponentDefinitionBuilder.PortSelection(
                                "clock", true, "TICK"),
                        new ComponentDefinitionBuilder.PortSelection(
                                "input-data", true, "DATA"),
                        new ComponentDefinitionBuilder.PortSelection(
                                "unnamed-input", false, "")),
                source);

        assertEquals(List.of(
                new ComponentDefinition.Port(
                        "RESULT", ComponentDefinition.Direction.OUT, false),
                new ComponentDefinition.Port(
                        "TICK", ComponentDefinition.Direction.IN, true),
                new ComponentDefinition.Port(
                        "DATA", ComponentDefinition.Direction.IN, false)),
                definition.ports());
        assertEquals("RESULT", definition.primaryOutput());
        assertFalse(definition.internalCircuit().components().stream()
                .anyMatch(component -> component.id().equals("unnamed-input")));
        assertEquals("A", source.components().stream()
                .filter(component -> component.id().equals("input-data"))
                .findFirst().orElseThrow().label());
        assertEquals("", source.components().stream()
                .filter(component -> component.id().equals("unnamed-input"))
                .findFirst().orElseThrow().label());
    }

    @Test
    void validatesDefinitionAndPortIdentifiersAndDuplicateNames() {
        Schematic source = simplePortCircuit();
        List<ComponentDefinitionBuilder.PortSelection> valid = List.of(
                new ComponentDefinitionBuilder.PortSelection("input", true, "A"),
                new ComponentDefinitionBuilder.PortSelection("output", true, "Y"));

        IllegalArgumentException invalidDefinitionName = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "bad-name", "Invalid", "Test", valid, source));
        assertTrue(invalidDefinitionName.getMessage().contains("definition name"));

        IllegalArgumentException duplicatePort = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "DuplicatePorts", "Invalid", "Test",
                        List.of(
                                new ComponentDefinitionBuilder.PortSelection(
                                        "input", true, "D"),
                                new ComponentDefinitionBuilder.PortSelection(
                                        "output", true, "D")),
                        source));
        assertTrue(duplicatePort.getMessage().contains("duplicate port name"));

        IllegalArgumentException invalidPortName = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "InvalidPort", "Invalid", "Test",
                        List.of(
                                new ComponentDefinitionBuilder.PortSelection(
                                        "input", true, "data in"),
                                new ComponentDefinitionBuilder.PortSelection(
                                        "output", true, "Y")),
                        source));
        assertTrue(invalidPortName.getMessage().contains("port name"));
    }

    @Test
    void refusesSchematicWithoutPortsOrWithoutAnIncludedInputAndOutput() {
        Schematic emptyPorts = new Schematic(GATES);
        emptyPorts.add("and", 200, 200, Facing.EAST);
        IllegalArgumentException noPorts = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "NoPorts", "Invalid", "Test", List.of(), emptyPorts));
        assertTrue(noPorts.getMessage().contains("at least one Input"));

        Schematic source = simplePortCircuit();
        IllegalArgumentException noInput = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "NoInput", "Invalid", "Test",
                        List.of(
                                new ComponentDefinitionBuilder.PortSelection(
                                        "input", false, ""),
                                new ComponentDefinitionBuilder.PortSelection(
                                        "output", true, "Y")),
                        source));
        assertTrue(noInput.getMessage().contains("at least one input"));

        IllegalArgumentException noOutput = assertThrows(
                IllegalArgumentException.class,
                () -> ComponentDefinitionBuilder.create(
                        "NoOutput", "Invalid", "Test",
                        List.of(
                                new ComponentDefinitionBuilder.PortSelection(
                                        "input", true, "A"),
                                new ComponentDefinitionBuilder.PortSelection(
                                        "output", false, "")),
                        source));
        assertTrue(noOutput.getMessage().contains("at least one output"));
    }

    @Test
    void savedUserComponentFlattensSimulatesAndSurvivesDocumentRoundTrip(
            @TempDir Path userDirectory) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition definition = passThroughDefinition();
        library.save(definition);

        ComponentLibrary reloadedLibrary = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition reloadedDefinition = reloadedLibrary.get("UserPass");
        assertEquals(definition.hash(), reloadedDefinition.hash());

        Schematic document = new Schematic(GATES);
        ComponentInstance instance = document.addInstance(
                reloadedDefinition, "U1", 500, 200, Facing.EAST);
        ComponentInstance.PinGeometry inputPin = instance.geometry(reloadedDefinition)
                .pins().stream().filter(pin -> pin.name().equals("A"))
                .findFirst().orElseThrow();
        ComponentInstance.PinGeometry outputPin = instance.geometry(reloadedDefinition)
                .pins().stream().filter(pin -> pin.name().equals("Y"))
                .findFirst().orElseThrow();
        addInputAt(document, "source", inputPin.x(), inputPin.y());
        addOutputAt(document, "probe", outputPin.x(), outputPin.y());

        document.setInputValue("source", true);
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(document)
                .outputValues().get("probe"));

        String json = SchematicJson.toJson(document);
        assertTrue(json.contains("\"instances\""));
        assertTrue(json.contains("\"componentDefinitions\""));
        SchematicJson.LoadResult loaded = SchematicJson.fromJsonWithNotices(
                json, GATES, reloadedLibrary);
        assertTrue(loaded.notices().isEmpty());
        assertEquals(document.componentInstances(), loaded.schematic().componentInstances());
        ComponentInstance restored = loaded.schematic().componentInstances().getFirst();
        assertEquals(reloadedDefinition.hash(),
                loaded.schematic().componentDefinitionFor(restored).hash());
        loaded.schematic().setInputValue("source", true);
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(loaded.schematic())
                .outputValues().get("probe"));
    }

    private static Schematic simplePortCircuit() {
        Schematic source = new Schematic(GATES);
        source.add(new PlacedComponent(
                "input", "input", 100, 100, Facing.EAST, Map.of(), "A"));
        source.add(new PlacedComponent(
                "output", "output", 400, 100, Facing.EAST, Map.of(), "Y"));
        return source;
    }

    private static ComponentDefinition passThroughDefinition() {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent(
                "input-A", "input", 100, 100, Facing.EAST, Map.of(), "A"));
        Connectivity.PinPosition inputPin = pin(internal, "input-A", "out");
        GateDef outputGate = gate("output");
        GateGeometry.Offset outputOffset =
                GateGeometry.inputOffsets(outputGate, Facing.EAST).getFirst();
        internal.add(new PlacedComponent(
                "output-Y", "output",
                400 - outputOffset.x(), inputPin.y() - outputOffset.y(),
                Facing.EAST, Map.of(), "Y"));
        Connectivity.PinPosition outputPin = pin(internal, "output-Y", "in0");
        internal.addWire(new WireSegment(
                inputPin.x(), inputPin.y(), outputPin.x(), outputPin.y()));
        return ComponentDefinitionBuilder.create(
                "UserPass", "User pass-through", "Test",
                List.of(
                        new ComponentDefinitionBuilder.PortSelection(
                                "input-A", true, "A"),
                        new ComponentDefinitionBuilder.PortSelection(
                                "output-Y", true, "Y")),
                internal);
    }

    private static void addInputAt(Schematic schematic, String id, int pinX, int pinY) {
        GateGeometry.Offset offset = GateGeometry.outputOffset(gate("input"), Facing.EAST);
        schematic.add(new PlacedComponent(
                id, "input", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), "SOURCE"));
    }

    private static void addOutputAt(Schematic schematic, String id, int pinX, int pinY) {
        GateGeometry.Offset offset =
                GateGeometry.inputOffsets(gate("output"), Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), "RESULT"));
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(candidate -> candidate.componentId().equals(componentId)
                        && candidate.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static GateDef gate(String family) {
        return GATES.stream().filter(definition -> definition.family().equals(family))
                .findFirst().orElseThrow();
    }
}
