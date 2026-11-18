package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TidyCircuitServiceTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void tidiesScrambledExpressionSchematicAndPreservesLabelsAndInputValues() {
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A & B");
        SchematicLayout.LayoutResult generated =
                new SchematicLayout(DEFINITIONS).layout(compilation.netlist());
        Schematic schematic = generated.schematic();
        String inputA = generated.inputComponentIds().get("A");
        schematic.setInputValue(inputA, true);
        PlacedComponent gate = schematic.components().stream()
                .filter(component -> component.typeId().equals("and"))
                .findFirst().orElseThrow();
        schematic.move(gate.id(), gate.x() + 100, gate.y() + 80);

        List<PlacedComponent> before = schematic.components();
        TidyCircuitService.TidyResult first =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        TidyCircuitService.TidyResult second =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertFalse(first.equivalenceSkipped());
        assertEquals(4, first.verificationRows());
        assertEquals(first.schematic().components(), second.schematic().components());
        assertEquals(first.schematic().wires(), second.schematic().wires());
        assertNotEquals(before.stream().map(c -> List.of(c.x(), c.y())).toList(),
                first.schematic().components().stream().map(c -> List.of(c.x(), c.y())).toList());

        Map<String, String> newInputIds = first.schematic().components().stream()
                .filter(component -> component.typeId().equals("input"))
                .collect(Collectors.toMap(PlacedComponent::label, PlacedComponent::id));
        assertEquals(Set.of("A", "B"), newInputIds.keySet());
        assertTrue(first.schematic().inputValue(newInputIds.get("A")));
        assertEquals("F", first.schematic().components().stream()
                .filter(component -> component.typeId().equals("output"))
                .map(PlacedComponent::label).findFirst().orElseThrow());
        assertTrue(first.schematic().components().stream()
                .filter(component -> !component.typeId().equals("input")
                        && !component.typeId().equals("output"))
                .allMatch(component -> component.facing() == Facing.EAST));

        TidyCircuitService.TidyResult repeated =
                TidyCircuitService.tidy(first.schematic(), DEFINITIONS);
        assertEquals(first.schematic().components(), repeated.schematic().components());
        assertEquals(first.schematic().wires(), repeated.schematic().wires());
    }

    @Test
    void handBuiltCrossingCircuitRemainsEquivalentAndKeepsCrossingNetsSeparate() {
        Schematic schematic = simpleCircuit(Facing.NORTH);
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.Net inputNet = topology.nets().stream()
                .filter(net -> net.pins().stream().anyMatch(pin ->
                        pin.componentId().equals("input-a") && pin.pinId().equals("out")))
                .findFirst().orElseThrow();
        assertTrue(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals("gate-and") && pin.pinId().equals("in0")));
        assertFalse(inputNet.pins().stream().anyMatch(pin ->
                pin.componentId().equals("gate-and") && pin.pinId().equals("out")));

        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertFalse(result.equivalenceSkipped());
        assertEquals(4, result.verificationRows());
        assertTrue(result.schematic().components().stream()
                .filter(component -> component.typeId().equals("and"))
                .allMatch(component -> component.facing() == Facing.EAST));
    }

    @Test
    void skipsExhaustiveEquivalenceForMoreThanTwelveInputsButStillTidies() {
        Schematic schematic = new Schematic(DEFINITIONS);
        List<PlacedComponent> inputs = new ArrayList<>();
        for (int index = 0; index < 13; index++) {
            PlacedComponent input = new PlacedComponent(
                    "input-" + index, "input", 0, index * 40,
                    Facing.EAST, Map.of(), "A" + index);
            schematic.add(input);
            inputs.add(input);
        }
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, Facing.EAST);
        PlacedComponent output = new PlacedComponent(
                "output-f", "output", 500, 100, Facing.EAST, Map.of(), "F");
        schematic.add(gate);
        schematic.add(output);
        connect(schematic, inputs.get(0).id(), "out", gate.id(), "in0");
        connect(schematic, inputs.get(1).id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");

        TidyCircuitService.TidyResult result = TidyCircuitService.tidy(schematic, DEFINITIONS);
        assertTrue(result.equivalenceSkipped());
        assertEquals(13, result.inputCount());
        assertEquals(0, result.verificationRows());
        assertTrue(result.verificationMessage().contains("skipped"));
    }

    @Test
    void everyRefusalLeavesTheOriginalSchematicUntouched() {
        Schematic bare = new Schematic(DEFINITIONS);
        PlacedComponent bareInput = add(bare, "input-a", "input", 0, 0, "A");
        PlacedComponent bareGate = add(bare, "gate-and", "and", 300, 100, "");
        PlacedComponent bareOutput = add(bare, "output-f", "output", 500, 100, "F");
        connect(bare, bareInput.id(), "out", bareGate.id(), "in0");
        connect(bare, bareGate.id(), "out", bareOutput.id(), "in0");
        assertRefusal(bare, DEFINITIONS, "bare (unconnected)");

        Schematic undriven = new Schematic(DEFINITIONS);
        PlacedComponent source = add(undriven, "input-a", "input", 0, 0, "A");
        PlacedComponent firstGate = add(undriven, "gate-and", "and", 300, 100, "");
        PlacedComponent secondGate = add(undriven, "gate-or", "or", 500, 200, "");
        PlacedComponent drivenOutput = add(undriven, "output-f", "output", 700, 100, "F");
        connect(undriven, source.id(), "out", firstGate.id(), "in0");
        connect(undriven, firstGate.id(), "in1", secondGate.id(), "in0");
        connect(undriven, firstGate.id(), "out", drivenOutput.id(), "in0");
        assertRefusal(undriven, DEFINITIONS, "net with no driver");

        Schematic multipleDrivers = new Schematic(DEFINITIONS);
        PlacedComponent driverA = add(multipleDrivers, "input-a", "input", 0, 0, "A");
        add(multipleDrivers, "input-b", "input", 0, 0, "B");
        PlacedComponent andGate = add(multipleDrivers, "gate-and", "and", 300, 100, "");
        PlacedComponent andOutput = add(multipleDrivers, "output-f", "output", 500, 100, "F");
        connect(multipleDrivers, driverA.id(), "out", andGate.id(), "in0");
        connect(multipleDrivers, andGate.id(), "out", andOutput.id(), "in0");
        assertRefusal(multipleDrivers, DEFINITIONS, "more than one driver");

        Schematic noOutputDriver = new Schematic(DEFINITIONS);
        PlacedComponent input1 = add(noOutputDriver, "input-a", "input", 0, 0, "A");
        PlacedComponent input2 = add(noOutputDriver, "input-b", "input", 0, 200, "B");
        PlacedComponent noOutputGate = add(noOutputDriver, "gate-and", "and", 300, 100, "");
        add(noOutputDriver, "output-f", "output", 500, 500, "F");
        connect(noOutputDriver, input1.id(), "out", noOutputGate.id(), "in0");
        connect(noOutputDriver, input2.id(), "out", noOutputGate.id(), "in1");
        assertRefusal(noOutputDriver, DEFINITIONS, "bare (unconnected)");

        Schematic feedback = feedbackCircuit();
        assertRefusal(feedback, DEFINITIONS, "combinational cycle");

        List<GateDef> unsupportedDefinitions = new ArrayList<>(DEFINITIONS);
        unsupportedDefinitions.add(new GateDef(
                "custom", "svg/custom.svg", 20, 20,
                List.of(new GateGeometry.Offset(0, 10)),
                new GateGeometry.Offset(20, 10)));
        Schematic unsupported = new Schematic(unsupportedDefinitions);
        unsupported.add("custom", 100, 100, Facing.EAST);
        assertRefusal(unsupported, unsupportedDefinitions, "Unsupported component");

        List<GateDef> threeInputDefinitions = new ArrayList<>(DEFINITIONS);
        threeInputDefinitions.add(new GateDef(
                "and-normal-3", "svg/and.svg", 30, 60,
                List.of(new GateGeometry.Offset(0, 10),
                        new GateGeometry.Offset(0, 30),
                        new GateGeometry.Offset(0, 50)),
                new GateGeometry.Offset(30, 30), "and", "normal", 3));
        Schematic unsupportedArity = new Schematic(threeInputDefinitions);
        unsupportedArity.add(
                "and", 100, 100, Facing.EAST,
                Map.of("size", "normal", "inputs", "3"));
        assertRefusal(unsupportedArity, threeInputDefinitions, "Unsupported input count");
    }

    private static Schematic simpleCircuit(Facing facing) {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent inputA = add(schematic, "input-a", "input", 0, 0, "A");
        PlacedComponent inputB = add(schematic, "input-b", "input", 0, 200, "B");
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, facing);
        schematic.add(gate);
        PlacedComponent output = add(schematic, "output-f", "output", 500, 100, "F");
        connect(schematic, inputA.id(), "out", gate.id(), "in0");
        connect(schematic, inputB.id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");
        schematic.setInputValue(inputB.id(), true);
        return schematic;
    }

    private static Schematic feedbackCircuit() {
        Schematic schematic = new Schematic(DEFINITIONS);
        add(schematic, "not-a", "not", 100, 100, "");
        add(schematic, "not-b", "not", 400, 200, "");
        add(schematic, "output-f", "output", 700, 200, "F");
        schematic.addWire(100, 100, 350, 100);
        schematic.addWire(350, 100, 350, 200);
        schematic.addWire(350, 200, 370, 200);
        schematic.addWire(400, 200, 600, 200);
        schematic.addWire(600, 200, 600, 0);
        schematic.addWire(600, 0, 70, 0);
        schematic.addWire(70, 0, 70, 100);
        schematic.addWire(400, 200, 700, 200);
        return schematic;
    }

    private static PlacedComponent add(
            Schematic schematic, String id, String type, int x, int y, String label) {
        PlacedComponent component = new PlacedComponent(
                id, type, x, y, Facing.EAST, Map.of(), label);
        schematic.add(component);
        return component;
    }

    private static void connect(
            Schematic schematic,
            String sourceId,
            String sourcePin,
            String targetId,
            String targetPin) {
        Connectivity.PinPosition source = pin(schematic, sourceId, sourcePin);
        Connectivity.PinPosition target = pin(schematic, targetId, targetPin);
        schematic.addWire(source.x(), source.y(), target.x(), source.y());
        schematic.addWire(target.x(), source.y(), target.x(), target.y());
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(componentId)
                        && pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static void assertRefusal(
            Schematic schematic, List<GateDef> definitions, String message) {
        SchematicSnapshot before = SchematicSnapshot.capture(schematic);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> TidyCircuitService.tidy(schematic, definitions));
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
        assertEquals(before, SchematicSnapshot.capture(schematic));
    }
}
