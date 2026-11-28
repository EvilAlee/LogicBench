package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiInputExportTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void fiveInputNandRoundTripsThroughExpressionAndExportsNaryVhdl() {
        Schematic schematic = new Schematic(DEFINITIONS);
        List<PlacedComponent> inputs = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            String name = String.valueOf((char) ('A' + index));
            inputs.add(new PlacedComponent(
                    "input-" + name, "input", 100, 60 + index * 20,
                    Facing.EAST, Map.of(), name));
        }
        PlacedComponent nand = schematic.add(
                "nand", 300, 100, Facing.EAST, Map.of("inputs", "5"));
        PlacedComponent output = new PlacedComponent(
                "output-f", "output", 450, 100, Facing.EAST, Map.of(), "F");
        for (PlacedComponent input : inputs) {
            schematic.add(input);
        }
        schematic.add(output);
        for (int index = 0; index < inputs.size(); index++) {
            connect(schematic, inputs.get(index).id(), "out", nand.id(), "in" + index);
        }
        connect(schematic, nand.id(), "out", output.id(), "in0");

        SchematicNetlistExtractor.Extraction extracted =
                SchematicNetlistExtractor.extract(schematic);
        assertEquals(5, extracted.netlist().gates().getFirst().inputs().size());

        CircuitDescriber.Description described = CircuitDescriber.describe(schematic);
        assertTrue(described.text().contains("NOT"), described.text());
        assertTrue(described.text().contains("A & B & C & D & E"), described.text());

        ExpressionCompiler.Compilation rebuilt = ExpressionCompiler.compile(
                described.text(), false);
        TruthTable expected = TruthTableGenerator.generate(extracted.netlist());
        TruthTable actual = TruthTableGenerator.generate(rebuilt.netlist());
        assertEquals(expected.inputNames(), actual.inputNames());
        assertEquals(expected.outputNames(), actual.outputNames());
        assertEquals(expected.rows().size(), actual.rows().size());
        for (int row = 0; row < expected.rows().size(); row++) {
            assertEquals(expected.rows().get(row).outputValues(),
                    actual.rows().get(row).outputValues(), "truth-table row " + row);
        }

        Schematic laidOut = new io.github.evilalee.gatelab.synth.layout.SchematicLayout(
                DEFINITIONS).layout(rebuilt.netlist()).schematic();
        PlacedComponent laidOutNand = laidOut.components().stream()
                .filter(component -> component.typeId().equals("nand"))
                .findFirst().orElseThrow();
        assertEquals(5, laidOut.definitionFor(laidOutNand).inputCount());
        assertEquals(5, SchematicNetlistExtractor.extract(laidOut)
                .netlist().gates().getFirst().inputs().size());

        String vhdl = VhdlExporter.export(schematic, "five-input-nand.lbc").text();
        assertTrue(vhdl.contains("(not ("), vhdl);
        assertTrue(vhdl.contains(" and "), vhdl);
        assertTrue(vhdl.contains("A"), vhdl);
        assertTrue(vhdl.contains("E"), vhdl);
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
