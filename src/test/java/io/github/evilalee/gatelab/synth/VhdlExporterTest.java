package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.generators.StandardCircuitGenerators;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class VhdlExporterTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void fullAdderMatchesGoldenVhdl(@TempDir Path temp) throws IOException {
        ComponentDefinition fullAdder = componentLibrary(temp).get("FA");
        VhdlExporter.Export exported = VhdlExporter.export(
                fullAdder.internalCircuit(), "FA.lbc");
        printVhdl("FULL_ADDER_GOLDEN", exported.text());

        String expected = resource("vhdl/full_adder.vhd");
        assertEquals(expected, exported.text());
        assertWellFormed(exported.text());
    }

    @Test
    void fullAdderMajorityAndFourBitAdderPassStructuralChecks(@TempDir Path temp) throws IOException {
        Schematic fullAdder = componentLibrary(temp).get("FA").internalCircuit();
        Schematic majority = layout(StandardCircuitGenerators.require("majority")
                .build(Map.of("n", "3")).expressionSource());
        Schematic adder = layout(StandardCircuitGenerators.require("ripple_adder")
                .build(Map.of("n", "4", "carryIn", "yes")).expressionSource());

        for (Schematic schematic : List.of(fullAdder, majority, adder)) {
            String vhdl = VhdlExporter.export(schematic, "checks.lbc").text();
            assertWellFormed(vhdl);
        }
    }

    @Test
    void instanceEmitsItsEntityAndNamedPortMap(@TempDir Path temp) throws IOException {
        ComponentDefinition inner = andDefinition();
        Schematic parent = instanceParent(inner, "Outer");
        String vhdl = VhdlExporter.export(parent, "parent.lbc").text();
        printVhdl("INSTANCE", vhdl);

        assertTrue(vhdl.contains("entity parent is"));
        assertTrue(vhdl.contains("Outer : entity work.Inner"));
        assertTrue(vhdl.contains("entity work.Inner"));
        assertTrue(vhdl.contains("A =>"));
        assertTrue(vhdl.contains("B =>"));
        assertTrue(vhdl.contains("F =>"));
        assertTrue(vhdl.contains("port map ("));
        assertEquals(1, occurrences(vhdl, "entity Inner is"));
        assertEquals(vhdl, VhdlExporter.export(parent, "parent.lbc").text());
    }

    @Test
    void nestedDefinitionsAreEmittedOnceInDependencyOrder(@TempDir Path temp) throws IOException {
        ComponentDefinition leaf = andDefinition();
        Schematic middleBody = new Schematic(DEFINITIONS);
        addPort(middleBody, "A", "input", 0, 0);
        addPort(middleBody, "B", "input", 0, 100);
        addPort(middleBody, "F", "output", 500, 50);
        ComponentInstance leafInstance = middleBody.addInstance(leaf, "leaf_inst", 220, 0, Facing.EAST);
        connect(middleBody, "A", "out", leafInstance.id(), "A");
        connect(middleBody, "B", "out", leafInstance.id(), "B");
        connect(middleBody, leafInstance.id(), "F", "F", "in0");
        ComponentDefinition middle = ComponentDefinition.fromCircuit(
                "Middle", "Nested middle", "Test", null, middleBody);

        Schematic root = instanceParent(middle, "NestedTop");
        String vhdl = VhdlExporter.export(root, "nested.lbc").text();
        printVhdl("NESTED", vhdl);
        int leafAt = vhdl.indexOf("entity Leaf is");
        int middleAt = vhdl.indexOf("entity Middle is");
        int rootAt = vhdl.indexOf("entity NestedTop is");
        assertTrue(leafAt >= 0 && middleAt > leafAt && rootAt > middleAt);
        assertEquals(1, occurrences(vhdl, "entity Leaf is"));
        assertEquals(1, occurrences(vhdl, "entity Middle is"));
        assertEquals(1, occurrences(vhdl, "entity work.Leaf"));
        assertTrue(vhdl.contains("leaf_inst : entity work.Leaf"));
    }

    @Test
    void srLatchHasFeedbackCommentAndEachInternalSignalHasOneDriver() {
        Schematic latch = norLatch();
        String vhdl = VhdlExporter.export(latch, "latch.lbc").text();
        assertTrue(vhdl.contains(
                "-- Contains combinational feedback (latches). Simulates in VHDL simulators; synthesis tools may warn."));
        assertWellFormed(vhdl);
    }

    @Test
    void keywordsAndCaseInsensitiveCollisionsAreRenamedDeterministically() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent first = addPort(schematic, "and", "input", 0, 0);
        PlacedComponent second = addPort(schematic, "AND", "input", 0, 80);
        PlacedComponent gate = addGate(schematic, "and", 200, 40);
        PlacedComponent output = addPort(schematic, "A", "output", 350, 40);
        connect(schematic, first.id(), "out", gate.id(), "in0");
        connect(schematic, second.id(), "out", gate.id(), "in1");
        connect(schematic, gate.id(), "out", output.id(), "in0");

        String firstExport = VhdlExporter.export(schematic, "collision.lbc").text();
        String secondExport = VhdlExporter.export(schematic, "collision.lbc").text();
        assertEquals(firstExport, secondExport);
        assertTrue(firstExport.contains("and_n"));
        assertTrue(firstExport.contains("AND_n_2"));
        assertWellFormed(firstExport);
    }

    @Test
    void combinationalThreeInputTestbenchChecksEveryTruthTableRow(@TempDir Path temp)
            throws IOException {
        Schematic fullAdder = componentLibrary(temp).get("FA").internalCircuit();
        VhdlExporter.Export exported = VhdlExporter.export(fullAdder, "full_adder.lbc");
        assertTrue(exported.testbenchCapability().enabled(), exported.testbenchCapability().reason());
        assertNotNull(exported.testbench());
        assertEquals(8, occurrences(exported.testbench(), "Mismatch for input row"));
        assertTrue(exported.testbench().contains("wait for 10 ns;"));
        assertTrue(exported.testbench().contains("report \"TEST PASSED\" severity note;"));
    }

    @Test
    void clockAndSequentialDefinitionDisableTestbenchWithReason(@TempDir Path temp)
            throws IOException {
        ComponentDefinition dff = componentLibrary(temp).get("DFF");
        Schematic schematic = new Schematic(DEFINITIONS);
        addPort(schematic, "D", "input", 0, 0);
        addPort(schematic, "CLK", "clock", 0, 100);
        addPort(schematic, "Q", "output", 700, 50);
        ComponentInstance instance = schematic.addInstance(
                dff, "register", 350, 0, Facing.EAST);
        connect(schematic, "D", "out", instance.id(), "D");
        connect(schematic, "CLK", "out", instance.id(), "CLK");
        connect(schematic, instance.id(), "Q", "Q", "in0");

        VhdlExporter.TestbenchCapability capability =
                VhdlExporter.testbenchCapability(schematic);
        assertFalse(capability.enabled());
        assertTrue(capability.reason().contains("Clock"));
        assertNull(VhdlExporter.export(schematic, "dff.lbc").testbench());
    }

    private static ComponentLibrary componentLibrary(Path temp) throws IOException {
        return ComponentLibrary.load(DEFINITIONS, temp.resolve("components"));
    }

    private static ComponentDefinition andDefinition() {
        Schematic body = new Schematic(DEFINITIONS);
        addPort(body, "A", "input", 0, 0);
        addPort(body, "B", "input", 0, 100);
        PlacedComponent gate = addGate(body, "and", 200, 50);
        addPort(body, "F", "output", 400, 50);
        connect(body, "A", "out", gate.id(), "in0");
        connect(body, "B", "out", gate.id(), "in1");
        connect(body, gate.id(), "out", "F", "in0");
        return ComponentDefinition.fromCircuit(
                "Inner", "Two input AND", "Test", null, body);
    }

    private static Schematic instanceParent(ComponentDefinition definition, String label) {
        Schematic parent = new Schematic(DEFINITIONS);
        PlacedComponent inputX = addPort(parent, "X", "input", 0, 0);
        PlacedComponent inputY = addPort(parent, "Y", "input", 0, 100);
        PlacedComponent outputZ = addPort(parent, "Z", "output", 650, 50);
        ComponentInstance instance = parent.addInstance(
                definition, label, 300, 0, Facing.EAST);
        connect(parent, inputX.id(), "out", instance.id(), definition.inputPorts().get(0).name());
        connect(parent, inputY.id(), "out", instance.id(), definition.inputPorts().get(1).name());
        connect(parent, instance.id(), definition.outputPorts().get(0).name(), outputZ.id(), "in0");
        return parent;
    }

    private static Schematic norLatch() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent reset = addPort(schematic, "R", "input", 0, 0);
        PlacedComponent set = addPort(schematic, "S", "input", 0, 120);
        PlacedComponent qGate = addGate(schematic, "nor", 180, 40);
        PlacedComponent qBarGate = addGate(schematic, "nor", 180, 160);
        PlacedComponent q = addPort(schematic, "Q", "output", 450, 40);
        PlacedComponent qBar = addPort(schematic, "Qbar", "output", 450, 160);
        connect(schematic, reset.id(), "out", qGate.id(), "in0");
        connect(schematic, set.id(), "out", qBarGate.id(), "in0");
        Connectivity.PinPosition qOut = pin(schematic, qGate.id(), "out");
        Connectivity.PinPosition qBarIn = pin(schematic, qBarGate.id(), "in1");
        path(schematic, qOut.x(), qOut.y(), qOut.x() + 40, qOut.y(),
                qOut.x() + 40, qBarIn.y(), qBarIn.x(), qBarIn.y());
        Connectivity.PinPosition qBarOut = pin(schematic, qBarGate.id(), "out");
        Connectivity.PinPosition qIn = pin(schematic, qGate.id(), "in1");
        path(schematic, qBarOut.x(), qBarOut.y(), qBarOut.x() + 60, qBarOut.y(),
                qBarOut.x() + 60, qIn.y(), qIn.x(), qIn.y());
        connect(schematic, qGate.id(), "out", q.id(), "in0");
        connect(schematic, qBarGate.id(), "out", qBar.id(), "in0");
        return schematic;
    }

    private static Schematic layout(String expression) {
        Netlist netlist = ExpressionCompiler.compile(expression, false).netlist();
        return new SchematicLayout(DEFINITIONS).layout(netlist).schematic();
    }

    private static PlacedComponent addPort(
            Schematic schematic, String name, String type, int x, int y) {
        String id = type + "-" + name + "-" + schematic.components().size();
        PlacedComponent component = new PlacedComponent(
                id, type, x, y, Facing.EAST, Map.of(), name);
        schematic.add(component);
        return component;
    }

    private static PlacedComponent addGate(
            Schematic schematic, String type, int x, int y) {
        String id = "gate-" + schematic.components().size();
        PlacedComponent component = new PlacedComponent(
                id, type, x, y, Facing.EAST);
        schematic.add(component);
        return component;
    }

    private static void connect(
            Schematic schematic, String sourceIdOrLabel, String sourcePin,
            String targetIdOrLabel, String targetPin) {
        String sourceComponentId = componentId(schematic, sourceIdOrLabel);
        String targetComponentId = componentId(schematic, targetIdOrLabel);
        Connectivity.PinPosition source = pin(schematic, sourceComponentId, sourcePin);
        Connectivity.PinPosition target = pin(schematic, targetComponentId, targetPin);
        schematic.addWire(source.x(), source.y(), target.x(), source.y());
        schematic.addWire(target.x(), source.y(), target.x(), target.y());
    }

    private static void path(Schematic schematic, int... points) {
        for (int index = 0; index + 3 < points.length; index += 2) {
            schematic.addWire(points[index], points[index + 1],
                    points[index + 2], points[index + 3]);
        }
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentIdOrLabel, String pinId) {
        String componentId = componentId(schematic, componentIdOrLabel);
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(componentId)
                        && position.pinId().equals(pinId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No pin " + pinId + " on schematic component " + componentIdOrLabel
                                + " (resolved id " + componentId + ")"));
    }

    private static String componentId(Schematic schematic, String idOrLabel) {
        for (PlacedComponent component : schematic.components()) {
            if (component.id().equals(idOrLabel)) {
                return component.id();
            }
        }
        for (ComponentInstance instance : schematic.componentInstances()) {
            if (instance.id().equals(idOrLabel)) {
                return instance.id();
            }
        }

        List<String> matches = new ArrayList<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.label().equals(idOrLabel)) {
                matches.add(component.id());
            }
        }
        for (ComponentInstance instance : schematic.componentInstances()) {
            if (instance.label().equals(idOrLabel)) {
                matches.add(instance.id());
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "No schematic component has id or label: " + idOrLabel);
        }
        throw new IllegalArgumentException(
                "Ambiguous schematic component label '" + idOrLabel + "': " + matches);
    }

    private static void printVhdl(String label, String vhdl) {
        System.out.println("VHDL_DIAGNOSTIC_" + label + "_BEGIN");
        System.out.print(vhdl);
        System.out.println("VHDL_DIAGNOSTIC_" + label + "_END");
    }

    private static String resource(String name) throws IOException {
        try (var stream = VhdlExporterTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }

    private static void assertWellFormed(String vhdl) {
        assertEquals(occurrences(vhdl, "entity ") - occurrences(vhdl, "entity work.")
                - occurrences(vhdl, "end entity "), occurrences(vhdl, "end entity "));
        assertEquals(occurrences(vhdl, "architecture structural of "),
                occurrences(vhdl, "end architecture structural;"));
        assertTrue(vhdl.endsWith(System.lineSeparator()));

        Set<String> declared = new java.util.LinkedHashSet<>();
        Matcher signalMatcher = Pattern.compile("(?m)^\\s*signal ([A-Za-z][A-Za-z0-9_]*) : std_logic;$")
                .matcher(vhdl);
        while (signalMatcher.find()) {
            String name = signalMatcher.group(1);
            assertValidIdentifier(name);
            assertTrue(declared.add(name.toLowerCase(java.util.Locale.ROOT)),
                    "duplicate signal " + name);
        }
        for (String name : declared) {
            Pattern driver = Pattern.compile("(?m)^\\s*" + Pattern.quote(name)
                    + " <= ");
            assertEquals(1, occurrences(driver, vhdl),
                    "signal must have exactly one concurrent assignment: " + name);
        }
        Matcher idMatcher = Pattern.compile("(?m)^\\s*(?:signal )?([A-Za-z][A-Za-z0-9_]*)\\s*(?::|<=|: entity work\\.)")
                .matcher(vhdl);
        while (idMatcher.find()) {
            assertValidIdentifier(idMatcher.group(1));
        }
    }

    private static int occurrences(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) count++;
        return count;
    }

    private static void assertValidIdentifier(String identifier) {
        assertTrue(identifier.matches("[A-Za-z][A-Za-z0-9_]*"), identifier);
        assertFalse(identifier.endsWith("_"), identifier);
        assertFalse(identifier.contains("__"), identifier);
        assertFalse(List.of("abs", "and", "architecture", "buffer", "component",
                "end", "entity", "in", "inout", "is", "map", "nand", "nor", "not",
                "of", "or", "out", "port", "process", "register", "signal", "wait",
                "xnor", "xor", "library", "use", "all", "others", "to", "downto",
                "bit", "when", "else", "if", "then").contains(
                        identifier.toLowerCase(java.util.Locale.ROOT)), identifier);
    }
}
