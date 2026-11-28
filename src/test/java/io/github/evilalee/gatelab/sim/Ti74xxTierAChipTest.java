package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class Ti74xxTierAChipTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    /**
     * Rows transcribed from TI SN74LS42 datasheet SDLS109, page 4:
     * https://www.ti.com/lit/gpn/SN74LS42
     *
     * Input is the D-C-B-A nibble. For codes 0..9 that decimal output is LOW;
     * for invalid codes 10..15 every decimal output is HIGH.
     */
    private record BcdRow(int input, int lowOutput) {
    }

    private record DecoderRow(
            int g1, int g2Star, int selector, int lowOutput) {
    }

    private static final List<BcdRow> SN7442_FUNCTION_TABLE = List.of(
            new BcdRow(0b0000, 0),
            new BcdRow(0b0001, 1),
            new BcdRow(0b0010, 2),
            new BcdRow(0b0011, 3),
            new BcdRow(0b0100, 4),
            new BcdRow(0b0101, 5),
            new BcdRow(0b0110, 6),
            new BcdRow(0b0111, 7),
            new BcdRow(0b1000, 8),
            new BcdRow(0b1001, 9),
            new BcdRow(0b1010, -1),
            new BcdRow(0b1011, -1),
            new BcdRow(0b1100, -1),
            new BcdRow(0b1101, -1),
            new BcdRow(0b1110, -1),
            new BcdRow(0b1111, -1));

    /**
     * Rows transcribed from TI SN74LS138 datasheet SDLS014, page 2:
     * https://www.ti.com/lit/gpn/SN74LS138
     *
     * G2* is TI's shorthand for G2A OR G2B. lowOutput=-1 means all Y outputs HIGH.
     */
    private static final List<DecoderRow> SN74138_FUNCTION_TABLE = List.of(
            new DecoderRow(-1, 1, -1, -1),
            new DecoderRow(0, -1, -1, -1),
            new DecoderRow(1, 0, 0b000, 0),
            new DecoderRow(1, 0, 0b001, 1),
            new DecoderRow(1, 0, 0b010, 2),
            new DecoderRow(1, 0, 0b011, 3),
            new DecoderRow(1, 0, 0b100, 4),
            new DecoderRow(1, 0, 0b101, 5),
            new DecoderRow(1, 0, 0b110, 6),
            new DecoderRow(1, 0, 0b111, 7));

    @Test
    void diagnosticReportsBuiltInChipInitializationFailure() throws IOException {
        try {
            library();
        } catch (RuntimeException exception) {
            System.err.println("TI74XX_LIBRARY_INIT_DIAGNOSTIC: "
                    + exception.getClass().getName() + ": " + exception.getMessage());
            throw exception;
        }
    }

    @Test
    void sn7442PackageAndPinAssignmentsMatchTheTiDip16Table() throws IOException {
        ComponentDefinition definition = library().get("7442");
        assertEquals("DIP16", definition.packageType());

        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("0", 1), Map.entry("1", 2), Map.entry("2", 3),
                Map.entry("3", 4), Map.entry("4", 5), Map.entry("5", 6),
                Map.entry("6", 7), Map.entry("GND", 8), Map.entry("7", 9),
                Map.entry("8", 10), Map.entry("9", 11), Map.entry("D", 12),
                Map.entry("C", 13), Map.entry("B", 14), Map.entry("A", 15),
                Map.entry("VCC", 16));
        Map<String, Integer> actual = new HashMap<>();
        definition.ports().forEach(port -> actual.put(port.name(), port.pin()));
        assertEquals(expected, actual);
        assertEquals(ComponentDefinition.Direction.IN,
                definition.ports().stream().filter(port -> port.name().equals("VCC"))
                        .findFirst().orElseThrow().direction());
        assertEquals(ComponentDefinition.Direction.IN,
                definition.ports().stream().filter(port -> port.name().equals("GND"))
                        .findFirst().orElseThrow().direction());
    }

    @Test
    void sn7442DatasheetFunctionRowsMatchTheFlattenedPoweredPart() throws IOException {
        Schematic schematic = bcdFixture(true);
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());

        Map<String, String> inputIdsByLabel = new HashMap<>();
        Map<String, String> outputIdsByLabel = new HashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("input")) {
                inputIdsByLabel.put(component.label(), component.id());
            } else if (component.typeId().equals("output")) {
                outputIdsByLabel.put(component.label(), component.id());
            }
        }
        InputPinAccess inputA = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("A")));
        InputPinAccess inputB = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("B")));
        InputPinAccess inputC = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("C")));
        InputPinAccess inputD = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("D")));
        InputPinAccess vcc = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("VCC")));
        InputPinAccess gnd = new InputPinAccess(compiled.inputs().get(inputIdsByLabel.get("GND")));
        simulator.setInputValue(vcc.pin(), Value.ONE);
        simulator.setInputValue(gnd.pin(), Value.ZERO);

        for (BcdRow row : SN7442_FUNCTION_TABLE) {
            simulator.setInputValue(inputA.pin(), bit(row.input(), 0));
            simulator.setInputValue(inputB.pin(), bit(row.input(), 1));
            simulator.setInputValue(inputC.pin(), bit(row.input(), 2));
            simulator.setInputValue(inputD.pin(), bit(row.input(), 3));
            simulator.settle();

            for (int decimal = 0; decimal < 10; decimal++) {
                Value expected = row.lowOutput() == decimal ? Value.ZERO : Value.ONE;
                assertEquals(expected,
                        simulator.getProbeValue(compiled.outputs().get(
                                outputIdsByLabel.get("probe" + decimal))),
                        "D-C-B-A=" + String.format("%4s", Integer.toBinaryString(row.input()))
                                .replace(' ', '0') + ", output " + decimal);
            }
        }
    }

    @Test
    void sn74138PackageAndPinAssignmentsMatchTheTiDip16NTable() throws IOException {
        ComponentDefinition definition = library().get("74138");
        assertEquals("DIP16", definition.packageType());
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("A", 1), Map.entry("B", 2), Map.entry("C", 3),
                Map.entry("G2A", 4), Map.entry("G2B", 5), Map.entry("G1", 6),
                Map.entry("Y7", 7), Map.entry("GND", 8), Map.entry("Y6", 9),
                Map.entry("Y5", 10), Map.entry("Y4", 11), Map.entry("Y3", 12),
                Map.entry("Y2", 13), Map.entry("Y1", 14), Map.entry("Y0", 15),
                Map.entry("VCC", 16));
        Map<String, Integer> actual = new HashMap<>();
        definition.ports().forEach(port -> actual.put(port.name(), port.pin()));
        assertEquals(expected, actual);
        assertEquals(ComponentDefinition.Direction.IN,
                definition.ports().stream().filter(port -> port.name().equals("VCC"))
                        .findFirst().orElseThrow().direction());
        assertEquals(ComponentDefinition.Direction.IN,
                definition.ports().stream().filter(port -> port.name().equals("GND"))
                        .findFirst().orElseThrow().direction());
    }

    @Test
    void sn74138DatasheetFunctionRowsMatchTheFlattenedPoweredPart() throws IOException {
        Schematic schematic = decoderFixture();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());
        Map<String, String> inputIdsByLabel = new HashMap<>();
        Map<String, String> outputIdsByLabel = new HashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("input")) {
                inputIdsByLabel.put(component.label(), component.id());
            } else if (component.typeId().equals("output")) {
                outputIdsByLabel.put(component.label(), component.id());
            }
        }
        InputPinAccess aPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("A")));
        InputPinAccess bPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("B")));
        InputPinAccess cPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("C")));
        InputPinAccess g2aPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("G2A")));
        InputPinAccess g2bPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("G2B")));
        InputPinAccess g1Pin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("G1")));
        InputPinAccess vccPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("VCC")));
        InputPinAccess gndPin = new InputPinAccess(
                compiled.inputs().get(inputIdsByLabel.get("GND")));
        simulator.setInputValue(vccPin.pin(), Value.ONE);
        simulator.setInputValue(gndPin.pin(), Value.ZERO);

        for (DecoderRow row : SN74138_FUNCTION_TABLE) {
            simulator.setInputValue(g1Pin.pin(), row.g1() == 1 ? Value.ONE : Value.ZERO);
            // The table uses G2*=G2A+G2B. A representative high row drives G2A high,
            // G2B low; the G1-low row makes G2* irrelevant.
            simulator.setInputValue(g2aPin.pin(),
                    row.g2Star() == 1 ? Value.ONE : Value.ZERO);
            simulator.setInputValue(g2bPin.pin(), Value.ZERO);
            int selector = row.selector() < 0 ? 0 : row.selector();
            simulator.setInputValue(aPin.pin(), bit(selector, 0));
            simulator.setInputValue(bPin.pin(), bit(selector, 1));
            simulator.setInputValue(cPin.pin(), bit(selector, 2));
            simulator.settle();

            for (int output = 0; output < 8; output++) {
                Value expected = row.lowOutput() == output ? Value.ZERO : Value.ONE;
                String probeId = outputIdsByLabel.get("probeY" + output);
                assertNotNull(probeId, "missing probe label Y" + output);
                assertEquals(expected,
                        simulator.getProbeValue(compiled.outputs().get(probeId)),
                        "G1=" + row.g1() + ", G2*=" + row.g2Star()
                                + ", CBA=" + row.selector() + ", Y" + output);
            }
        }
    }

    @Test
    void sn7442OutputIsUnknownWhenItsVccSupplyIsNotConnected() throws IOException {
        Schematic schematic = bcdFixture(false);
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.X, result.outputValues().get("probe0"));
    }

    private static Schematic decoderFixture() throws IOException {
        ComponentDefinition definition = library().get("74138");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 300, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        for (String signal : List.of("A", "B", "C", "G2A", "G2B", "G1")) {
            addInputAt(schematic, signal, pins.get(signal), false);
        }
        addInputAt(schematic, "GND", pins.get("GND"), false);
        addInputAt(schematic, "VCC", pins.get("VCC"), true);
        for (int output = 0; output < 8; output++) {
            addOutputAt(schematic, "probeY" + output, pins.get("Y" + output));
        }
        return schematic;
    }

    private static Schematic bcdFixture(boolean connectVcc) throws IOException {
        ComponentDefinition definition = library().get("7442");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 300, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);

        addInputAt(schematic, "A", pins.get("A"), false);
        addInputAt(schematic, "B", pins.get("B"), false);
        addInputAt(schematic, "C", pins.get("C"), false);
        addInputAt(schematic, "D", pins.get("D"), false);
        addInputAt(schematic, "GND", pins.get("GND"), false);
        if (connectVcc) {
            addInputAt(schematic, "VCC", pins.get("VCC"), true);
        }
        for (int output = 0; output < 10; output++) {
            addOutputAt(schematic, "probe" + output, pins.get(Integer.toString(output)));
        }
        return schematic;
    }

    private static Value bit(int input, int index) {
        return ((input >>> index) & 1) == 1 ? Value.ONE : Value.ZERO;
    }

    private static Map<String, ComponentInstance.PinGeometry> pins(
            ComponentInstance instance, ComponentDefinition definition) {
        Map<String, ComponentInstance.PinGeometry> result = new HashMap<>();
        instance.geometry(definition).pins().forEach(pin -> result.put(pin.name(), pin));
        return result;
    }

    private static void addInputAt(
            Schematic schematic, String id, ComponentInstance.PinGeometry pin, boolean high) {
        GateDef input = gate("input");
        GateGeometry.Offset output = GateGeometry.outputOffset(input, Facing.EAST);
        schematic.add(new PlacedComponent(
                id, "input", pin.x() - output.x(), pin.y() - output.y(),
                Facing.EAST, Map.of(), id));
        schematic.setInputValue(id, high);
    }

    private static void addOutputAt(
            Schematic schematic, String id, ComponentInstance.PinGeometry pin) {
        GateGeometry.Offset input =
                GateGeometry.inputOffsets(gate("output"), Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pin.x() - input.x(), pin.y() - input.y(),
                Facing.EAST, Map.of(), id));
    }

    private static GateDef gate(String family) {
        return GATES.stream().filter(definition -> definition.family().equals(family))
                .findFirst().orElseThrow();
    }

    private static ComponentLibrary library() throws IOException {
        return ComponentLibrary.load(GATES, Path.of(System.getProperty("java.io.tmpdir"),
                "logicbench-tier-a-" + UUID.randomUUID()));
    }

    private record InputPinAccess(io.github.evilalee.gatelab.model.InputPin pin) {
        private InputPinAccess {
            assertNotNull(pin);
        }
    }
}
