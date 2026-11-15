package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void unconnectedGateInputIsUnknownAndEachWireHasItsNetValue() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, -10, Facing.EAST);
        PlacedComponent gate = schematic.add("and", 100, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 180, 0, Facing.EAST);
        connect(schematic, input, "out", gate, "in0");
        connect(schematic, gate, "out", output, "in0");
        schematic.setInputValue(input.id(), true);
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.X, result.outputValues().get(output.id()));
        WireSegment wire = schematic.wires().getLast();
        assertEquals(Value.X, result.valueOf(wire));
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

    private static void addPath(Schematic s, int... xy) {
        for (int i=0; i+3<xy.length; i+=2) s.addWire(xy[i],xy[i+1],xy[i+2],xy[i+3]);
    }
}
