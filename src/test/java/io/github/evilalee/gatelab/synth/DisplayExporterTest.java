package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.DisplayComponent;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.synth.layout.TidyCircuitService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisplayExporterTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void expressionAndVhdlExportsExposeEveryDisplayInputAsANamedOutputPort() {
        Schematic schematic = wiredDisplays();
        CircuitDescriber.Description description = CircuitDescriber.describe(schematic);
        VhdlExporter.Export vhdl = VhdlExporter.export(schematic, "displays.lbc");

        for (String signal : List.of(
                "Status_a", "Status_b", "Status_c", "Status_d",
                "Status_e", "Status_f", "Status_g", "Status_dp",
                "Nibble_d0", "Nibble_d1", "Nibble_d2", "Nibble_d3", "Nibble_dp")) {
            assertTrue(description.text().contains(signal + " ="),
                    "expression output " + signal + " missing from " + description.text());
            assertTrue(vhdl.text().contains(signal),
                    "VHDL port " + signal + " missing");
        }
        String statusId = schematic.components().stream()
                .filter(component -> component.typeId().equals("seg7"))
                .findFirst().orElseThrow().id();
        assertEquals(statusId, description.componentIds().get("Status_a"));
        assertFalse(vhdl.testbenchCapability().enabled());
        assertTrue(vhdl.testbenchCapability().reason().contains("display"));
        assertNull(vhdl.testbench());
    }

    @Test
    void tidyTreatsEveryDisplayPinAsASeparateNamedSink() {
        Schematic schematic = wiredDisplayThroughBuffer();
        TidyCircuitService.TidyResult result =
                TidyCircuitService.tidy(schematic, DEFINITIONS);

        List<PlacedComponent> outputs = result.schematic().components().stream()
                .filter(component -> component.typeId().equals("output"))
                .toList();
        assertEquals(8, outputs.size());
        assertTrue(outputs.stream().map(PlacedComponent::label).toList().containsAll(
                List.of("Seg1_a", "Seg1_b", "Seg1_c", "Seg1_d",
                        "Seg1_e", "Seg1_f", "Seg1_g", "Seg1_dp")));
        assertTrue(result.schematic().components().stream()
                .noneMatch(component -> component.typeId().equals("seg7")));
    }

    private static Schematic wiredDisplays() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent seg7 = schematic.add("seg7", 600, 300, Facing.EAST);
        schematic.setLabel(seg7.id(), "Status");
        PlacedComponent hex = schematic.add("hexdigit", 1000, 300, Facing.SOUTH);
        schematic.setLabel(hex.id(), "Nibble");
        wireEveryPinToInput(schematic, seg7);
        wireEveryPinToInput(schematic, hex);
        return schematic;
    }

    private static Schematic wiredDisplayThroughBuffer() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent display = schematic.add("seg7", 600, 300, Facing.EAST);
        Connectivity.PinPosition displayA = pin(schematic, display.id(), "in0");

        GateDef inverter = definition("not");
        GateGeometry.Offset inverterOutput = GateGeometry.outputOffset(inverter, Facing.EAST);
        PlacedComponent gate = new PlacedComponent(
                "inverter-display", "not", 500, displayA.y() - inverterOutput.y(),
                Facing.EAST, Map.of(), "");
        schematic.add(gate);

        Connectivity.PinPosition gateInput = pin(schematic, gate.id(), "in0");
        PlacedComponent data = addInputAt(
                schematic, "inverter-data", gateInput.x() - 80, gateInput.y());
        Connectivity.PinPosition dataOut = pin(schematic, data.id(), "out");
        schematic.addWire(new WireSegment(
                dataOut.x(), dataOut.y(), gateInput.x(), gateInput.y()));
        Connectivity.PinPosition gateOut = pin(schematic, gate.id(), "out");
        schematic.addWire(new WireSegment(
                gateOut.x(), gateOut.y(), displayA.x(), displayA.y()));

        List<Connectivity.PinPosition> displayPins = schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(display.id()))
                .filter(position -> !position.pinId().equals("in0"))
                .toList();
        int index = 0;
        for (Connectivity.PinPosition displayPin : displayPins) {
            PlacedComponent source = addInputAt(
                    schematic, "display-data-" + index++,
                    displayPin.x() - 80, displayPin.y());
            Connectivity.PinPosition sourceOut = pin(schematic, source.id(), "out");
            schematic.addWire(new WireSegment(
                    sourceOut.x(), sourceOut.y(), displayPin.x(), displayPin.y()));
        }
        return schematic;
    }

    private static void wireEveryPinToInput(Schematic schematic, PlacedComponent display) {
        List<DisplayComponent.SinkPin> sinks = DisplayComponent.sinkPins(schematic).stream()
                .filter(sink -> sink.componentId().equals(display.id()))
                .toList();
        int index = 0;
        for (DisplayComponent.SinkPin sink : sinks) {
            Connectivity.PinPosition displayPin = pin(schematic, display.id(), sink.pinId());
            PlacedComponent source = addInputAt(
                    schematic, display.id() + "-data-" + (index++),
                    displayPin.x() - 80, displayPin.y());
            Connectivity.PinPosition sourceOut = pin(schematic, source.id(), "out");
            schematic.addWire(new WireSegment(
                    sourceOut.x(), sourceOut.y(), displayPin.x(), displayPin.y()));
        }
    }

    private static PlacedComponent addInputAt(
            Schematic schematic, String id, int pinX, int pinY) {
        GateGeometry.Offset offset =
                GateGeometry.outputOffset(definition("input"), Facing.EAST);
        PlacedComponent input = new PlacedComponent(
                id, "input", pinX - offset.x(), pinY - offset.y(),
                Facing.EAST, Map.of(), "");
        schematic.add(input);
        return input;
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(candidate -> candidate.componentId().equals(componentId)
                        && candidate.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static GateDef definition(String id) {
        return DEFINITIONS.stream().filter(definition -> definition.id().equals(id))
                .findFirst().orElseThrow();
    }
}
