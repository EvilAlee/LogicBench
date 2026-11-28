package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.ChipPowerChecker;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import io.github.evilalee.gatelab.sim.Simulator;
import io.github.evilalee.gatelab.ui.ComponentInstanceRenderer;
import io.github.evilalee.gatelab.synth.GateOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Ti74xxChipTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    private record FunctionCase(String output, List<String> inputs, GateOperator operation) {
        private FunctionCase(String output, GateOperator operation, String... inputs) {
            this(output, List.of(inputs), operation);
        }
    }

    private record ChipCase(String name, List<FunctionCase> functions) { }

    @Test
    void builtInPackagesAndPinAssignmentsMatchTheSuppliedPinoutTables() {
        ComponentLibrary library = loadLibrary();
        Map<String, Map<String, Integer>> expected = new HashMap<>();
        Map<String, Integer> quad = pins(
                "1A", 1, "1B", 2, "1Y", 3, "2A", 4, "2B", 5, "2Y", 6,
                "GND", 7, "3Y", 8, "3A", 9, "3B", 10, "4Y", 11,
                "4A", 12, "4B", 13, "VCC", 14);
        expected.put("7400", quad);
        expected.put("7408", quad);
        expected.put("7432", quad);
        expected.put("7486", quad);
        expected.put("7402", pins(
                "1Y", 1, "1A", 2, "1B", 3, "2Y", 4, "2A", 5, "2B", 6,
                "GND", 7, "3A", 8, "3B", 9, "3Y", 10, "4A", 11,
                "4B", 12, "4Y", 13, "VCC", 14));
        expected.put("7404", pins(
                "1A", 1, "1Y", 2, "2A", 3, "2Y", 4, "3A", 5, "3Y", 6,
                "GND", 7, "4Y", 8, "4A", 9, "5Y", 10, "5A", 11,
                "6Y", 12, "6A", 13, "VCC", 14));
        Map<String, Integer> triple = pins(
                "1A", 1, "1B", 2, "2A", 3, "2B", 4, "2C", 5, "2Y", 6,
                "GND", 7, "3Y", 8, "3A", 9, "3B", 10, "3C", 11,
                "1Y", 12, "1C", 13, "VCC", 14);
        expected.put("7410", triple);
        expected.put("7411", triple);
        expected.put("7427", triple);
        expected.put("7420", pins(
                "1A", 1, "1B", 2, "1C", 4, "1D", 5, "1Y", 6,
                "GND", 7, "2Y", 8, "2A", 9, "2B", 10,
                "2C", 12, "2D", 13, "VCC", 14));
        expected.put("7430", pins(
                "A", 1, "B", 2, "C", 3, "D", 4, "E", 5, "F", 6,
                "GND", 7, "Y", 8, "G", 11, "H", 12, "VCC", 14));
        expected.put("7442", pins(
                "0", 1, "1", 2, "2", 3, "3", 4, "4", 5, "5", 6,
                "6", 7, "GND", 8, "7", 9, "8", 10, "9", 11,
                "D", 12, "C", 13, "B", 14, "A", 15, "VCC", 16));
        expected.put("74138", pins(
                "A", 1, "B", 2, "C", 3, "G2A", 4, "G2B", 5,
                "G1", 6, "Y7", 7, "GND", 8, "Y6", 9, "Y5", 10,
                "Y4", 11, "Y3", 12, "Y2", 13, "Y1", 14, "Y0", 15, "VCC", 16));

        expected.put("74139", pins(
                "1G", 1, "1A", 2, "1B", 3, "1Y0", 4, "1Y1", 5,
                "1Y2", 6, "1Y3", 7, "GND", 8, "2Y3", 9, "2Y2", 10,
                "2Y1", 11, "2Y0", 12, "2B", 13, "2A", 14, "2G", 15, "VCC", 16));

        expected.put("74148", pins(
                "4", 1, "5", 2, "6", 3, "7", 4, "EI", 5, "A2", 6,
                "A1", 7, "GND", 8, "A0", 9, "0", 10, "1", 11,
                "2", 12, "3", 13, "GS", 14, "EO", 15, "VCC", 16));

        assertEquals(expected.keySet(), library.builtIns().stream()
                .filter(definition -> definition.category().equals("TTL 74xx"))
                .map(ComponentDefinition::name)
                .collect(java.util.stream.Collectors.toSet()));
        for (Map.Entry<String, Map<String, Integer>> entry : expected.entrySet()) {
            ComponentDefinition definition = library.get(entry.getKey());
            assertEquals(
                    Set.of("7442", "74138", "74139", "74148").contains(entry.getKey()) ? "DIP16" : "DIP14",
                    definition.packageType(), entry.getKey());
            assertEquals(entry.getValue(), definition.ports().stream().collect(
                    java.util.stream.Collectors.toMap(
                            ComponentDefinition.Port::name, ComponentDefinition.Port::pin)),
                    entry.getKey());
            assertFalse(definition.description().isBlank());
            assertTrue(definition.ports().stream().anyMatch(port ->
                    port.name().equals("VCC") && port.direction() == ComponentDefinition.Direction.IN));
            assertTrue(definition.ports().stream().anyMatch(port ->
                    port.name().equals("GND") && port.direction() == ComponentDefinition.Direction.IN));
        }
    }

    @Test
    void everyGateSectionMatchesItsExhaustiveBooleanFunctionTable() {
        ComponentLibrary library = loadLibrary();
        List<ChipCase> chips = List.of(
                new ChipCase("7400", List.of(
                        f("1Y", GateOperator.NAND, "1A", "1B"),
                        f("2Y", GateOperator.NAND, "2A", "2B"),
                        f("3Y", GateOperator.NAND, "3A", "3B"),
                        f("4Y", GateOperator.NAND, "4A", "4B"))),
                new ChipCase("7402", List.of(
                        f("1Y", GateOperator.NOR, "1A", "1B"),
                        f("2Y", GateOperator.NOR, "2A", "2B"),
                        f("3Y", GateOperator.NOR, "3A", "3B"),
                        f("4Y", GateOperator.NOR, "4A", "4B"))),
                new ChipCase("7404", List.of(
                        f("1Y", GateOperator.NOT, "1A"),
                        f("2Y", GateOperator.NOT, "2A"),
                        f("3Y", GateOperator.NOT, "3A"),
                        f("4Y", GateOperator.NOT, "4A"),
                        f("5Y", GateOperator.NOT, "5A"),
                        f("6Y", GateOperator.NOT, "6A"))),
                new ChipCase("7408", List.of(
                        f("1Y", GateOperator.AND, "1A", "1B"),
                        f("2Y", GateOperator.AND, "2A", "2B"),
                        f("3Y", GateOperator.AND, "3A", "3B"),
                        f("4Y", GateOperator.AND, "4A", "4B"))),
                new ChipCase("7410", List.of(
                        f("1Y", GateOperator.NAND, "1A", "1B", "1C"),
                        f("2Y", GateOperator.NAND, "2A", "2B", "2C"),
                        f("3Y", GateOperator.NAND, "3A", "3B", "3C"))),
                new ChipCase("7411", List.of(
                        f("1Y", GateOperator.AND, "1A", "1B", "1C"),
                        f("2Y", GateOperator.AND, "2A", "2B", "2C"),
                        f("3Y", GateOperator.AND, "3A", "3B", "3C"))),
                new ChipCase("7420", List.of(
                        f("1Y", GateOperator.NAND, "1A", "1B", "1C", "1D"),
                        f("2Y", GateOperator.NAND, "2A", "2B", "2C", "2D"))),
                new ChipCase("7427", List.of(
                        f("1Y", GateOperator.NOR, "1A", "1B", "1C"),
                        f("2Y", GateOperator.NOR, "2A", "2B", "2C"),
                        f("3Y", GateOperator.NOR, "3A", "3B", "3C"))),
                new ChipCase("7430", List.of(
                        f("Y", GateOperator.NAND, "A", "B", "C", "D", "E", "F", "G", "H"))),
                new ChipCase("7432", List.of(
                        f("1Y", GateOperator.OR, "1A", "1B"),
                        f("2Y", GateOperator.OR, "2A", "2B"),
                        f("3Y", GateOperator.OR, "3A", "3B"),
                        f("4Y", GateOperator.OR, "4A", "4B"))),
                new ChipCase("7486", List.of(
                        f("1Y", GateOperator.XOR, "1A", "1B"),
                        f("2Y", GateOperator.XOR, "2A", "2B"),
                        f("3Y", GateOperator.XOR, "3A", "3B"),
                        f("4Y", GateOperator.XOR, "4A", "4B"))));

        for (ChipCase chip : chips) {
            ComponentDefinition definition = library.get(chip.name());
            Schematic internal = definition.internalCircuit();
            SchematicCompiler.CompiledCircuit compiled =
                    SchematicCompiler.compileCircuit(internal);
            Map<String, String> inputIds = new HashMap<>();
            Map<String, String> outputIds = new HashMap<>();
            for (PlacedComponent component : internal.components()) {
                if (component.typeId().equals("input")) inputIds.put(component.label(), component.id());
                if (component.typeId().equals("output")) outputIds.put(component.label(), component.id());
            }
            Simulator simulator = new Simulator(compiled.circuit());
            for (FunctionCase function : chip.functions()) {
                int rows = 1 << function.inputs().size();
                for (int row = 0; row < rows; row++) {
                    for (Map.Entry<String, String> input : inputIds.entrySet()) {
                        boolean high = false;
                        int inputIndex = function.inputs().indexOf(input.getKey());
                        if (inputIndex >= 0) high = (row & (1 << inputIndex)) != 0;
                        simulator.setInputValue(compiled.inputs().get(input.getValue()),
                                high ? Value.ONE : Value.ZERO);
                    }
                    simulator.settle();
                    Value actual = simulator.getProbeValue(
                            compiled.outputs().get(outputIds.get(function.output())));
                    boolean expected = expected(function.operation(), row, function.inputs().size());
                    assertEquals(expected ? Value.ONE : Value.ZERO, actual,
                            chip.name() + "." + function.output() + " row " + row);
                }
            }
        }
    }

    @Test
    void dip14AndDip16GeometryNumbersAndPhysicalSidesRotateTogether() {
        ComponentDefinition dip14 = loadLibrary().get("7400");
        ComponentDefinition dip16 = minimalDip16();
        assertRotatedPackageGeometry(dip14, 14);
        assertRotatedPackageGeometry(dip16, 16);
    }

    @Test
    void chipMetadataSurvivesLbcompEmbeddedJsonAndClipboard(@TempDir Path temp)
            throws IOException {
        ComponentDefinition definition = loadLibrary().get("7400");
        Path chipFile = temp.resolve("7400.lbcomp");
        ComponentLibrary.exportDefinition(definition, chipFile);
        ComponentDefinition fromFile = ComponentLibrary.readDefinition(chipFile, GATES);
        assertTrue(Files.readString(chipFile).contains("\"package\": \"DIP14\""));
        ComponentDefinition fromEmbedded = ComponentDefinitionJson.fromJson(
                ComponentDefinitionJson.toJson(definition), GATES);
        for (ComponentDefinition copy : List.of(fromFile, fromEmbedded)) {
            assertEquals(definition.hash(), copy.hash());
            assertEquals(definition.packageType(), copy.packageType());
            assertEquals(definition.ports(), copy.ports());
        }

        Schematic source = new Schematic(GATES);
        source.addInstance(definition, "U1", 200, 200, Facing.WEST);
        String text = SchematicClipboard.encode(source, List.of("U1"), List.of()).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(text, GATES, null)
                .orElseThrow();
        Schematic target = new Schematic(GATES);
        SchematicClipboard.paste(target, payload, null, null, 300, 300).orElseThrow();
        ComponentDefinition pasted = target.componentDefinitionFor(
                target.componentInstances().getFirst());
        assertEquals(definition.packageType(), pasted.packageType());
        assertEquals(definition.ports(), pasted.ports());
        assertEquals(definition.hash(), pasted.hash());
        assertEquals(14, target.pinPositions().stream()
                .filter(pin -> pin.componentId().equals("U1")).count());
    }

    @Test
    void rendererDrawsChipNotchAndPinLabelsWithoutThrowing() {
        ComponentDefinition definition = loadLibrary().get("7400");
        ComponentInstance instance = new ComponentInstance(
                "7400", definition.hash(), "U1", "", 40, 40, Facing.EAST);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                220, 230, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            ComponentInstanceRenderer renderer = new ComponentInstanceRenderer();
            assertDoesNotThrow(() -> renderer.render(
                    graphics, instance, definition, instance.geometry(definition), 1.0));
        } finally {
            graphics.dispose();
        }
        assertNotEquals(0, image.getRGB(40, 80),
                "DIP body outline is painted");
    }

    @Test
    void powerCheckerUsesSimulatedVccAndGndNetsAndCanBeDisabled() {
        ComponentDefinition chip = loadLibrary().get("7400");
        Schematic schematic = poweredChipSchematic(chip, true, false);
        SimulationResult powered = SchematicCompiler.compileAndSettle(schematic);
        assertTrue(ChipPowerChecker.check(schematic, powered, true).isEmpty());
        assertTrue(ChipPowerChecker.check(schematic, powered, false).isEmpty());

        schematic.setInputValue("power-vcc", false);
        SimulationResult lowVcc = SchematicCompiler.compileAndSettle(schematic, powered);
        assertEquals("U1 (7400): no power",
                ChipPowerChecker.statusText(schematic, lowVcc, true));
        assertTrue(ChipPowerChecker.check(schematic, lowVcc, false).isEmpty());

        schematic.setInputValue("power-vcc", true);
        schematic.setInputValue("power-gnd", true);
        SimulationResult highGnd = SchematicCompiler.compileAndSettle(schematic, lowVcc);
        assertEquals(1, ChipPowerChecker.check(schematic, highGnd, true).size());
    }

    private static ComponentLibrary loadLibrary() {
        try {
            return ComponentLibrary.load(GATES, Path.of(System.getProperty("java.io.tmpdir"),
                    "logicbench-ttl-chip-tests-" + java.util.UUID.randomUUID()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static FunctionCase f(String output, GateOperator operation, String... inputs) {
        return new FunctionCase(output, operation, inputs);
    }

    private static boolean expected(GateOperator operation, int row, int count) {
        if (operation == GateOperator.NOT) return (row & 1) == 0;
        boolean aggregate = operation == GateOperator.AND || operation == GateOperator.NAND;
        for (int index = 0; index < count; index++) {
            boolean bit = (row & (1 << index)) != 0;
            aggregate = switch (operation) {
                case AND, NAND -> aggregate && bit;
                case OR, NOR -> aggregate || bit;
                case XOR -> aggregate ^ bit;
                default -> throw new IllegalArgumentException(
                        "unsupported test operation " + operation);
            };
        }
        return (operation == GateOperator.NAND || operation == GateOperator.NOR)
                ? !aggregate : aggregate;
    }

    private static Map<String, Integer> pins(Object... entries) {
        Map<String, Integer> result = new HashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], (Integer) entries[index + 1]);
        }
        return result;
    }

    private static ComponentDefinition minimalDip16() {
        Schematic inner = new Schematic(GATES);
        inner.add(new PlacedComponent("a", "input", 0, 0, Facing.EAST, Map.of(), "A"));
        inner.add(new PlacedComponent("gnd", "input", 0, 40, Facing.EAST, Map.of(), "GND"));
        inner.add(new PlacedComponent("vcc", "input", 0, 80, Facing.EAST, Map.of(), "VCC"));
        inner.add(new PlacedComponent("y", "output", 200, 0, Facing.EAST, Map.of(), "Y"));
        return new ComponentDefinition("Dip16Sample", "DIP-16 test component", "Test",
                List.of(
                        new ComponentDefinition.Port("A", ComponentDefinition.Direction.IN, false, 1),
                        new ComponentDefinition.Port("GND", ComponentDefinition.Direction.IN, false, 8),
                        new ComponentDefinition.Port("VCC", ComponentDefinition.Direction.IN, false, 9),
                        new ComponentDefinition.Port("Y", ComponentDefinition.Direction.OUT, false, 16)),
                null, "DIP16", inner);
    }

    private static void assertRotatedPackageGeometry(ComponentDefinition definition, int pinCount) {
        int packageWidth = ComponentInstance.BOX_WIDTH;
        int packageHeight = pinCount / 2 * ComponentInstance.PORT_SPACING
                + ComponentInstance.PORT_SPACING;
        int half = pinCount / 2;
        for (Facing rotation : Facing.values()) {
            ComponentInstance instance = new ComponentInstance(
                    definition.name(), definition.hash(), "U1", "", 100, 200, rotation);
            ComponentInstance.Geometry geometry = instance.geometry(definition);
            assertEquals(rotation == Facing.EAST || rotation == Facing.WEST
                    ? packageWidth : packageHeight, geometry.width());
            assertEquals(rotation == Facing.EAST || rotation == Facing.WEST
                    ? packageHeight : packageWidth, geometry.height());
            for (int number = 1; number <= pinCount; number++) {
                int physicalNumber = number;
                boolean left = number <= half;
                int localX = left ? 0 : packageWidth;
                int localY = 20 + (left ? number - 1 : pinCount - number) * 20;
                int expectedX;
                int expectedY;
                switch (rotation) {
                    case EAST -> {
                        expectedX = 100 + localX;
                        expectedY = 200 + localY;
                    }
                    case SOUTH -> {
                        expectedX = 100 + packageHeight - localY;
                        expectedY = 200 + localX;
                    }
                    case WEST -> {
                        expectedX = 100 + packageWidth - localX;
                        expectedY = 200 + packageHeight - localY;
                    }
                    case NORTH -> {
                        expectedX = 100 + localY;
                        expectedY = 200 + packageWidth - localX;
                    }
                    default -> throw new AssertionError(rotation);
                }
                ComponentInstance.PinGeometry pin = geometry.pins().stream()
                        .filter(candidate -> candidate.pinNumber() != null
                                && candidate.pinNumber() == physicalNumber)
                        .findFirst().orElseThrow();
                assertEquals(expectedX, pin.x(), definition.name() + " pin " + number + " " + rotation);
                assertEquals(expectedY, pin.y(), definition.name() + " pin " + number + " " + rotation);
                Facing baseSide = left ? Facing.WEST : Facing.EAST;
                assertEquals(rotateSide(baseSide, rotation), pin.outwardFacing());
            }
        }
    }

    private static Facing rotateSide(Facing base, Facing rotation) {
        GateGeometry.Offset vector = switch (base) {
            case WEST -> new GateGeometry.Offset(-1, 0);
            case EAST -> new GateGeometry.Offset(1, 0);
            default -> throw new AssertionError(base);
        };
        GateGeometry.Offset turned = GateGeometry.rotate(vector, rotation);
        if (turned.x() < 0) return Facing.WEST;
        if (turned.x() > 0) return Facing.EAST;
        if (turned.y() < 0) return Facing.NORTH;
        return Facing.SOUTH;
    }

    private static Schematic poweredChipSchematic(
            ComponentDefinition definition, boolean vccHigh, boolean gndHigh) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance instance = schematic.addInstance(definition, "U1", 200, 200, Facing.EAST);
        Map<String, Connectivity.PinPosition> chipPins = new HashMap<>();
        for (Connectivity.PinPosition pin : instance.pinPositions(definition)) {
            chipPins.put(pin.pinId(), pin);
        }
        GateDef input = GateLibrary.load().defaultDefinition("input");
        GateGeometry.Offset output = GateGeometry.outputOffset(input, Facing.EAST);
        Connectivity.PinPosition vcc = chipPins.get("VCC");
        Connectivity.PinPosition gnd = chipPins.get("GND");
        schematic.add(new PlacedComponent(
                "power-vcc", "input", vcc.x() - output.x(), vcc.y() - output.y(),
                Facing.EAST, Map.of(), "VCC source"));
        schematic.add(new PlacedComponent(
                "power-gnd", "input", gnd.x() - output.x(), gnd.y() - output.y(),
                Facing.EAST, Map.of(), "GND source"));
        schematic.setInputValue("power-vcc", vccHigh);
        schematic.setInputValue("power-gnd", gndHigh);
        return schematic;
    }
}
