package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.GateOperator;
import io.github.evilalee.gatelab.ui.PinDotStyle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChipPowerSimulationTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private enum Supply {
        POWERED, VCC_UNCONNECTED, GND_UNCONNECTED, VCC_LOW, GND_HIGH, VCC_X
    }

    private record Fixture(Schematic schematic, ComponentInstance chip) { }

    @Test
    void powered7400ProducesItsNormalNandOutput() {
        Fixture fixture = nandFixture(Supply.POWERED);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(fixture.schematic());

        assertEquals(Value.ZERO, result.outputValues().get("probe"));
        assertFalse(result.oscillating());
        assertTrue(ChipPowerChecker.check(fixture.schematic(), result, true).isEmpty());
    }

    @Test
    void unconnectedVccForcesEveryChipOutputToX() {
        assertUnpowered(Supply.VCC_UNCONNECTED);
    }

    @Test
    void unconnectedGndForcesEveryChipOutputToX() {
        assertUnpowered(Supply.GND_UNCONNECTED);
    }

    @Test
    void lowVccForcesEveryChipOutputToX() {
        assertUnpowered(Supply.VCC_LOW);
    }

    @Test
    void highGndForcesEveryChipOutputToX() {
        assertUnpowered(Supply.GND_HIGH);
    }

    @Test
    void unknownVccForcesEveryChipOutputToX() {
        assertUnpowered(Supply.VCC_X);
    }

    @Test
    void disablingChipPowerRestoresIdealLogicWithoutSupplyWires() {
        Fixture fixture = nandFixture(Supply.VCC_UNCONNECTED);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(fixture.schematic(), null, false);

        assertEquals(Value.ZERO, result.outputValues().get("probe"));
        assertTrue(ChipPowerChecker.check(fixture.schematic(), result, false).isEmpty());
    }

    @Test
    void pinDotDiagnosticsShowBlueConnectedXAtAnUnpoweredChipOutput() {
        Fixture fixture = nandFixture(Supply.VCC_UNCONNECTED);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(fixture.schematic());
        PinStateHelper.Pin pin = PinStateHelper.analyze(fixture.schematic(), result)
                .requirePin(fixture.chip().id(), "1Y");

        assertEquals(PinStateHelper.State.CONNECTED_X, pin.state());
        assertEquals(Value.X, pin.value());
        assertEquals(Color.BLUE, PinDotStyle.forPin(pin.state(), false, 1.0).color());
    }

    @Test
    void flipFlopChipRetainsItsStateAcrossPowerLossAndRestoration() {
        ComponentDefinition definition = poweredDffDefinition();
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(definition, "U1", 400, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        addInputAt(schematic, "data", pins.get("D"), true);
        addClockAt(schematic, "clock", pins.get("CLK"), false);
        addInputAt(schematic, "vcc", pins.get("VCC"), true);
        addInputAt(schematic, "gnd", pins.get("GND"), false);
        addOutputAt(schematic, "q", pins.get("Q"));

        SchematicCompiler.SimulationResult initial =
                SchematicCompiler.compileAndSettle(schematic);
        schematic.setClockValue("clock", true);
        SchematicCompiler.SimulationResult captured =
                SchematicCompiler.compileAndSettle(schematic, initial);
        assertEquals(Value.ONE, captured.outputValues().get("q"));

        schematic.setInputValue("vcc", false);
        SchematicCompiler.SimulationResult unpowered =
                SchematicCompiler.compileAndSettle(schematic, captured);
        assertEquals(Value.X, unpowered.outputValues().get("q"));

        schematic.setInputValue("vcc", true);
        SchematicCompiler.SimulationResult restored =
                SchematicCompiler.compileAndSettle(schematic, unpowered);
        assertEquals(Value.ONE, restored.outputValues().get("q"));
    }

    @Test
    void flattened7400SrLatchSettlesAndHoldsWithValidPower() {
        ComponentDefinition definition = library().get("7400");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(definition, "U1", 400, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);

        addInputAt(schematic, "set-n", pins.get("1A"), false);
        addInputAt(schematic, "reset-n", pins.get("2A"), true);
        addInputAt(schematic, "vcc", pins.get("VCC"), true);
        addInputAt(schematic, "gnd", pins.get("GND"), false);
        addOutputAt(schematic, "q", pins.get("1Y"));
        addOutputAt(schematic, "qn", pins.get("2Y"));

        ComponentInstance.PinGeometry q = pins.get("1Y");
        ComponentInstance.PinGeometry qn = pins.get("2Y");
        ComponentInstance.PinGeometry qFeedback = pins.get("2B");
        ComponentInstance.PinGeometry qnFeedback = pins.get("1B");
        connectPath(schematic,
                q.x(), q.y(),
                q.x() + 140, q.y(),
                q.x() + 140, qFeedback.y(),
                qFeedback.x(), qFeedback.y());
        connectPath(schematic,
                qn.x(), qn.y(),
                qn.x() + 180, qn.y(),
                qn.x() + 180, qnFeedback.y(),
                qnFeedback.x(), qnFeedback.y());

        SchematicCompiler.SimulationResult set =
                SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.ONE, set.outputValues().get("q"));
        assertEquals(Value.ZERO, set.outputValues().get("qn"));
        assertFalse(set.oscillating());

        schematic.setInputValue("set-n", true);
        SchematicCompiler.SimulationResult held =
                SchematicCompiler.compileAndSettle(schematic, set);
        assertEquals(Value.ONE, held.outputValues().get("q"));
        assertEquals(Value.ZERO, held.outputValues().get("qn"));
        assertFalse(held.oscillating());
    }

    private static void assertUnpowered(Supply supply) {
        Fixture fixture = nandFixture(supply);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(fixture.schematic());
        assertEquals(Value.X, result.outputValues().get("probe"), supply.name());
        assertEquals("U1 (7400): no power",
                ChipPowerChecker.statusText(fixture.schematic(), result, true));
        assertFalse(result.oscillating());
    }

    private static Fixture nandFixture(Supply supply) {
        ComponentDefinition definition = library().get("7400");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(definition, "U1", 200, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        addInputAt(schematic, "a", pins.get("1A"), true);
        addInputAt(schematic, "b", pins.get("1B"), true);
        addOutputAt(schematic, "probe", pins.get("1Y"));
        switch (supply) {
            case POWERED -> {
                addInputAt(schematic, "vcc", pins.get("VCC"), true);
                addInputAt(schematic, "gnd", pins.get("GND"), false);
            }
            case VCC_UNCONNECTED -> addInputAt(schematic, "gnd", pins.get("GND"), false);
            case GND_UNCONNECTED -> addInputAt(schematic, "vcc", pins.get("VCC"), true);
            case VCC_LOW -> {
                addInputAt(schematic, "vcc", pins.get("VCC"), false);
                addInputAt(schematic, "gnd", pins.get("GND"), false);
            }
            case GND_HIGH -> {
                addInputAt(schematic, "vcc", pins.get("VCC"), true);
                addInputAt(schematic, "gnd", pins.get("GND"), true);
            }
            case VCC_X -> {
                addInputAt(schematic, "vcc-high", pins.get("VCC"), true);
                addInputAt(schematic, "vcc-low", pins.get("VCC"), false);
                addInputAt(schematic, "gnd", pins.get("GND"), false);
            }
        }
        return new Fixture(schematic, chip);
    }

    private static ComponentDefinition poweredDffDefinition() {
        ComponentDefinition base = library().get("DFF");
        Schematic internal = base.internalCircuit();
        int bottom = internal.pinPositions().stream()
                .mapToInt(Connectivity.PinPosition::y).max().orElse(200);
        addInputAt(internal, "supply-vcc", 100, bottom + 60, true, "VCC", "input");
        addInputAt(internal, "supply-gnd", 100, bottom + 100, false, "GND", "input");
        return new ComponentDefinition(
                "DFF-Powered-Test", "D flip-flop with DIP supplies", "Test",
                List.of(
                        new ComponentDefinition.Port("D", ComponentDefinition.Direction.IN, false, 2),
                        new ComponentDefinition.Port("CLK", ComponentDefinition.Direction.IN, true, 3),
                        new ComponentDefinition.Port("GND", ComponentDefinition.Direction.IN, false, 7),
                        new ComponentDefinition.Port("Q", ComponentDefinition.Direction.OUT, false, 5),
                        new ComponentDefinition.Port("QN", ComponentDefinition.Direction.OUT, false, 6),
                        new ComponentDefinition.Port("VCC", ComponentDefinition.Direction.IN, false, 14)),
                "Q", "DIP14", internal);
    }

    private static void addInputAt(
            Schematic schematic, String id, ComponentInstance.PinGeometry pin, boolean high) {
        addInputAt(schematic, id, pin.x(), pin.y(), high, id, "input");
    }

    private static void addInputAt(
            Schematic schematic, String id, int pinX, int pinY, boolean high) {
        addInputAt(schematic, id, pinX, pinY, high, id, "input");
    }

    private static void addInputAt(
            Schematic schematic, String id, int pinX, int pinY, boolean high,
            String label, String type) {
        GateDef definition = gate(type);
        GateGeometry.Offset output = GateGeometry.outputOffset(definition, Facing.EAST);
        schematic.add(new PlacedComponent(
                id, type, pinX - output.x(), pinY - output.y(),
                Facing.EAST, Map.of(), label));
        schematic.setInputValue(id, high);
    }

    private static void addClockAt(
            Schematic schematic, String id, ComponentInstance.PinGeometry pin, boolean high) {
        addInputAt(schematic, id, pin.x(), pin.y(), high, id, "clock");
        schematic.setClockValue(id, high);
    }

    private static void addOutputAt(
            Schematic schematic, String id, ComponentInstance.PinGeometry pin) {
        GateGeometry.Offset input =
                GateGeometry.inputOffsets(gate("output"), Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pin.x() - input.x(), pin.y() - input.y(),
                Facing.EAST, Map.of(), id));
    }

    private static Map<String, ComponentInstance.PinGeometry> pins(
            ComponentInstance instance, ComponentDefinition definition) {
        Map<String, ComponentInstance.PinGeometry> result = new HashMap<>();
        for (ComponentInstance.PinGeometry pin : instance.geometry(definition).pins()) {
            result.put(pin.name(), pin);
        }
        return result;
    }

    private static void connectPath(Schematic schematic, int... coordinates) {
        if (coordinates.length < 4 || coordinates.length % 2 != 0) {
            throw new IllegalArgumentException("path requires at least two points");
        }
        for (int index = 2; index < coordinates.length; index += 2) {
            schematic.addWire(new WireSegment(
                    coordinates[index - 2], coordinates[index - 1],
                    coordinates[index], coordinates[index + 1]));
        }
    }

    private static ComponentLibrary library() {
        try {
            return ComponentLibrary.load(GATES, Path.of(System.getProperty("java.io.tmpdir"),
                    "logicbench-chip-power-" + java.util.UUID.randomUUID()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static GateDef gate(String family) {
        return GATES.stream().filter(definition -> definition.family().equals(family))
                .findFirst().orElseThrow();
    }
}
