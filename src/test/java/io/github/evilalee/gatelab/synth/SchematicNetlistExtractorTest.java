package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchematicNetlistExtractorTest {
    @Test
    void extractsLabeledAndGeneratedNamesInVisualOrderAndKeepsUnusedGates() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent firstVisualInput = new PlacedComponent(
                "input-b", "input", 0, 0, Facing.EAST, Map.of(), "");
        PlacedComponent secondVisualInput = new PlacedComponent(
                "input-a", "input", 0, 100, Facing.EAST, Map.of(), "A");
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 50, Facing.EAST);
        PlacedComponent unusedGate = new PlacedComponent(
                "gate-buffer", "buffer", 450, 150, Facing.EAST);
        PlacedComponent output = new PlacedComponent(
                "output", "output", 550, 50, Facing.EAST, Map.of(), "");

        for (PlacedComponent component : List.of(
                firstVisualInput, secondVisualInput, gate, unusedGate, output)) {
            schematic.add(component);
        }
        connect(schematic, "input-b", "out", "gate-and", "in0");
        connect(schematic, "input-a", "out", "gate-and", "in1");
        connect(schematic, "gate-and", "out", "output", "in0");
        connect(schematic, "gate-and", "out", "gate-buffer", "in0");

        SchematicNetlistExtractor.Extraction extraction =
                SchematicNetlistExtractor.extract(schematic);

        assertEquals(List.of("In1", "A"), extraction.netlist().inputs());
        assertEquals(List.of("Out1"), extraction.netlist().outputs().stream()
                .map(Netlist.Output::name).toList());
        assertEquals("input-b", extraction.inputComponentIds().get("In1"));
        assertEquals("output", extraction.outputComponentIds().get("Out1"));
        assertEquals(2, extraction.netlist().gates().size());
        assertEquals(GateOperator.AND, extraction.netlist().gates().getFirst().type());
        assertEquals(List.of("In1", "A"),
                extraction.netlist().gates().getFirst().inputs());
        assertEquals(GateOperator.BUFFER, extraction.netlist().gates().get(1).type());
    }

    @Test
    void connectedWireCrossingsRemainSeparateNets() {
        Schematic schematic = new Schematic(GateLibrary.load().all());
        PlacedComponent input = new PlacedComponent(
                "input-a", "input", 0, 0, Facing.EAST, Map.of(), "A");
        PlacedComponent gate = new PlacedComponent(
                "gate-and", "and", 300, 100, Facing.NORTH);
        PlacedComponent output = new PlacedComponent(
                "output-f", "output", 500, 100, Facing.EAST, Map.of(), "F");
        schematic.add(input);
        schematic.add(gate);
        schematic.add(output);

        Connectivity.PinPosition source = pin(schematic, "input-a", "out");
        Connectivity.PinPosition gateInput0 = pin(schematic, "gate-and", "in0");
        Connectivity.PinPosition gateInput1 = pin(schematic, "gate-and", "in1");
        Connectivity.PinPosition gateOutput = pin(schematic, "gate-and", "out");
        Connectivity.PinPosition outputInput = pin(schematic, "output-f", "in0");
        connect(schematic, source, gateInput0);
        connect(schematic, source, gateInput1);
        connect(schematic, gateOutput, outputInput);

        SchematicNetlistExtractor.Extraction extraction =
                SchematicNetlistExtractor.extract(schematic);
        assertEquals(List.of("A"), extraction.netlist().inputs());
        assertEquals("F", extraction.netlist().outputs().getFirst().name());
        assertEquals(List.of("A", "A"), extraction.netlist().gates().getFirst().inputs());
        Connectivity.Net inputNet = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires()).nets().stream()
                .filter(net -> net.pins().contains(new Connectivity.PinPosition(
                        "input-a", "out", 0, 0))).findFirst().orElseThrow();
        assertFalse(inputNet.pins().contains(new Connectivity.PinPosition(
                "gate-and", "out", 300, 100)));
    }

    private static void connect(
            Schematic schematic,
            String sourceId,
            String sourcePin,
            String targetId,
            String targetPin) {
        connect(schematic, pin(schematic, sourceId, sourcePin),
                pin(schematic, targetId, targetPin));
    }

    private static void connect(
            Schematic schematic,
            Connectivity.PinPosition source,
            Connectivity.PinPosition target) {
        schematic.addWire(source.x(), source.y(), target.x(), source.y());
        schematic.addWire(target.x(), source.y(), target.x(), target.y());
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(componentId)
                        && pin.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }
}
