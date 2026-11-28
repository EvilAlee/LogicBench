package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.DisplayComponent;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.sim.PinStateHelper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.model.OutputProbe;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisplayComponentTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void displayDefinitionsHaveOnlyTheNamedSinkInputs() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent seg7 = schematic.add("seg7", 200, 200, Facing.EAST);
        PlacedComponent hex = schematic.add("hexdigit", 500, 200, Facing.EAST);

        assertNull(schematic.definitionFor(seg7).output());
        assertNull(schematic.definitionFor(hex).output());
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "dp"),
                DisplayComponent.inputNames("seg7"));
        assertEquals(List.of("D0", "D1", "D2", "D3", "dp"),
                DisplayComponent.inputNames("hexdigit"));
        assertEquals(8, schematic.definitionFor(seg7).inputs().size());
        assertEquals(5, schematic.definitionFor(hex).inputs().size());
    }

    @Test
    void unnamedDisplaysGetDocumentOrderedFallbackNamesWithoutNamedPartsConsumingNumbers() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent namedSeg = schematic.add("seg7", 100, 100, Facing.EAST);
        schematic.setLabel(namedSeg.id(), "Status");
        PlacedComponent firstSeg = schematic.add("seg7", 300, 100, Facing.EAST);
        PlacedComponent secondSeg = schematic.add("seg7", 500, 100, Facing.EAST);
        PlacedComponent namedHex = schematic.add("hexdigit", 700, 100, Facing.EAST);
        schematic.setLabel(namedHex.id(), "Counter");
        PlacedComponent firstHex = schematic.add("hexdigit", 900, 100, Facing.EAST);

        Map<String, String> outputNamesByPin = new HashMap<>();
        for (DisplayComponent.SinkPin sink : DisplayComponent.sinkPins(schematic)) {
            outputNamesByPin.put(sink.componentId() + "." + sink.pinId(), sink.portName());
        }
        assertEquals("Status_a", outputNamesByPin.get(namedSeg.id() + ".in0"));
        assertEquals("Seg1_a", outputNamesByPin.get(firstSeg.id() + ".in0"));
        assertEquals("Seg2_a", outputNamesByPin.get(secondSeg.id() + ".in0"));
        assertEquals("Counter_d0", outputNamesByPin.get(namedHex.id() + ".in0"));
        assertEquals("Hex1_d0", outputNamesByPin.get(firstHex.id() + ".in0"));
    }

    @Test
    void displaysRoundTripWithNameAndAttributesAndLegacyFilesRemainReadable() {
        Schematic original = new Schematic(DEFINITIONS);
        PlacedComponent seg7 = original.add("seg7", 200, 200, Facing.SOUTH);
        original.setLabel(seg7.id(), "Status");
        original.setAttribute(seg7.id(), "activeLevel", "low");
        original.setAttribute(seg7.id(), "onColour", "green");
        PlacedComponent hex = original.add("hexdigit", 500, 200, Facing.WEST);
        original.setLabel(hex.id(), "Nibble");
        original.setAttribute(hex.id(), "onColour", "blue");

        String json = SchematicJson.toJson(original);
        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(original.components(), loaded.components());
        assertEquals("Status", loaded.components().get(0).label());
        assertEquals("low", loaded.attributeValue(seg7.id(), "activeLevel"));
        assertEquals("green", loaded.attributeValue(seg7.id(), "onColour"));
        assertEquals("blue", loaded.attributeValue(hex.id(), "onColour"));

        Schematic legacy = SchematicJson.fromJson(
                "{\"version\":1,\"components\":[{\"id\":\"in\","
                        + "\"type\":\"input\",\"x\":0,\"y\":0,"
                        + "\"facing\":\"EAST\"}],\"wires\":[]}",
                DEFINITIONS);
        assertEquals("input", legacy.components().getFirst().typeId());
        assertTrue(legacy.components().getFirst().attributes().isEmpty());
    }

    @Test
    void displaysCopyPasteWithTheirNameAndAttributes() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent display = source.add("hexdigit", 200, 200, Facing.NORTH);
        source.setLabel(display.id(), "Count");
        source.setAttribute(display.id(), "activeLevel", "low");
        source.setAttribute(display.id(), "onColour", "yellow");

        String encoded = SchematicClipboard.encode(
                source, List.of(display.id()), List.of()).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                encoded, DEFINITIONS, null).orElseThrow();
        Schematic target = new Schematic(DEFINITIONS);
        SchematicClipboard.PasteResult pasted = SchematicClipboard.paste(
                target, payload, null, null, 500, 400).orElseThrow();
        PlacedComponent copy = target.components().stream()
                .filter(component -> component.id().equals(pasted.componentIds().getFirst()))
                .findFirst().orElseThrow();

        assertEquals("hexdigit", copy.typeId());
        assertEquals("Count", copy.label());
        assertEquals("low", target.attributeValue(copy.id(), "activeLevel"));
        assertEquals("yellow", target.attributeValue(copy.id(), "onColour"));
        assertEquals(5, target.definitionFor(copy).inputs().size());
    }

    @Test
    void allDisplayPinPositionsRotateWithTheComponentGeometry() {
        for (String type : List.of("seg7", "hexdigit")) {
            for (Facing facing : Facing.values()) {
                Schematic schematic = new Schematic(DEFINITIONS);
                PlacedComponent display = schematic.add(type, 200, 300, facing);
                GateDef definition = schematic.definitionFor(display);
                Map<String, Connectivity.PinPosition> actual = new HashMap<>();
                for (Connectivity.PinPosition pin : schematic.pinPositions()) {
                    actual.put(pin.pinId(), pin);
                }
                for (int index = 0; index < definition.inputs().size(); index++) {
                    GateGeometry.Offset offset = GateGeometry.pinOffset(
                            definition, definition.inputs().get(index), facing);
                    Connectivity.PinPosition pin = actual.get("in" + index);
                    assertNotNull(pin);
                    assertEquals(display.x() + offset.x(), pin.x(),
                            type + " " + facing + " input " + index + " x");
                    assertEquals(display.y() + offset.y(), pin.y(),
                            type + " " + facing + " input " + index + " y");
                }
            }
        }
    }

    @Test
    void compilerUsesOnlyReadOnlyOutputProbesForDisplayPins() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent display = schematic.add("seg7", 500, 300, Facing.EAST);
        Connectivity.PinPosition displayA = pin(schematic, display.id(), "in0");
        PlacedComponent source = addInputAt(
                schematic, "segment-a", displayA.x() - 80, displayA.y());
        Connectivity.PinPosition sourcePin = pin(schematic, source.id(), "out");
        schematic.addWire(new WireSegment(
                sourcePin.x(), sourcePin.y(), displayA.x(), displayA.y()));
        schematic.setInputValue(source.id(), true);

        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        assertEquals(8, compiled.circuit().components().stream()
                .filter(OutputProbe.class::isInstance).count());
        assertTrue(compiled.outputs().isEmpty(),
                "display terminals are sink probes, not public Output components");

        SchematicCompiler.SimulationResult result =
                SchematicCompiler.compileAndSettle(schematic);
        assertTrue(result.outputValues().isEmpty());
        Connectivity.Net connected = result.topology().nets().stream()
                .filter(net -> net.pins().stream().anyMatch(pin ->
                        pin.componentId().equals(display.id()) && pin.pinId().equals("in0")))
                .findFirst().orElseThrow();
        assertEquals(Value.ONE, result.netValues().get(connected));
        assertEquals(PinStateHelper.State.CONNECTED_ONE,
                PinStateHelper.analyze(schematic, result)
                        .requirePin(display.id(), "in0").state());
    }

    private static PlacedComponent addInputAt(Schematic schematic, String id, int pinX, int pinY) {
        GateDef input = definition("input");
        GateGeometry.Offset output = GateGeometry.outputOffset(input, Facing.EAST);
        PlacedComponent placed = new PlacedComponent(
                id, "input", pinX - output.x(), pinY - output.y(),
                Facing.EAST, Map.of(), id);
        schematic.add(placed);
        return placed;
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
