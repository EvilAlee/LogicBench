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
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.CircuitDescriber.Description;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class BoxPinConnectivityRegressionTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @TempDir
    Path directory;

    private record Fixture(
            String name,
            Schematic schematic,
            ComponentInstance latch,
            List<ComponentInstance> adders,
            PlacedComponent d,
            PlacedComponent en,
            List<PlacedComponent> carries) {
    }

    @Test
    void reproduceLatchToAdderBoxPinsAndReportAllThreeConnectivityViews() throws Exception {
        ComponentLibrary library = ComponentLibrary.load(GATES, directory);
        List<Fixture> fixtures = List.of(
                circuitA(library),
                circuitB(library));

        for (Fixture fixture : fixtures) {
            System.out.println("\n===== BOX PIN DIAGNOSTIC " + fixture.name() + " =====");
            SchematicCompiler.SimulationResult settled = exerciseLatch(fixture);
            reportTopLevelPins(fixture, settled);
            reportTemporaryProjection(fixture);
            reportAdderValues(fixture, settled);
            assertLatchAndAdderValues(fixture, settled);

            Description description = CircuitDescriber.describe(fixture.schematic());
            System.out.println("CircuitDescriber: SUCCESS\n" + description.text());
            ComponentExpressionCompiler.Compilation rebuilt =
                    ComponentExpressionCompiler.compile(description.text(), GATES, library.all());
            SchematicCompiler.SimulationResult roundTrip =
                    exerciseLatchWithoutReport(rebuilt.schematic());
            assertEquals(outputValuesByLabel(fixture.schematic(), settled),
                    outputValuesByLabel(rebuilt.schematic(), roundTrip),
                    fixture.name() + " CircuitDescriber round trip");
        }
    }

    @Test
    void rotatedFullAdderBoxPinsRemainConnectedInAllFourFacings() throws IOException {
        ComponentDefinition definition = ComponentLibrary.load(GATES, directory).get("FA");
        GateDef inputDefinition = GATES.stream()
                .filter(gate -> gate.family().equals("input") && gate.size().equals("narrow"))
                .findFirst().orElseThrow();
        int index = 0;
        for (Facing facing : List.of(Facing.EAST, Facing.SOUTH, Facing.WEST, Facing.NORTH)) {
            Schematic schematic = new Schematic(GATES);
            ComponentInstance adder = schematic.addInstance(
                    definition, "FA_" + facing, 300 + index * 400, 300, facing);
            int normalX = switch (facing) {
                case EAST -> 1;
                case WEST -> -1;
                case NORTH, SOUTH -> 0;
            };
            int normalY = switch (facing) {
                case SOUTH -> 1;
                case NORTH -> -1;
                case EAST, WEST -> 0;
            };

            for (ComponentDefinition.Port port : definition.inputPorts()) {
                ComponentInstance.PinGeometry boxPin = adder.geometry(definition).pins().stream()
                        .filter(pin -> pin.name().equals(port.name())).findFirst().orElseThrow();
                int sourceX = boxPin.x() - normalX * 40;
                int sourceY = boxPin.y() - normalY * 40;
                PlacedComponent source = new PlacedComponent(
                        port.name() + "_" + facing, "input", sourceX, sourceY,
                        Facing.EAST, Map.of(), port.name());
                schematic.add(source);
                schematic.setInputValue(source.id(), switch (port.name()) {
                    case "A", "CIN" -> true;
                    case "B" -> false;
                    default -> throw new IllegalArgumentException(port.name());
                });
                schematic.addWire(sourceX, sourceY, boxPin.x(), boxPin.y());
            }

            Map<String, PlacedComponent> probes = new LinkedHashMap<>();
            for (ComponentDefinition.Port port : definition.outputPorts()) {
                ComponentInstance.PinGeometry boxPin = adder.geometry(definition).pins().stream()
                        .filter(pin -> pin.name().equals(port.name())).findFirst().orElseThrow();
                int probeX = boxPin.x() + normalX * 40;
                int probeY = boxPin.y() + normalY * 40;
                PlacedComponent probe = new PlacedComponent(
                        "OUT_" + port.name() + "_" + facing, "output", probeX, probeY,
                        Facing.EAST, Map.of(), port.name());
                schematic.add(probe);
                schematic.addWire(boxPin.x(), boxPin.y(), probeX, probeY);
                probes.put(port.name(), probe);
            }

            Connectivity.Result topology = Connectivity.analyze(
                    schematic.pinPositions(), schematic.wires());
            for (ComponentDefinition.Port port : definition.ports()) {
                ComponentInstance.PinGeometry geometry = adder.geometry(definition).pins().stream()
                        .filter(pin -> pin.name().equals(port.name())).findFirst().orElseThrow();
                Connectivity.PinPosition boxPin = new Connectivity.PinPosition(
                        adder.id(), geometry.name(), geometry.x(), geometry.y());
                Connectivity.PinPosition paired = port.direction() == ComponentDefinition.Direction.IN
                        ? pin(schematic, port.name() + "_" + facing, "out")
                        : pin(schematic, probes.get(port.name()).id(), "in0");
                assertEquals(netId(topology, paired), netId(topology, boxPin),
                        facing + " " + port.name() + " endpoint geometry");
            }

            SchematicCompiler.SimulationResult result =
                    SchematicCompiler.compileAndSettle(schematic);
            assertFalse(result.oscillating(), facing + " did not settle");
            assertEquals(Value.ZERO, result.outputValues().get(probes.get("S").id()),
                    facing + " sum");
            assertEquals(Value.ONE, result.outputValues().get(probes.get("COUT").id()),
                    facing + " carry");
            index++;
        }
    }

    private static Fixture circuitA(ComponentLibrary library) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance latch = schematic.addInstance(
                library.get("DLATCH"), "Latch", 200, 100, Facing.EAST);
        ComponentInstance adder = schematic.addInstance(
                library.get("FA"), "FA1", 500, 200, Facing.EAST);

        PlacedComponent d = addInput(schematic, "D", 20, 20, false);
        PlacedComponent en = addInput(schematic, "EN", 20, 260, false);
        PlacedComponent cin = addInput(schematic, "CIN", 450, 500, false);
        PlacedComponent sum = addOutput(schematic, "SUM", 700, 180);
        PlacedComponent carry = addOutput(schematic, "COUT", 700, 300);

        connectL(schematic, pin(schematic, d.id(), "out"),
                pin(schematic, latch.id(), "D"), true);
        connectL(schematic, pin(schematic, en.id(), "out"),
                pin(schematic, latch.id(), "EN"), false);
        connectL(schematic, pin(schematic, latch.id(), "Q"),
                pin(schematic, adder.id(), "A"), true);
        connectL(schematic, pin(schematic, latch.id(), "QN"),
                pin(schematic, adder.id(), "B"), false);
        connectL(schematic, pin(schematic, cin.id(), "out"),
                pin(schematic, adder.id(), "CIN"), true);
        connectL(schematic, pin(schematic, adder.id(), "S"),
                pin(schematic, sum.id(), "in0"), true);
        connectL(schematic, pin(schematic, adder.id(), "COUT"),
                pin(schematic, carry.id(), "in0"), false);
        return new Fixture("Circuit A", schematic, latch, List.of(adder), d, en, List.of(cin));
    }

    private static Fixture circuitB(ComponentLibrary library) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance latch = schematic.addInstance(
                library.get("DLATCH"), "Latch", 200, 100, Facing.EAST);
        ComponentInstance first = schematic.addInstance(
                library.get("FA"), "FA1", 500, 300, Facing.EAST);
        ComponentInstance second = schematic.addInstance(
                library.get("FA"), "FA2", 760, 500, Facing.EAST);

        PlacedComponent d = addInput(schematic, "D", 20, 20, false);
        PlacedComponent en = addInput(schematic, "EN", 20, 260, false);
        PlacedComponent cin1 = addInput(schematic, "CIN1", 450, 400, false);
        PlacedComponent cin2 = addInput(schematic, "CIN2", 710, 620, false);
        PlacedComponent sum1 = addOutput(schematic, "SUM1", 680, 280);
        PlacedComponent carry1 = addOutput(schematic, "COUT1", 680, 390);
        PlacedComponent sum2 = addOutput(schematic, "SUM2", 940, 480);
        PlacedComponent carry2 = addOutput(schematic, "COUT2", 940, 590);

        connectL(schematic, pin(schematic, d.id(), "out"),
                pin(schematic, latch.id(), "D"), true);
        connectL(schematic, pin(schematic, en.id(), "out"),
                pin(schematic, latch.id(), "EN"), false);

        // Connect each first adder pin, then branch from the shared wire just as CanvasPanel does.
        Connectivity.PinPosition q = pin(schematic, latch.id(), "Q");
        Connectivity.PinPosition qn = pin(schematic, latch.id(), "QN");
        Connectivity.PinPosition firstA = pin(schematic, first.id(), "A");
        Connectivity.PinPosition firstB = pin(schematic, first.id(), "B");
        Connectivity.PinPosition secondA = pin(schematic, second.id(), "A");
        Connectivity.PinPosition secondB = pin(schematic, second.id(), "B");
        connectL(schematic, q, firstA, true);
        connectL(schematic, qn, firstB, false);
        connectLFromWireBranch(schematic, new Connectivity.Point(400, q.y()),
                secondB, false);
        connectLFromWireBranch(schematic, new Connectivity.Point(440, firstB.y()),
                secondA, false);

        connectL(schematic, pin(schematic, cin1.id(), "out"),
                pin(schematic, first.id(), "CIN"), true);
        connectL(schematic, pin(schematic, cin2.id(), "out"),
                pin(schematic, second.id(), "CIN"), true);

        connectL(schematic, pin(schematic, first.id(), "S"),
                pin(schematic, sum1.id(), "in0"), true);
        connectL(schematic, pin(schematic, first.id(), "COUT"),
                pin(schematic, carry1.id(), "in0"), false);
        connectL(schematic, pin(schematic, second.id(), "S"),
                pin(schematic, sum2.id(), "in0"), true);
        connectL(schematic, pin(schematic, second.id(), "COUT"),
                pin(schematic, carry2.id(), "in0"), false);
        return new Fixture("Circuit B", schematic, latch, List.of(first, second),
                d, en, List.of(cin1, cin2));
    }

    private static PlacedComponent addInput(
            Schematic schematic, String id, int x, int y, boolean high) {
        PlacedComponent input = new PlacedComponent(
                id, "input", x, y, Facing.EAST, Map.of(), id);
        schematic.add(input);
        schematic.setInputValue(id, high);
        return input;
    }

    private static PlacedComponent addOutput(
            Schematic schematic, String id, int x, int y) {
        PlacedComponent output = new PlacedComponent(
                id, "output", x, y, Facing.EAST, Map.of(), id);
        schematic.add(output);
        return output;
    }

    private static void connectL(
            Schematic schematic, Connectivity.PinPosition start,
            Connectivity.PinPosition end, boolean horizontalFirst) {
        connectLFromWireBranch(schematic,
                new Connectivity.Point(start.x(), start.y()), end, horizontalFirst);
    }

    /** Matches CanvasPanel's two-segment preview, including starts in a wire interior. */
    private static void connectLFromWireBranch(
            Schematic schematic, Connectivity.Point start,
            Connectivity.PinPosition end, boolean horizontalFirst) {
        int cornerX = horizontalFirst ? end.x() : start.x();
        int cornerY = horizontalFirst ? start.y() : end.y();
        addOrthogonalSegment(schematic, start.x(), start.y(), cornerX, cornerY);
        addOrthogonalSegment(schematic, cornerX, cornerY, end.x(), end.y());
    }

    private static void addOrthogonalSegment(
            Schematic schematic, int x1, int y1, int x2, int y2) {
        if (x1 == x2 && y1 == y2) return;
        if (x1 != x2 && y1 != y2) {
            throw new IllegalArgumentException("diagnostic route segment is not orthogonal");
        }
        schematic.addWire(x1, y1, x2, y2);
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(candidate -> candidate.componentId().equals(componentId)
                        && candidate.pinId().equals(pinId))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("missing pin " + componentId + "." + pinId));
    }

    private static SchematicCompiler.SimulationResult exerciseLatch(Fixture fixture) {
        Schematic schematic = fixture.schematic();
        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic);
        reportLatchState(fixture, "initial EN=0, D=0", result);

        schematic.setInputValue(fixture.en().id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        reportLatchState(fixture, "load D=0, EN=1", result);

        schematic.setInputValue(fixture.d().id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        reportLatchState(fixture, "load D=1, EN=1", result);

        schematic.setInputValue(fixture.en().id(), false);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        reportLatchState(fixture, "hold D=1, EN=0", result);
        return result;
    }

    private static void assertLatchAndAdderValues(
            Fixture fixture, SchematicCompiler.SimulationResult result) {
        assertFalse(result.oscillating(), fixture.name() + " did not settle within tick budget");
        assertEquals(Value.ONE,
                valueAt(result, pin(fixture.schematic(), fixture.latch().id(), "Q")));
        assertEquals(Value.ZERO,
                valueAt(result, pin(fixture.schematic(), fixture.latch().id(), "QN")));
        for (int index = 0; index < fixture.adders().size(); index++) {
            ComponentInstance adder = fixture.adders().get(index);
            Value expectedA = index == 0 ? Value.ONE : Value.ZERO;
            Value expectedB = index == 0 ? Value.ZERO : Value.ONE;
            assertEquals(expectedA, valueAt(result,
                    pin(fixture.schematic(), adder.id(), "A")), adder.id() + ".A");
            assertEquals(expectedB, valueAt(result,
                    pin(fixture.schematic(), adder.id(), "B")), adder.id() + ".B");
            assertEquals(Value.ZERO, valueAt(result,
                    pin(fixture.schematic(), adder.id(), "CIN")), adder.id() + ".CIN");
            assertEquals(Value.ONE, valueAt(result,
                    pin(fixture.schematic(), adder.id(), "S")), adder.id() + ".S");
            assertEquals(Value.ZERO, valueAt(result,
                    pin(fixture.schematic(), adder.id(), "COUT")), adder.id() + ".COUT");
        }

        if (fixture.adders().size() == 2) {
            Connectivity.Result topology = Connectivity.analyze(
                    fixture.schematic().pinPositions(), fixture.schematic().wires());
            assertEquals(netId(topology, pin(fixture.schematic(),
                            fixture.latch().id(), "Q")),
                    netId(topology, pin(fixture.schematic(),
                            fixture.adders().get(0).id(), "A")));
            assertEquals(netId(topology, pin(fixture.schematic(),
                            fixture.latch().id(), "Q")),
                    netId(topology, pin(fixture.schematic(),
                            fixture.adders().get(1).id(), "B")));
            assertEquals(netId(topology, pin(fixture.schematic(),
                            fixture.latch().id(), "QN")),
                    netId(topology, pin(fixture.schematic(),
                            fixture.adders().get(0).id(), "B")));
            assertEquals(netId(topology, pin(fixture.schematic(),
                            fixture.latch().id(), "QN")),
                    netId(topology, pin(fixture.schematic(),
                            fixture.adders().get(1).id(), "A")));
        }
    }

    private static SchematicCompiler.SimulationResult exerciseLatchWithoutReport(
            Schematic schematic) {
        PlacedComponent d = inputByLabel(schematic, "D");
        PlacedComponent en = inputByLabel(schematic, "EN");
        SchematicCompiler.SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        schematic.setInputValue(en.id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        schematic.setInputValue(d.id(), true);
        result = SchematicCompiler.compileAndSettle(schematic, result);
        schematic.setInputValue(en.id(), false);
        return SchematicCompiler.compileAndSettle(schematic, result);
    }

    private static PlacedComponent inputByLabel(Schematic schematic, String label) {
        return schematic.components().stream()
                .filter(component -> component.typeId().equals("input")
                        && component.label().equals(label))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("missing input label " + label));
    }

    private static Map<String, Value> outputValuesByLabel(
            Schematic schematic, SchematicCompiler.SimulationResult result) {
        Map<String, Value> values = new LinkedHashMap<>();
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("output")) {
                values.put(component.label(), result.outputValues().get(component.id()));
            }
        }
        return values;
    }

    private static void reportLatchState(
            Fixture fixture, String stage, SchematicCompiler.SimulationResult result) {
        Value q = valueAt(result, pin(fixture.schematic(), fixture.latch().id(), "Q"));
        Value qn = valueAt(result, pin(fixture.schematic(), fixture.latch().id(), "QN"));
        System.out.println(stage + ": latch Q=" + q + ", QN=" + qn
                + ", didNotSettle=" + result.oscillating()
                + ", tickBudgetHit=" + result.oscillating());
    }

    private static void reportTopLevelPins(
            Fixture fixture, SchematicCompiler.SimulationResult result) {
        Schematic schematic = fixture.schematic();
        Connectivity.Result connectivity = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        System.out.println("Top-level pin nets (flatten root-scope vs independent Connectivity):");
        List<ComponentInstance> instances = schematic.componentInstances().stream()
                .sorted(Comparator.comparing(ComponentInstance::id)).toList();
        for (int index = 0; index < instances.size(); index++) {
            ComponentInstance instance = instances.get(index);
            ComponentDefinition definition = schematic.componentDefinitionFor(instance);
            for (ComponentInstance.PinGeometry geometry : instance.geometry(definition).pins()) {
                Connectivity.PinPosition pin = new Connectivity.PinPosition(
                        instance.id(), geometry.name(), geometry.x(), geometry.y());
                String flattenNet = netId(result.topology(), pin);
                String connectivityNet = netId(connectivity, pin);
                System.out.println("  " + instance.id() + "." + geometry.name()
                        + " @(" + geometry.x() + "," + geometry.y() + ")"
                        + " flatten=" + flattenNet + " connectivity=" + connectivityNet);
            }
        }
    }

    private static void reportTemporaryProjection(Fixture fixture) {
        Schematic temporary =
                CircuitDescriber.temporarySchematicForInstanceExport(fixture.schematic());
        Connectivity.Result topology = Connectivity.analyze(
                temporary.pinPositions(), temporary.wires());
        System.out.println("CircuitDescriber temporary projection:");
        List<ComponentInstance> instances = fixture.schematic().componentInstances().stream()
                .sorted(Comparator.comparing(ComponentInstance::id)).toList();
        for (int index = 0; index < instances.size(); index++) {
            ComponentInstance instance = instances.get(index);
            ComponentDefinition definition = fixture.schematic().componentDefinitionFor(instance);
            for (ComponentInstance.PinGeometry geometry : instance.geometry(definition).pins()) {
                String kind = geometry.direction() == ComponentDefinition.Direction.IN
                        ? "sink" : "source";
                String syntheticId = "export-box-" + kind + "-" + index + "-" + geometry.name();
                String syntheticPinId = geometry.direction() == ComponentDefinition.Direction.IN
                        ? "in0" : "out";
                Connectivity.PinPosition pseudo = pin(temporary, syntheticId, syntheticPinId);
                System.out.println("  " + instance.id() + "." + geometry.name()
                        + " @(" + geometry.x() + "," + geometry.y() + ")"
                        + " -> " + syntheticId + "." + syntheticPinId
                        + " @(" + pseudo.x() + "," + pseudo.y() + ")"
                        + " tempNet=" + netId(topology, pseudo)
                        + " segments=" + netSegmentCount(topology, pseudo));
            }
        }
    }

    private static void reportAdderValues(
            Fixture fixture, SchematicCompiler.SimulationResult result) {
        for (ComponentInstance adder : fixture.adders()) {
            StringBuilder line = new StringBuilder(adder.label().isBlank() ? adder.id() : adder.label());
            for (String input : List.of("A", "B", "CIN")) {
                line.append(" ").append(input).append("=")
                        .append(valueAt(result, pin(fixture.schematic(), adder.id(), input)));
            }
            for (String output : List.of("S", "COUT")) {
                line.append(" ").append(output).append("=")
                        .append(valueAt(result, pin(fixture.schematic(), adder.id(), output)));
            }
            line.append(" didNotSettle=").append(result.oscillating())
                    .append(" tickBudgetHit=").append(result.oscillating());
            System.out.println("  " + line);
        }
    }

    private static Value valueAt(
            SchematicCompiler.SimulationResult result, Connectivity.PinPosition pin) {
        return result.topology().nets().stream()
                .filter(net -> net.pins().contains(pin))
                .findFirst()
                .map(net -> result.netValues().getOrDefault(net, Value.X))
                .orElse(Value.X);
    }

    private static String netId(Connectivity.Result topology, Connectivity.PinPosition pin) {
        for (int index = 0; index < topology.nets().size(); index++) {
            if (topology.nets().get(index).pins().contains(pin)) return "N" + index;
        }
        return "NO_NET";
    }

    private static int netSegmentCount(
            Connectivity.Result topology, Connectivity.PinPosition pin) {
        return topology.nets().stream()
                .filter(net -> net.pins().contains(pin))
                .findFirst().map(net -> net.segments().size()).orElse(0);
    }
}
