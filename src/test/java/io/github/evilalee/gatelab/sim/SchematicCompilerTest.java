package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchematicCompilerTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void schematicOwnsInputStateAndNotifiesOnToggleAndReset() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        AtomicInteger changes = new AtomicInteger();
        schematic.addChangeListener(changes::incrementAndGet);
        assertFalse(schematic.inputValue(input.id()));
        assertTrue(schematic.toggleInput(input.id()));
        assertEquals(1, changes.get());
        schematic.setInputValue(input.id(), true);
        assertEquals(1, changes.get());
        assertTrue(schematic.resetInputs());
        assertFalse(schematic.inputValue(input.id()));
        assertEquals(2, changes.get());
        assertFalse(schematic.resetInputs());
        assertThrows(IllegalArgumentException.class, () -> schematic.toggleInput(gate.id()));
    }

    @Test
    void andOrXorAndNotCircuitsCompileAndFollowInputChanges() {
        for (String type : List.of("and", "or", "xor", "not")) {
            Schematic schematic = new Schematic(DEFINITIONS);
            PlacedComponent a = schematic.add("input", 0, -10, Facing.EAST);
            PlacedComponent b = type.equals("not") ? null
                    : schematic.add("input", 0, 10, Facing.EAST);
            PlacedComponent gate = schematic.add(type, 100, 0, Facing.EAST);
            PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
            connect(schematic, a, "out", gate, "in0");
            if (b != null) connect(schematic, b, "out", gate, "in1");
            connect(schematic, gate, "out", output, "in0");

            for (boolean av : new boolean[]{false, true}) {
                for (boolean bv : new boolean[]{false, true}) {
                    schematic.setInputValue(a.id(), av);
                    if (b != null) schematic.setInputValue(b.id(), bv);
                    Value expected = switch (type) {
                        case "and" -> av && bv ? Value.ONE : Value.ZERO;
                        case "or" -> av || bv ? Value.ONE : Value.ZERO;
                        case "xor" -> av ^ bv ? Value.ONE : Value.ZERO;
                        case "not" -> av ? Value.ZERO : Value.ONE;
                        default -> throw new AssertionError(type);
                    };
                    assertEquals(expected,
                            SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()));
                }
            }
        }
    }

    @Test
    void crossCoupledNorLatchCanSetHoldAndResetAcrossFreshRebuilds() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent reset = schematic.add("input", 50, -10, Facing.EAST);
        PlacedComponent set = schematic.add("input", 150, -10, Facing.EAST);
        PlacedComponent qGate = schematic.add("nor", 100, 0, Facing.EAST);
        PlacedComponent qBarGate = schematic.add("nor", 200, 0, Facing.EAST);
        PlacedComponent q = schematic.add("output", 120, 0, Facing.EAST);
        PlacedComponent qBar = schematic.add("output", 220, 0, Facing.EAST);
        connect(schematic, reset, "out", qGate, "in0");
        connect(schematic, set, "out", qBarGate, "in0");
        addPath(schematic, 200, 0, 200, -40, 30, -40, 30, 10, 60, 10);
        addPath(schematic, 100, 0, 100, 40, 180, 40, 180, 10, 160, 10);
        connect(schematic, qGate, "out", q, "in0");
        connect(schematic, qBarGate, "out", qBar, "in0");

        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.X, result.outputValues().get(q.id()));
        assertFalse(result.oscillating());
        schematic.setInputValue(set.id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, result.outputValues().get(q.id()));
        assertEquals(Value.ZERO, result.outputValues().get(qBar.id()));
        schematic.setInputValue(set.id(), false);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ONE, result.outputValues().get(q.id()), "released controls must hold state");
        assertEquals(Value.ZERO, result.outputValues().get(qBar.id()));
        schematic.setInputValue(reset.id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertEquals(Value.ZERO, result.outputValues().get(q.id()));
        assertEquals(Value.ONE, result.outputValues().get(qBar.id()));
        assertFalse(result.oscillating());
    }

    @Test
    void ignoreModePrunesOneBareInputForAllSixGateFamilies() {
        for (String type : List.of("and", "or", "nand", "nor", "xor", "xnor")) {
            for (int drivenIndex = 0; drivenIndex < 2; drivenIndex++) {
                for (boolean high : new boolean[]{false, true}) {
                    Schematic schematic = new Schematic(DEFINITIONS);
                    PlacedComponent gate = schematic.add(type, 100, 0, Facing.EAST);
                    Connectivity.PinPosition gateInput = pin(schematic, gate, "in" + drivenIndex);
                    PlacedComponent input = schematic.add(
                            "input", gateInput.x() - 40, gateInput.y(), Facing.EAST);
                    PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
                    connect(schematic, input, "out", gate, "in" + drivenIndex);
                    connect(schematic, gate, "out", output, "in0");
                    schematic.setInputValue(input.id(), high);

                    assertEquals(singleInputExpected(type, high),
                            SchematicCompiler.compileAndSettle(schematic)
                                    .outputValues().get(output.id()),
                            type + " driven in" + drivenIndex + " high=" + high);
                }
            }
        }
    }

    @Test
    void strictModePreservesLegacyThreeValuedResultsForOneBareInput() {
        for (String type : List.of("and", "or", "nand", "nor", "xor", "xnor")) {
            for (int drivenIndex = 0; drivenIndex < 2; drivenIndex++) {
                for (boolean high : new boolean[]{false, true}) {
                    Schematic schematic = new Schematic(DEFINITIONS);
                    schematic.setUndefinedInputMode(UndefinedInputMode.ERROR);
                    PlacedComponent gate = schematic.add(type, 100, 0, Facing.EAST);
                    Connectivity.PinPosition gateInput = pin(schematic, gate, "in" + drivenIndex);
                    PlacedComponent input = schematic.add(
                            "input", gateInput.x() - 40, gateInput.y(), Facing.EAST);
                    PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
                    connect(schematic, input, "out", gate, "in" + drivenIndex);
                    connect(schematic, gate, "out", output, "in0");
                    schematic.setInputValue(input.id(), high);

                    assertEquals(strictTwoInputExpected(type, high),
                            SchematicCompiler.compileAndSettle(schematic)
                                    .outputValues().get(output.id()),
                            type + " strict driven in" + drivenIndex + " high=" + high);
                }
            }
        }
    }

    @Test
    void allBareInputsLeaveTheGateOutputUndrivenInBothModes() {
        for (UndefinedInputMode mode : UndefinedInputMode.values()) {
            Schematic schematic = new Schematic(DEFINITIONS);
            schematic.setUndefinedInputMode(mode);
            PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
            PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
            connect(schematic, gate, "out", output, "in0");

            SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
            assertEquals(Value.X, result.outputValues().get(output.id()), mode.name());
            assertEquals(Value.X, result.gateOutputValues().get(gate.id()), mode.name());
        }
    }

    @Test
    void aWireAttachedToAnUndrivenInputIsNotPrunedInEitherMode() {
        for (UndefinedInputMode mode : UndefinedInputMode.values()) {
            Schematic schematic = new Schematic(DEFINITIONS);
            schematic.setUndefinedInputMode(mode);
            PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
            PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
            Connectivity.PinPosition firstInput = pin(schematic, gate, "in0");
            schematic.addWire(firstInput.x(), firstInput.y(),
                    firstInput.x() - 20, firstInput.y());
            connect(schematic, gate, "out", output, "in0");

            assertEquals(Value.X,
                    SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()),
                    mode.name());
        }
    }

    @Test
    void aPinSharingANetWithAnotherPinWithoutWireIsStillConnected() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        Connectivity.PinPosition gateInput = pin(schematic, gate, "in0");
        PlacedComponent input = schematic.add(
                "input", gateInput.x(), gateInput.y(), Facing.EAST);
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        connect(schematic, gate, "out", output, "in0");
        schematic.setInputValue(input.id(), true);

        assertEquals(Value.ONE,
                SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()));
    }

    @Test
    void threeInputGateWithOneBarePinBehavesAsTwoInputGate() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add(
                "and", 100, 0, Facing.EAST, Map.of("inputs", "3"));
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        Connectivity.PinPosition firstPosition = pin(schematic, gate, "in0");
        Connectivity.PinPosition secondPosition = pin(schematic, gate, "in1");
        PlacedComponent first = schematic.add(
                "input", firstPosition.x() - 40, firstPosition.y(), Facing.EAST);
        PlacedComponent second = schematic.add(
                "input", secondPosition.x() - 40, secondPosition.y(), Facing.EAST);
        connect(schematic, first, "out", gate, "in0");
        connect(schematic, second, "out", gate, "in1");
        connect(schematic, gate, "out", output, "in0");

        schematic.setInputValue(first.id(), true);
        schematic.setInputValue(second.id(), true);
        assertEquals(Value.ONE,
                SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()));

        schematic.setInputValue(second.id(), false);
        assertEquals(Value.ZERO,
                SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()));
    }

    @Test
    void notAndBufferKeepTheirUnconnectedInputAsUnknownInBothModes() {
        for (String type : List.of("not", "buffer")) {
            for (UndefinedInputMode mode : UndefinedInputMode.values()) {
                Schematic schematic = new Schematic(DEFINITIONS);
                schematic.setUndefinedInputMode(mode);
                PlacedComponent gate = schematic.add(type, 100, 0, Facing.EAST);
                PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
                connect(schematic, gate, "out", output, "in0");
                assertEquals(Value.X,
                        SchematicCompiler.compileAndSettle(schematic).outputValues().get(output.id()),
                        type + " " + mode);
            }
        }
    }

    @Test
    void bareInputsAreIgnoredAndEachWireHasItsNetValue() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        Connectivity.PinPosition gateInput = pin(schematic, gate, "in0");
        PlacedComponent input = schematic.add(
                "input", gateInput.x() - 40, gateInput.y(), Facing.EAST);
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        connect(schematic, input, "out", gate, "in0");
        connect(schematic, gate, "out", output, "in0");
        schematic.setInputValue(input.id(), true);

        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.ONE, result.outputValues().get(output.id()));
        WireSegment wire = schematic.wires().getLast();
        assertEquals(Value.ONE, result.valueOf(wire));
        Connectivity.Net net = result.topology().nets().stream()
                .filter(n -> n.segments().contains(wire)).findFirst().orElseThrow();
        assertEquals(result.netValues().get(net), result.valueOf(wire));
    }

    @Test
    void gatedRingOscillatorIsReportedWhenEnabled() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent control = schematic.add("input", 50, 10, Facing.EAST);
        PlacedComponent nand = schematic.add("nand", 100, 0, Facing.EAST);
        PlacedComponent firstNot = schematic.add("not", 200, 0, Facing.EAST);
        PlacedComponent secondNot = schematic.add("not", 300, 0, Facing.EAST);
        connect(schematic, control, "out", nand, "in1");
        connect(schematic, nand, "out", firstNot, "in0");
        connect(schematic, firstNot, "out", secondNot, "in0");
        addPath(schematic, 300, 0, 300, -40, 50, -40, 50, -10, 60, -10);
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertFalse(result.oscillating());
        schematic.toggleInput(control.id());
        result = SchematicCompiler.compileAndSettle(schematic, result);
        assertTrue(result.oscillating());
    }

    private static void connect(Schematic schematic, PlacedComponent a, String aPin,
            PlacedComponent b, String bPin) {
        Connectivity.PinPosition pa = pin(schematic, a, aPin);
        Connectivity.PinPosition pb = pin(schematic, b, bPin);
        if (pa.x() == pb.x() || pa.y() == pb.y()) {
            schematic.addWire(pa.x(), pa.y(), pb.x(), pb.y());
        } else {
            schematic.addWire(pa.x(), pa.y(), pb.x(), pa.y());
            schematic.addWire(pb.x(), pa.y(), pb.x(), pb.y());
        }
    }

    private static Connectivity.PinPosition pin(Schematic schematic, PlacedComponent c, String id) {
        return schematic.pinPositions().stream()
                .filter(p -> p.componentId().equals(c.id()) && p.pinId().equals(id))
                .findFirst().orElseThrow();
    }

    private static Value singleInputExpected(String type, boolean high) {
        boolean result = switch (type) {
            case "nand", "nor", "xnor" -> !high;
            default -> high;
        };
        return result ? Value.ONE : Value.ZERO;
    }

    private static Value strictTwoInputExpected(String type, boolean high) {
        return switch (type) {
            case "and" -> high ? Value.X : Value.ZERO;
            case "or" -> high ? Value.ONE : Value.X;
            case "nand" -> high ? Value.X : Value.ONE;
            case "nor" -> high ? Value.ZERO : Value.X;
            case "xor", "xnor" -> Value.X;
            default -> throw new AssertionError(type);
        };
    }

    private static void addPath(Schematic s, int... xy) {
        for (int i=0; i+3<xy.length; i+=2) s.addWire(xy[i],xy[i+1],xy[i+2],xy[i+3]);
    }
}
