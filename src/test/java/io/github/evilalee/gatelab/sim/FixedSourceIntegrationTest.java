package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicClipboard;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.CircuitDescriber;
import io.github.evilalee.gatelab.synth.VhdlExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FixedSourceIntegrationTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @TempDir
    Path directory;

    @Test
    void vccAndGndAreFixedOutputOnlySimulationSources() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent vcc = schematic.add("vcc", 0, 20, Facing.EAST);
        PlacedComponent gnd = schematic.add("gnd", 0, 110, Facing.EAST);
        PlacedComponent or = schematic.add("or", 200, 100, Facing.EAST);
        PlacedComponent output = schematic.add("output", 300, 100, Facing.EAST);

        connect(schematic, vcc.id(), "out", or.id(), "in0");
        connect(schematic, gnd.id(), "out", or.id(), "in1");
        connect(schematic, or.id(), "out", output.id(), "in0");

        assertEquals(Value.ONE, vcc.fixedSourceValue());
        assertEquals(Value.ZERO, gnd.fixedSourceValue());
        assertEquals(0, schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(vcc.id())
                        && pin.pinId().startsWith("in")).count());
        assertEquals(0, schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(gnd.id())
                        && pin.pinId().startsWith("in")).count());
        assertThrows(IllegalArgumentException.class, () -> schematic.toggleInput(vcc.id()));
        assertThrows(IllegalArgumentException.class, () -> schematic.toggleInput(gnd.id()));
        assertEquals(Value.ONE, SchematicCompiler.compileAndSettle(schematic)
                .outputValues().get(output.id()));
    }

    @Test
    void constantValueAndNameRoundTripAndLegacyLabelMigrates() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent constant = schematic.add(
                "constant", 100, 100, Facing.NORTH, Map.of("value", "1"));
        assertTrue(schematic.setLabel(constant.id(), "BIAS"));
        PlacedComponent vcc = schematic.add("vcc", 200, 100, Facing.WEST);
        PlacedComponent gnd = schematic.add("gnd", 300, 100, Facing.SOUTH);

        String json = SchematicJson.toJson(schematic);
        Schematic loaded = SchematicJson.fromJson(json, DEFINITIONS);
        assertEquals(schematic.components(), loaded.components());
        assertEquals("1", loaded.attributeValue(constant.id(), "value"));
        assertEquals("BIAS", loaded.components().stream()
                .filter(component -> component.id().equals(constant.id()))
                .findFirst().orElseThrow().label());
        assertEquals(Value.ONE, loaded.components().stream()
                .filter(component -> component.typeId().equals("constant"))
                .findFirst().orElseThrow().fixedSourceValue());
        assertTrue(loaded.components().stream().anyMatch(c -> c.typeId().equals("vcc")));
        assertTrue(loaded.components().stream().anyMatch(c -> c.typeId().equals("gnd")));

        String legacy = """
                {"version":1,
                 "components":[{"id":"old-constant","type":"constant",
                   "x":100,"y":100,"facing":"EAST","label":"1"}],
                 "wires":[]}
                """;
        Schematic migrated = SchematicJson.fromJson(legacy, DEFINITIONS);
        PlacedComponent old = migrated.components().getFirst();
        assertEquals("1", migrated.attributeValue(old.id(), "value"));
        assertEquals("", old.label());
        assertEquals(Value.ONE, old.fixedSourceValue());
    }

    @Test
    void fixedSourcesKeepTheirTypeValueAndNameThroughClipboardCopyAndPaste()
            throws IOException {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent vcc = source.add("vcc", 0, 0, Facing.EAST);
        PlacedComponent gnd = source.add("gnd", 0, 100, Facing.SOUTH);
        PlacedComponent constant = source.add(
                "constant", 100, 100, Facing.WEST, Map.of("value", "1"));
        source.setLabel(constant.id(), "BIAS");

        String encoded = SchematicClipboard.encode(
                source, List.of(vcc.id(), gnd.id(), constant.id()), List.of()).orElseThrow();
        ComponentLibrary library = ComponentLibrary.load(
                DEFINITIONS, directory.resolve("components"));
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                encoded, DEFINITIONS, library).orElseThrow();

        Schematic target = new Schematic(DEFINITIONS);
        SchematicClipboard.PasteResult result = SchematicClipboard.paste(
                target, payload, null, null, 200, 200).orElseThrow();
        assertEquals(3, result.componentIds().size());
        assertTrue(target.components().stream().anyMatch(c ->
                c.typeId().equals("vcc") && c.fixedSourceValue() == Value.ONE));
        assertTrue(target.components().stream().anyMatch(c ->
                c.typeId().equals("gnd") && c.fixedSourceValue() == Value.ZERO));
        PlacedComponent pastedConstant = target.components().stream()
                .filter(c -> c.typeId().equals("constant")).findFirst().orElseThrow();
        assertEquals("1", target.attributeValue(pastedConstant.id(), "value"));
        assertEquals("BIAS", pastedConstant.label());
    }

    @Test
    void expressionAndVhdlExportUseVccAndGndAsLiteralOneAndZero() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent vcc = schematic.add("vcc", 0, 20, Facing.EAST);
        PlacedComponent gnd = schematic.add("gnd", 0, 110, Facing.EAST);
        PlacedComponent or = schematic.add("or", 200, 100, Facing.EAST);
        PlacedComponent output = schematic.add("output", 300, 100, Facing.EAST);
        connect(schematic, vcc.id(), "out", or.id(), "in0");
        connect(schematic, gnd.id(), "out", or.id(), "in1");
        connect(schematic, or.id(), "out", output.id(), "in0");

        CircuitDescriber.Description expression = CircuitDescriber.describe(schematic);
        assertTrue(expression.text().contains("(1 | 0)"), expression.text());

        String vhdl = VhdlExporter.export(schematic, "power-sources.lbc").text();
        assertTrue(vhdl.contains("'1'"), vhdl);
        assertTrue(vhdl.contains("'0'"), vhdl);
        assertTrue(vhdl.contains(" or "), vhdl);
    }

    private static void connect(
            Schematic schematic, String sourceId, String sourcePin,
            String targetId, String targetPin) {
        Connectivity.PinPosition source = pin(schematic, sourceId, sourcePin);
        Connectivity.PinPosition target = pin(schematic, targetId, targetPin);
        if (source.x() != target.x()) {
            schematic.addWire(source.x(), source.y(), target.x(), source.y());
        }
        if (source.y() != target.y()) {
            schematic.addWire(target.x(), source.y(), target.x(), target.y());
        }
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, String componentId, String pinId) {
        return schematic.pinPositions().stream()
                .filter(value -> value.componentId().equals(componentId)
                        && value.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }
}
