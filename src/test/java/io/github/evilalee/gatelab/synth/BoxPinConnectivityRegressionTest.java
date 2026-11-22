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
import io.github.evilalee.gatelab.synth.CircuitDescriber.Description;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
            try {
                Description description = CircuitDescriber.describe(fixture.schematic());
                System.out.println("CircuitDescriber: SUCCESS\n" + description.text());
            } catch (RuntimeException exception) {
                System.out.println("CircuitDescriber: EXCEPTION "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
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

        connectViaX(schematic, pin(schematic, d.id(), "out"),
                pin(schematic, latch.id(), "D"), 100);
        connectViaX(schematic, pin(schematic, en.id(), "out"),
                pin(schematic, latch.id(), "EN"), 150);
        connectViaX(schematic, pin(schematic, latch.id(), "Q"),
                pin(schematic, adder.id(), "A"), 360);
        connectViaX(schematic, pin(schematic, latch.id(), "QN"),
                pin(schematic, adder.id(), "B"), 420);
        connectViaX(schematic, pin(schematic, cin.id(), "out"),
                pin(schematic, adder.id(), "CIN"), 400);
        connectViaX(schematic, pin(schematic, adder.id(), "S"),
                pin(schematic, sum.id(), "in0"), 650);
        connectViaX(schematic, pin(schematic, adder.id(), "COUT"),
                pin(schematic, carry.id(), "in0"), 680);
        return new Fixture("Circuit A", schematic, latch, List.of(adder), d, en, List.of(cin));
    }

    private static Fixture circuitB(ComponentLibrary library) {
        Schematic schematic = new Schematic(GATES);
        ComponentInstance latch = schematic.addInstance(
                library.get("DLATCH"), "Latch", 200, 100, Facing.EAST);
        ComponentInstance first = schematic.addInstance(
                library.get("FA"), "FA1", 500, 80, Facing.EAST);
        ComponentInstance second = schematic.addInstance(
                library.get("FA"), "FA2", 760, 250, Facing.EAST);

        PlacedComponent d = addInput(schematic, "D", 20, 20, false);
        PlacedComponent en = addInput(schematic, "EN", 20, 260, false);
        PlacedComponent cin1 = addInput(schematic, "CIN1", 450, 140, false);
        PlacedComponent cin2 = addInput(schematic, "CIN2", 710, 310, false);
        PlacedComponent sum1 = addOutput(schematic, "SUM1", 680, 60);
        PlacedComponent carry1 = addOutput(schematic, "COUT1", 680, 180);
        PlacedComponent sum2 = addOutput(schematic, "SUM2", 940, 230);
        PlacedComponent carry2 = addOutput(schematic, "COUT2", 940, 330);

        connectViaX(schematic, pin(schematic, d.id(), "out"),
                pin(schematic, latch.id(), "D"), 100);
        connectViaX(schematic, pin(schematic, en.id(), "out"),
                pin(schematic, latch.id(), "EN"), 150);

        // One latch output fans out to two adder input pins through separate orthogonal paths.
        connectViaX(schematic, pin(schematic, latch.id(), "Q"),
                pin(schematic, first.id(), "A"), 360);
        connectViaX(schematic, pin(schematic, latch.id(), "Q"),
                pin(schematic, second.id(), "B"), 600);
        connectViaX(schematic, pin(schematic, latch.id(), "QN"),
                pin(schematic, first.id(), "B"), 420);
        connectViaX(schematic, pin(schematic, latch.id(), "QN"),
                pin(schematic, second.id(), "A"), 680);

        connectViaX(schematic, pin(schematic, cin1.id(), "out"),
                pin(schematic, first.id(), "CIN"), 470);
        connectViaX(schematic, pin(schematic, cin2.id(), "out"),
                pin(schematic, second.id(), "CIN"), 730);

        connectViaX(schematic, pin(schematic, first.id(), "S"),
                pin(schematic, sum1.id(), "in0"), 640);
        connectViaX(schematic, pin(schematic, first.id(), "COUT"),
                pin(schematic, carry1.id(), "in0"), 650);
        connectViaX(schematic, pin(schematic, second.id(), "S"),
                pin(schematic, sum2.id(), "in0"), 900);
        connectViaX(schematic, pin(schematic, second.id(), "COUT"),
                pin(schematic, carry2.id(), "in0"), 920);
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

    private static void connectViaX(
            Schematic schematic, Connectivity.PinPosition start,
            Connectivity.PinPosition end, int trackX) {
        addOrthogonalSegment(schematic, start.x(), start.y(), trackX, start.y());
        addOrthogonalSegment(schematic, trackX, start.y(), trackX, end.y());
        addOrthogonalSegment(schematic, trackX, end.y(), end.x(), end.y());
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
