package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SequenceRunner;
import io.github.evilalee.gatelab.synth.SequentialParts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void builtInDefinitionsExposeTheSpecifiedOrderedPortsAndPrimaryOutputs(@TempDir Path userDirectory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition latch = library.get("DLATCH");
        ComponentDefinition dff = library.get("DFF");
        ComponentDefinition tff = library.get("TFF");
        ComponentDefinition jkff = library.get("JKFF");
        ComponentDefinition ha = library.get("HA");
        ComponentDefinition fa = library.get("FA");

        System.out.println("COMPONENT_LIBRARY_PORTS: DLATCH=" + portNames(latch)
                + " DFF=" + portNames(dff) + " TFF=" + portNames(tff)
                + " JKFF=" + portNames(jkff) + " HA=" + portNames(ha)
                + " FA=" + portNames(fa));

        assertEquals(List.of("D", "EN", "Q", "QN"), portNames(latch));
        assertEquals(List.of("D", "CLK", "Q", "QN"), portNames(dff));
        assertEquals(List.of("T", "CLK", "Q", "QN"), portNames(tff));
        assertEquals(List.of("J", "K", "CLK", "Q", "QN"), portNames(jkff));
        assertEquals(List.of("A", "B", "S", "C"), portNames(ha));
        assertEquals(List.of("A", "B", "CIN", "S", "COUT"), portNames(fa));

        for (ComponentDefinition sequential : List.of(latch, dff, tff, jkff)) {
            assertEquals("Q", sequential.primaryOutput());
        }
        assertTrue(dff.ports().stream().anyMatch(port ->
                port.name().equals("CLK") && port.isClock()
                        && port.direction() == ComponentDefinition.Direction.IN));
        assertTrue(tff.ports().stream().anyMatch(port ->
                port.name().equals("CLK") && port.isClock()));
        assertTrue(jkff.ports().stream().anyMatch(port ->
                port.name().equals("CLK") && port.isClock()));
        assertFalse(latch.ports().stream().anyMatch(ComponentDefinition.Port::isClock));
        assertNull(ha.primaryOutput());
        assertNull(fa.primaryOutput());
        assertTrue(library.builtInNames().containsAll(
                List.of("DLATCH", "DFF", "TFF", "JKFF", "HA", "FA")));

        for (ComponentDefinition definition : library.builtIns()) {
            assertTrue(definition.internalCircuit().components().size() > 0,
                    definition.name() + " must be generated as an ordinary schematic");
        }
    }

    @Test
    void userDefinitionLibrarySaveAndLoadRoundTripsJson(@TempDir Path userDirectory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition definition = passThroughDefinition();

        Path saved = library.save(definition);

        assertEquals("PassThrough" + ".lbcomp.json", saved.getFileName().toString());
        assertTrue(Files.isRegularFile(saved));
        assertTrue(Files.readString(saved).contains("\"internalCircuit\""));

        ComponentLibrary reloaded = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition loaded = reloaded.get("PassThrough");
        assertEquals(definition.hash(), loaded.hash());
        assertEquals(definition.ports(), loaded.ports());
        assertEquals(definition.internalCircuit().components(),
                loaded.internalCircuit().components());
    }

    @Test
    void userDefinitionsCannotOverwriteBuiltInNames(@TempDir Path userDirectory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition builtin = library.get("HA");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> library.save(builtin));
        assertTrue(failure.getMessage().contains("built-in"));
    }

    @Test
    void twoBitDffCounterMatchesSequentialPartsAfterSynchronousReset(@TempDir Path userDirectory)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, userDirectory);
        ComponentDefinition dff = library.get("DFF");
        Schematic internalDff = dff.internalCircuit();
        SchematicCompiler.CompiledCircuit internalCompiled =
                SchematicCompiler.compileCircuit(internalDff);
        Map<String, InputPin> internalInputs =
                namedInputs(internalDff, internalCompiled);
        Map<String, OutputProbe> internalOutputs =
                namedOutputs(internalDff, internalCompiled);
        SequenceRunner internalRunner = new SequenceRunner(
                internalCompiled.circuit(), internalInputs, "CLK", internalOutputs);
        SequenceRunner.SequenceResult internalRun = internalRunner.run(List.of(
                new SequenceRunner.Step("capture high", Map.of("D", Value.ONE), 1,
                        Map.of("Q", Value.ONE, "QN", Value.ZERO)),
                new SequenceRunner.Step("capture low", Map.of("D", Value.ZERO), 1,
                        Map.of("Q", Value.ZERO, "QN", Value.ONE))));
        System.out.println("DFF_INTERNAL_DIAGNOSTIC: components="
                + internalDff.components().stream()
                        .map(c -> c.id() + ":" + c.typeId() + ":" + c.label()).toList()
                + " clockIds=" + internalCompiled.clockIds()
                + " outputs=" + internalRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList()
                + " mismatch=" + internalRun.firstMismatch());

        Schematic schematic = counterSchematic(dff);
        List<List<String>> parentNetPins = Connectivity.analyze(
                        schematic.pinPositions(), schematic.wires()).nets().stream()
                .filter(net -> !net.pins().isEmpty())
                .map(net -> net.pins().stream()
                        .map(pin -> pin.componentId() + "." + pin.pinId())
                        .sorted().toList())
                .toList();
        System.out.println("COUNTER_PARENT_NETS: " + parentNetPins);

        SchematicCompiler.CompiledCircuit flattened =
                SchematicCompiler.compileCircuit(schematic);
        SequenceRunner schematicRunner = new SequenceRunner(
                flattened.circuit(),
                namedInputs(schematic, flattened),
                "CLK",
                namedOutputs(schematic, flattened));
        SequenceRunner.SequenceResult flattenedRun = schematicRunner.run(counterSteps());

        SequenceRunner referenceRunner = referenceCounterRunner();
        SequenceRunner.SequenceResult referenceRun = referenceRunner.run(counterSteps());

        System.out.println("COMPONENT_LIBRARY_COUNTER: flattened="
                + flattenedRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList()
                + " firstMismatch=" + flattenedRun.firstMismatch()
                + " reference=" + referenceRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList()
                + " referenceMismatch=" + referenceRun.firstMismatch());

        assertTrue(flattenedRun.passed(), () -> flattenedRun.firstMismatch().toString());
        assertTrue(referenceRun.passed(), () -> referenceRun.firstMismatch().toString());
        assertEquals(referenceRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList(),
                flattenedRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList());
        assertEquals(List.of(
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO),
                        Map.of("Q0", Value.ONE, "Q1", Value.ZERO),
                        Map.of("Q0", Value.ZERO, "Q1", Value.ONE),
                        Map.of("Q0", Value.ONE, "Q1", Value.ONE),
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO)),
                flattenedRun.steps().stream()
                        .map(SequenceRunner.StepObservation::observedOutputs).toList());
    }

    private static List<String> portNames(ComponentDefinition definition) {
        return definition.ports().stream().map(ComponentDefinition.Port::name).toList();
    }

    private static ComponentDefinition passThroughDefinition() {
        Schematic internal = new Schematic(GATES);
        PlacedComponent input = new PlacedComponent(
                "in-A", "input", 100, 100, Facing.EAST, Map.of(), "A");
        internal.add(input);
        Connectivity.PinPosition inputPin = pin(internal, input.id(), "out");
        GateDef outputDefinition = gateDefinition("output");
        GateGeometry.Offset offset =
                GateGeometry.inputOffsets(outputDefinition, Facing.EAST).getFirst();
        PlacedComponent output = new PlacedComponent(
                "out-Y", "output", 300 - offset.x(), inputPin.y() - offset.y(),
                Facing.EAST, Map.of(), "Y");
        internal.add(output);
        Connectivity.PinPosition outputPin = pin(internal, output.id(), "in0");
        internal.addWire(new WireSegment(
                inputPin.x(), inputPin.y(), outputPin.x(), outputPin.y()));
        return ComponentDefinition.fromCircuit(
                "PassThrough", "Direct pass-through", "Test", null, internal);
    }

    private static Schematic counterSchematic(ComponentDefinition dff) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance lowBit = schematic.addInstance(
                dff, "U1", 400, 200, Facing.EAST);
        ComponentInstance highBit = schematic.addInstance(
                dff, "U2", 1000, 200, Facing.EAST);

        schematic.add(new PlacedComponent(
                "reset", "input", 0, 400, Facing.EAST, Map.of(), "RESET"));
        schematic.add(new PlacedComponent(
                "clock", "clock", 0, 600, Facing.EAST, Map.of(), "CLK"));

        schematic.add(new PlacedComponent("reset-not", "not", 200, 400, Facing.EAST));
        schematic.add(new PlacedComponent("q0-not", "not", 700, 400, Facing.EAST));
        schematic.add(new PlacedComponent("count-xor", "xor", 1300, 400, Facing.EAST));
        schematic.add(new PlacedComponent("d0-and", "and", 300, 700, Facing.EAST));
        schematic.add(new PlacedComponent("d1-and", "and", 1000, 700, Facing.EAST));

        addOutputAt(schematic, "q0-probe", "Q0", 1800, 220);
        addOutputAt(schematic, "q1-probe", "Q1", 1800, 240);

        int route = 0;
        route(schematic, pin(schematic, "reset", "out"),
                pin(schematic, "reset-not", "in0"), route++);
        route(schematic, pin(schematic, "reset-not", "out"),
                pin(schematic, "d0-and", "in1"), route++);
        route(schematic, pin(schematic, "reset-not", "out"),
                pin(schematic, "d1-and", "in1"), route++);

        ComponentInstance.PinGeometry q0 = port(lowBit, dff, "Q");
        ComponentInstance.PinGeometry d0 = port(lowBit, dff, "D");
        ComponentInstance.PinGeometry clk0 = port(lowBit, dff, "CLK");
        ComponentInstance.PinGeometry q1 = port(highBit, dff, "Q");
        ComponentInstance.PinGeometry d1 = port(highBit, dff, "D");
        ComponentInstance.PinGeometry clk1 = port(highBit, dff, "CLK");

        Connectivity.PinPosition q0Pin = new Connectivity.PinPosition(
                lowBit.id(), "Q", q0.x(), q0.y());
        Connectivity.PinPosition d0Pin = new Connectivity.PinPosition(
                lowBit.id(), "D", d0.x(), d0.y());
        Connectivity.PinPosition clk0Pin = new Connectivity.PinPosition(
                lowBit.id(), "CLK", clk0.x(), clk0.y());
        Connectivity.PinPosition q1Pin = new Connectivity.PinPosition(
                highBit.id(), "Q", q1.x(), q1.y());
        Connectivity.PinPosition d1Pin = new Connectivity.PinPosition(
                highBit.id(), "D", d1.x(), d1.y());
        Connectivity.PinPosition clk1Pin = new Connectivity.PinPosition(
                highBit.id(), "CLK", clk1.x(), clk1.y());

        route(schematic, q0Pin, pin(schematic, "q0-not", "in0"), route++);
        route(schematic, q0Pin, pin(schematic, "count-xor", "in0"), route++);
        route(schematic, q0Pin, pin(schematic, "q0-probe", "in0"), route++);
        route(schematic, pin(schematic, "q0-not", "out"),
                pin(schematic, "d0-and", "in0"), route++);
        route(schematic, q1Pin, pin(schematic, "count-xor", "in1"), route++);
        route(schematic, q1Pin, pin(schematic, "q1-probe", "in0"), route++);
        route(schematic, pin(schematic, "count-xor", "out"),
                pin(schematic, "d1-and", "in0"), route++);
        route(schematic, pin(schematic, "d0-and", "out"), d0Pin, route++);
        route(schematic, pin(schematic, "d1-and", "out"), d1Pin, route++);
        route(schematic, pin(schematic, "clock", "out"), clk0Pin, route++);
        route(schematic, pin(schematic, "clock", "out"), clk1Pin, route);
        return schematic;
    }

    private static List<SequenceRunner.Step> counterSteps() {
        return List.of(
                new SequenceRunner.Step("synchronous reset",
                        Map.of("RESET", Value.ONE), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO)),
                new SequenceRunner.Step("00 to 01",
                        Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ONE, "Q1", Value.ZERO)),
                new SequenceRunner.Step("01 to 10",
                        Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ONE)),
                new SequenceRunner.Step("10 to 11",
                        Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ONE, "Q1", Value.ONE)),
                new SequenceRunner.Step("11 to 00",
                        Map.of("RESET", Value.ZERO), 1,
                        Map.of("Q0", Value.ZERO, "Q1", Value.ZERO)));
    }

    private static SequenceRunner referenceCounterRunner() {
        Circuit circuit = new Circuit();
        InputPin reset = new InputPin("RESET");
        InputPin clock = new InputPin("CLK");
        circuit.addComponent(reset);
        circuit.addComponent(clock);

        Gate resetNot = addGate(circuit, "reset-not", GateType.NOT, 1);
        Gate q0Not = addGate(circuit, "q0-not", GateType.NOT, 1);
        Gate countXor = addGate(circuit, "count-xor", GateType.XOR, 2);
        Gate d0And = addGate(circuit, "d0-and", GateType.AND, 2);
        Gate d1And = addGate(circuit, "d1-and", GateType.AND, 2);
        circuit.connect(reset.output(), resetNot.input(0));

        SequentialParts.Outputs low = SequentialParts.dff(
                circuit, "REF_LOW", d0And.output(), clock.output());
        SequentialParts.Outputs high = SequentialParts.dff(
                circuit, "REF_HIGH", d1And.output(), clock.output());

        circuit.connect(low.q(), q0Not.input(0));
        circuit.connect(low.q(), countXor.input(0));
        circuit.connect(high.q(), countXor.input(1));
        circuit.connect(q0Not.output(), d0And.input(0));
        circuit.connect(resetNot.output(), d0And.input(1));
        circuit.connect(countXor.output(), d1And.input(0));
        circuit.connect(resetNot.output(), d1And.input(1));

        OutputProbe q0 = new OutputProbe("Q0");
        OutputProbe q1 = new OutputProbe("Q1");
        circuit.addComponent(q0);
        circuit.addComponent(q1);
        circuit.connect(low.q(), q0.input());
        circuit.connect(high.q(), q1.input());
        return new SequenceRunner(
                circuit, Map.of("RESET", reset, "CLK", clock), "CLK",
                Map.of("Q0", q0, "Q1", q1));
    }

    private static Gate addGate(Circuit circuit, String id, GateType type, int count) {
        Gate gate = new Gate(id, type, count);
        circuit.addComponent(gate);
        return gate;
    }

    private static Map<String, InputPin> namedInputs(
            Schematic schematic, SchematicCompiler.CompiledCircuit compiled) {
        Map<String, InputPin> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("input") || component.typeId().equals("clock")) {
                result.put(component.label(), compiled.inputs().get(component.id()));
            }
        }
        return result;
    }

    private static Map<String, OutputProbe> namedOutputs(
            Schematic schematic, SchematicCompiler.CompiledCircuit compiled) {
        Map<String, OutputProbe> result = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("output")) {
                result.put(component.label(), compiled.outputs().get(component.id()));
            }
        }
        return result;
    }

    private static void route(
            Schematic schematic,
            Connectivity.PinPosition start,
            Connectivity.PinPosition end,
            int index) {
        int laneY = 1200 + index * 20;
        // Give every source/target stub its own X lane; pins sharing a body edge
        // (for example D and CLK) must not share an overlapping vertical trunk.
        int startX = start.x() + (index + 1) * 10;
        int endX = end.x() - (index + 1) * 10;
        addWire(schematic, start.x(), start.y(), startX, start.y());
        addWire(schematic, startX, start.y(), startX, laneY);
        addWire(schematic, startX, laneY, endX, laneY);
        addWire(schematic, endX, laneY, endX, end.y());
        addWire(schematic, endX, end.y(), end.x(), end.y());
    }

    private static void addWire(
            Schematic schematic, int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) {
            return;
        }
        schematic.addWire(new WireSegment(x1, y1, x2, y2));
    }

    private static void addOutputAt(
            Schematic schematic, String id, String label, int pinX, int pinY) {
        GateGeometry.Offset input = GateGeometry.inputOffsets(
                gateDefinition("output"), Facing.EAST).getFirst();
        schematic.add(new PlacedComponent(
                id, "output", pinX - input.x(), pinY - input.y(),
                Facing.EAST, Map.of(), label));
    }

    private static ComponentInstance.PinGeometry port(
            ComponentInstance instance, ComponentDefinition definition, String name) {
        return instance.geometry(definition).pins().stream()
                .filter(pin -> pin.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(componentId)
                        && position.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static GateDef gateDefinition(String family) {
        return GATES.stream()
                .filter(definition -> definition.family().equals(family)
                        && definition.size().equals("narrow"))
                .findFirst().orElseThrow();
    }
}
