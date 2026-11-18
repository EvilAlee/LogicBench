package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanvasExpressionGenerationTest {
    @Test
    void generatedCircuitIsOneUndoStepAndRedoRestoresItsLabeledComponentsAndWires() {
        CanvasPanel canvas = new CanvasPanel();
        PlacedComponent existing = canvas.schematic().add("input", 0, 0, Facing.EAST);
        SchematicSnapshotView before = SchematicSnapshotView.capture(canvas.schematic());

        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile("F = A ^ B ^ C");
        SchematicLayout.LayoutResult generated = new SchematicLayout(
                GateLibrary.load().all()).layout(compilation.netlist());

        canvas.replaceWithGeneratedCircuit(generated.schematic());

        assertEquals(generated.schematic().components(), canvas.schematic().components());
        assertEquals(generated.schematic().wires(), canvas.schematic().wires());
        assertTrue(canvas.canUndo());
        assertFalse(canvas.canRedo());

        assertTrue(canvas.undo());
        assertEquals(before.components(), canvas.schematic().components());
        assertEquals(before.wires(), canvas.schematic().wires());
        assertEquals(existing.id(), canvas.schematic().components().getFirst().id());

        assertTrue(canvas.redo());
        assertEquals(generated.schematic().components(), canvas.schematic().components());
        assertEquals(generated.schematic().wires(), canvas.schematic().wires());
        assertTrue(canvas.schematic().components().stream()
                .filter(component -> component.typeId().equals("input"))
                .allMatch(component -> !component.label().isEmpty()));
    }

    @Test
    void replacementResetsEveryGeneratedLogicalInputToLowAndFitsTheCanvas() {
        CanvasPanel canvas = new CanvasPanel();
        canvas.setSize(720, 480);
        SchematicLayout.LayoutResult generated = new SchematicLayout(
                GateLibrary.load().all()).layout(
                        ExpressionCompiler.compile("F = (A & B) | (C & D)").netlist());
        generated.schematic().setInputValue(
                generated.inputComponentIds().get("A"), true);

        canvas.replaceWithGeneratedCircuit(generated.schematic());

        for (String id : generated.inputComponentIds().values()) {
            assertFalse(canvas.schematic().inputValue(id));
        }
        for (var component : canvas.schematic().components()) {
            if (component.typeId().equals("input") && component.label().equals("1")) {
                assertTrue(canvas.schematic().inputValue(component.id()),
                        "constant one source stays HIGH");
            }
        }
        assertTrue(canvas.viewTransform().scale() >= ViewTransform.MIN_ZOOM);
        assertTrue(canvas.viewTransform().scale() <= ViewTransform.MAX_ZOOM);
    }

    private record SchematicSnapshotView(
            List<PlacedComponent> components,
            List<io.github.evilalee.gatelab.model.WireSegment> wires) {
        private static SchematicSnapshotView capture(Schematic schematic) {
            return new SchematicSnapshotView(schematic.components(), schematic.wires());
        }
    }
}
