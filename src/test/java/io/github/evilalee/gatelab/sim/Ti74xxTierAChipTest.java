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

    private record DualDecoderRow(
            int enable, int b, int a, int lowOutput) {
    }

    private record PriorityEncoderRow(
            int enable, int selectedInput, int encoded, int gs, int eo) {
    }

    private record MultiplexerRow(int selector, boolean disabled) {
    }

    private record DualMultiplexerRow(
            int b, int a, int selectedInput, int selectedValue, boolean disabled) {
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

    /**
     * Rows transcribed from TI SN74LS139A datasheet SDLS013, page 1:
     * https://www.ti.com/lit/gpn/SN74LS139A
     * This same table applies independently to decoder 1 and decoder 2.
     */
    private static final List<DualDecoderRow> SN74139_FUNCTION_TABLE = List.of(
            new DualDecoderRow(1, -1, -1, -1),
            new DualDecoderRow(0, 0, 0, 0),
            new DualDecoderRow(0, 0, 1, 1),
            new DualDecoderRow(0, 1, 0, 2),
            new DualDecoderRow(0, 1, 1, 3));

    /**
     * Rows transcribed from TI SN74LS148 datasheet SDLS053B, page 3:
     * https://www.ti.com/lit/gpn/SN74LS148
     * Inputs are 0..7 in ascending order. -2 denotes the disabled EI=H row;
     * -1 denotes enabled with no active-low data input.
     */
    private static final List<PriorityEncoderRow> SN74148_FUNCTION_TABLE = List.of(
            new PriorityEncoderRow(1, -2, 0b111, 1, 1),
            new PriorityEncoderRow(0, -1, 0b111, 1, 0),
            new PriorityEncoderRow(0, 7, 0b000, 0, 1),
            new PriorityEncoderRow(0, 6, 0b001, 0, 1),
            new PriorityEncoderRow(0, 5, 0b010, 0, 1),
            new PriorityEncoderRow(0, 4, 0b011, 0, 1),
            new PriorityEncoderRow(0, 3, 0b100, 0, 1),
            new PriorityEncoderRow(0, 2, 0b101, 0, 1),
            new PriorityEncoderRow(0, 1, 0b110, 0, 1),
            new PriorityEncoderRow(0, 0, 0b111, 0, 1));

    /**
     * Rows transcribed from TI SN74LS151 datasheet SDLS054, page 4:
     * https://www.ti.com/lit/gpn/SN74LS151
     * Dn denotes the selected data input; the test evaluates each row with
     * two distinct data patterns to exercise both values of Dn.
     */
    private static final List<MultiplexerRow> SN74151_FUNCTION_TABLE = List.of(
            new MultiplexerRow(-1, true),
            new MultiplexerRow(0, false),
            new MultiplexerRow(1, false),
            new MultiplexerRow(2, false),
            new MultiplexerRow(3, false),
            new MultiplexerRow(4, false),
            new MultiplexerRow(5, false),
            new MultiplexerRow(6, false),
            new MultiplexerRow(7, false));

    /**
     * Rows transcribed from TI SN74LS153 datasheet SDLS055A, page 1:
     * https://www.ti.com/lit/ds/symlink/sn74ls153.pdf
     * The same function table applies independently to each mux section.
     */
    private static final List<DualMultiplexerRow> SN74153_FUNCTION_TABLE = List.of(
            new DualMultiplexerRow(-1, -1, -1, -1, true),
            new DualMultiplexerRow(0, 0, 0, 0, false),
            new DualMultiplexerRow(0, 0, 0, 1, false),
            new DualMultiplexerRow(0, 1, 1, 0, false),
            new DualMultiplexerRow(0, 1, 1, 1, false),
            new DualMultiplexerRow(1, 0, 2, 0, false),
            new DualMultiplexerRow(1, 0, 2, 1, false),
            new DualMultiplexerRow(1, 1, 3, 0, false),
            new DualMultiplexerRow(1, 1, 3, 1, false));

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
    void sn74139PackageAndPinAssignmentsMatchTheTiDip16NTable() throws IOException {
        ComponentDefinition definition = library().get("74139");
        assertEquals("DIP16", definition.packageType());
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("1G", 1), Map.entry("1A", 2), Map.entry("1B", 3),
                Map.entry("1Y0", 4), Map.entry("1Y1", 5), Map.entry("1Y2", 6),
                Map.entry("1Y3", 7), Map.entry("GND", 8), Map.entry("2Y3", 9),
                Map.entry("2Y2", 10), Map.entry("2Y1", 11), Map.entry("2Y0", 12),
                Map.entry("2B", 13), Map.entry("2A", 14), Map.entry("2G", 15),
                Map.entry("VCC", 16));
        Map<String, Integer> actual = new HashMap<>();
        definition.ports().forEach(port -> actual.put(port.name(), port.pin()));
        assertEquals(expected, actual);
    }

    @Test
    void sn74139BothDecodersMatchTheSameTiFunctionTableWhenPowered() throws IOException {
        Schematic schematic = dualDecoderFixture();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());
        Map<String, String> inputIds = componentIdsByLabel(schematic, "input");
        Map<String, String> outputIds = componentIdsByLabel(schematic, "output");

        Map<String, InputPinAccess> inputs = new HashMap<>();
        for (String name : List.of("1G", "1A", "1B", "2G", "2A", "2B", "VCC", "GND")) {
            inputs.put(name, new InputPinAccess(compiled.inputs().get(inputIds.get(name))));
        }
        simulator.setInputValue(inputs.get("VCC").pin(), Value.ONE);
        simulator.setInputValue(inputs.get("GND").pin(), Value.ZERO);

        for (DualDecoderRow row : SN74139_FUNCTION_TABLE) {
            for (String prefix : List.of("1", "2")) {
                simulator.setInputValue(inputs.get(prefix + "G").pin(),
                        row.enable() == 1 ? Value.ONE : Value.ZERO);
                int a = row.a() < 0 ? 0 : row.a();
                int b = row.b() < 0 ? 0 : row.b();
                simulator.setInputValue(inputs.get(prefix + "A").pin(), bit(a, 0));
                simulator.setInputValue(inputs.get(prefix + "B").pin(), bit(b, 0));
            }
            simulator.settle();

            for (String prefix : List.of("1", "2")) {
                for (int output = 0; output < 4; output++) {
                    String signal = prefix + "Y" + output;
                    String probe = "probe" + signal;
                    String probeId = outputIds.get(probe);
                    assertNotNull(probeId, "missing output probe " + probe);
                    Value expected = row.lowOutput() == output ? Value.ZERO : Value.ONE;
                    assertEquals(expected,
                            simulator.getProbeValue(compiled.outputs().get(probeId)),
                            "decoder " + prefix + ", G=" + row.enable()
                                    + ", B=" + row.b() + ", A=" + row.a()
                                    + ", output " + signal);
                }
            }
        }
    }

    @Test
    void sn74148PackageAndPinAssignmentsMatchTheTiDip16NTable() throws IOException {
        ComponentDefinition definition = library().get("74148");
        assertEquals("DIP16", definition.packageType());
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("4", 1), Map.entry("5", 2), Map.entry("6", 3),
                Map.entry("7", 4), Map.entry("EI", 5), Map.entry("A2", 6),
                Map.entry("A1", 7), Map.entry("GND", 8), Map.entry("A0", 9),
                Map.entry("0", 10), Map.entry("1", 11), Map.entry("2", 12),
                Map.entry("3", 13), Map.entry("GS", 14), Map.entry("EO", 15),
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
    void sn74148DatasheetRowsMatchTheFlattenedPoweredPriorityEncoder() throws IOException {
        Schematic schematic = priorityEncoderFixture();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());
        Map<String, String> inputIds = componentIdsByLabel(schematic, "input");
        Map<String, String> outputIds = componentIdsByLabel(schematic, "output");
        Map<String, InputPinAccess> inputs = new HashMap<>();
        for (String name : List.of("EI", "0", "1", "2", "3", "4", "5", "6", "7",
                "VCC", "GND")) {
            inputs.put(name, new InputPinAccess(compiled.inputs().get(inputIds.get(name))));
        }
        simulator.setInputValue(inputs.get("VCC").pin(), Value.ONE);
        simulator.setInputValue(inputs.get("GND").pin(), Value.ZERO);

        for (PriorityEncoderRow row : SN74148_FUNCTION_TABLE) {
            simulator.setInputValue(inputs.get("EI").pin(),
                    row.enable() == 1 ? Value.ONE : Value.ZERO);
            for (int input = 0; input < 8; input++) {
                boolean high = row.selectedInput() == -2
                        ? false : row.selectedInput() != input;
                simulator.setInputValue(inputs.get(Integer.toString(input)).pin(),
                        high ? Value.ONE : Value.ZERO);
            }
            simulator.settle();

            for (int bit = 2; bit >= 0; bit--) {
                String output = "A" + bit;
                Value expected = ((row.encoded() >>> bit) & 1) == 1
                        ? Value.ONE : Value.ZERO;
                String probeId = outputIds.get("probe" + output);
                assertNotNull(probeId, "missing output probe " + output);
                assertEquals(expected,
                        simulator.getProbeValue(compiled.outputs().get(probeId)),
                        "EI=" + row.enable() + ", selected=" + row.selectedInput()
                                + ", " + output);
            }
            for (String control : List.of("GS", "EO")) {
                int expectedBit = control.equals("GS") ? row.gs() : row.eo();
                Value expected = expectedBit == 1 ? Value.ONE : Value.ZERO;
                String probeId = outputIds.get("probe" + control);
                assertNotNull(probeId, "missing output probe " + control);
                assertEquals(expected,
                        simulator.getProbeValue(compiled.outputs().get(probeId)),
                        "EI=" + row.enable() + ", selected=" + row.selectedInput()
                                + ", " + control);
            }
        }
    }

    @Test
    void sn74151PackageAndPinAssignmentsMatchTheTiDip16NTable() throws IOException {
        ComponentDefinition definition = library().get("74151");
        assertEquals("DIP16", definition.packageType());
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("D3", 1), Map.entry("D2", 2), Map.entry("D1", 3),
                Map.entry("D0", 4), Map.entry("Y", 5), Map.entry("W", 6),
                Map.entry("G", 7), Map.entry("GND", 8), Map.entry("C", 9),
                Map.entry("B", 10), Map.entry("A", 11), Map.entry("D7", 12),
                Map.entry("D6", 13), Map.entry("D5", 14), Map.entry("D4", 15),
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
    void sn74151FunctionRowsMatchFlattenedPoweredMultiplexerForBothDataLevels()
            throws IOException {
        Schematic schematic = multiplexerFixture();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());
        Map<String, String> inputIds = componentIdsByLabel(schematic, "input");
        Map<String, String> outputIds = componentIdsByLabel(schematic, "output");
        Map<String, InputPinAccess> inputs = new HashMap<>();
        for (String name : List.of("G", "A", "B", "C",
                "D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7", "VCC", "GND")) {
            inputs.put(name, new InputPinAccess(compiled.inputs().get(inputIds.get(name))));
        }
        simulator.setInputValue(inputs.get("VCC").pin(), Value.ONE);
        simulator.setInputValue(inputs.get("GND").pin(), Value.ZERO);

        for (MultiplexerRow row : SN74151_FUNCTION_TABLE) {
            for (int dataPattern : new int[]{0b1010_0101, 0b0101_1010}) {
                for (int data = 0; data < 8; data++) {
                    simulator.setInputValue(inputs.get("D" + data).pin(),
                            ((dataPattern >>> data) & 1) == 1 ? Value.ONE : Value.ZERO);
                }
                simulator.setInputValue(inputs.get("G").pin(),
                        row.disabled() ? Value.ONE : Value.ZERO);
                int selector = row.selector() < 0 ? 0 : row.selector();
                simulator.setInputValue(inputs.get("A").pin(), bit(selector, 0));
                simulator.setInputValue(inputs.get("B").pin(), bit(selector, 1));
                simulator.setInputValue(inputs.get("C").pin(), bit(selector, 2));
                simulator.settle();

                Value expectedY = row.disabled() ? Value.ZERO
                        : ((dataPattern >>> row.selector()) & 1) == 1
                                ? Value.ONE : Value.ZERO;
                Value expectedW = expectedY == Value.ONE ? Value.ZERO : Value.ONE;
                assertEquals(expectedY,
                        simulator.getProbeValue(compiled.outputs().get(
                                outputIds.get("probeY"))),
                        "G=" + (row.disabled() ? "H" : "L")
                                + ", CBA=" + Integer.toBinaryString(selector)
                                + ", data=0x" + Integer.toHexString(dataPattern));
                assertEquals(expectedW,
                        simulator.getProbeValue(compiled.outputs().get(
                                outputIds.get("probeW"))),
                        "complement output for CBA=" + selector);
            }
        }
    }

    @Test
    void sn74153PackageAndPinAssignmentsMatchTheTiDip16NTable() throws IOException {
        ComponentDefinition definition = library().get("74153");
        assertEquals("DIP16", definition.packageType());
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("1G", 1), Map.entry("B", 2), Map.entry("1C3", 3),
                Map.entry("1C2", 4), Map.entry("1C1", 5), Map.entry("1C0", 6),
                Map.entry("1Y", 7), Map.entry("GND", 8), Map.entry("2Y", 9),
                Map.entry("2C0", 10), Map.entry("2C1", 11), Map.entry("2C2", 12),
                Map.entry("2C3", 13), Map.entry("A", 14), Map.entry("2G", 15),
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
    void sn74153BothSectionsMatchTheTiFunctionRowsWithIndependentStrobes()
            throws IOException {
        Schematic schematic = dualMultiplexerFixture();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        Simulator simulator = new Simulator(compiled.circuit());
        Map<String, String> inputIds = componentIdsByLabel(schematic, "input");
        Map<String, String> outputIds = componentIdsByLabel(schematic, "output");
        Map<String, InputPinAccess> inputs = new HashMap<>();
        for (String name : List.of("1G", "2G", "A", "B",
                "1C0", "1C1", "1C2", "1C3",
                "2C0", "2C1", "2C2", "2C3", "VCC", "GND")) {
            inputs.put(name, new InputPinAccess(compiled.inputs().get(inputIds.get(name))));
        }
        simulator.setInputValue(inputs.get("VCC").pin(), Value.ONE);
        simulator.setInputValue(inputs.get("GND").pin(), Value.ZERO);

        int rowIndex = 0;
        for (DualMultiplexerRow row : SN74153_FUNCTION_TABLE) {
            int selectorA = row.a() < 0 ? 0 : row.a();
            int selectorB = row.b() < 0 ? 0 : row.b();
            simulator.setInputValue(inputs.get("A").pin(), bit(selectorA, 0));
            simulator.setInputValue(inputs.get("B").pin(), bit(selectorB, 0));
            simulator.setInputValue(inputs.get("1G").pin(),
                    row.disabled() ? Value.ONE : Value.ZERO);
            simulator.setInputValue(inputs.get("2G").pin(),
                    row.disabled() ? Value.ZERO : Value.ONE);

            for (int data = 0; data < 4; data++) {
                int firstData = data == row.selectedInput()
                        ? row.selectedValue() : ((rowIndex + data) & 1);
                int secondData = 1 - firstData;
                simulator.setInputValue(inputs.get("1C" + data).pin(),
                        firstData == 1 ? Value.ONE : Value.ZERO);
                simulator.setInputValue(inputs.get("2C" + data).pin(),
                        secondData == 1 ? Value.ONE : Value.ZERO);
            }
            simulator.settle();

            Value expectedFirst = !row.disabled() && row.selectedValue() == 1
                    ? Value.ONE : Value.ZERO;
            Value expectedSecond = row.disabled() && row.selectedValue() == 0
                    ? Value.ONE : Value.ZERO;
            assertEquals(expectedFirst,
                    simulator.getProbeValue(compiled.outputs().get(
                            outputIds.get("probe1Y"))),
                    "section 1, B=" + row.b() + ", A=" + row.a()
                            + ", C" + row.selectedInput() + "=" + row.selectedValue());
            assertEquals(expectedSecond,
                    simulator.getProbeValue(compiled.outputs().get(
                            outputIds.get("probe2Y"))),
                    "section 2, B=" + row.b() + ", A=" + row.a()
                            + ", C" + row.selectedInput() + "=" + (1 - row.selectedValue()));
            rowIndex++;
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

    private static Schematic dualMultiplexerFixture() throws IOException {
        ComponentDefinition definition = library().get("74153");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 350, 250, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        for (String signal : List.of("1G", "2G", "A", "B",
                "1C0", "1C1", "1C2", "1C3",
                "2C0", "2C1", "2C2", "2C3")) {
            addInputAt(schematic, signal, pins.get(signal), false);
        }
        addInputAt(schematic, "GND", pins.get("GND"), false);
        addInputAt(schematic, "VCC", pins.get("VCC"), true);
        addOutputAt(schematic, "probe1Y", pins.get("1Y"));
        addOutputAt(schematic, "probe2Y", pins.get("2Y"));
        return schematic;
    }

    private static Schematic multiplexerFixture() throws IOException {
        ComponentDefinition definition = library().get("74151");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 350, 250, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        for (String signal : List.of("G", "A", "B", "C",
                "D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7")) {
            addInputAt(schematic, signal, pins.get(signal), false);
        }
        addInputAt(schematic, "GND", pins.get("GND"), false);
        addInputAt(schematic, "VCC", pins.get("VCC"), true);
        addOutputAt(schematic, "probeY", pins.get("Y"));
        addOutputAt(schematic, "probeW", pins.get("W"));
        return schematic;
    }

    private static Schematic priorityEncoderFixture() throws IOException {
        ComponentDefinition definition = library().get("74148");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 300, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        for (String name : List.of("EI", "0", "1", "2", "3", "4", "5", "6", "7")) {
            addInputAt(schematic, name, pins.get(name), false);
        }
        addInputAt(schematic, "GND", pins.get("GND"), false);
        addInputAt(schematic, "VCC", pins.get("VCC"), true);
        for (String name : List.of("A2", "A1", "A0", "GS", "EO")) {
            addOutputAt(schematic, "probe" + name, pins.get(name));
        }
        return schematic;
    }

    private static Schematic dualDecoderFixture() throws IOException {
        ComponentDefinition definition = library().get("74139");
        Schematic schematic = new Schematic(GATES);
        ComponentInstance chip = schematic.addInstance(
                definition, 300, 200, Facing.EAST);
        Map<String, ComponentInstance.PinGeometry> pins = pins(chip, definition);
        for (String name : List.of("1G", "1A", "1B", "2G", "2A", "2B")) {
            addInputAt(schematic, name, pins.get(name), false);
        }
        addInputAt(schematic, "GND", pins.get("GND"), false);
        addInputAt(schematic, "VCC", pins.get("VCC"), true);
        for (String section : List.of("1", "2")) {
            for (int output = 0; output < 4; output++) {
                String signal = section + "Y" + output;
                addOutputAt(schematic, "probe" + signal, pins.get(signal));
            }
        }
        return schematic;
    }

    private static Map<String, String> componentIdsByLabel(
            Schematic schematic, String family) {
        Map<String, String> result = new HashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals(family)) {
                result.put(component.label(), component.id());
            }
        }
        return result;
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
