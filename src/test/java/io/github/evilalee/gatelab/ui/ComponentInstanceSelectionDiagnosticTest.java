package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ComponentInstanceSelectionDiagnosticTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void reportsCurrentInstanceClickAndMarqueeSelectionPath() throws ReflectiveOperationException {
        Schematic internal = new Schematic(GATES);
        internal.add(new PlacedComponent(
                "input-a", "input", 100, 100, Facing.EAST, Map.of(), "A"));
        internal.add(new PlacedComponent(
                "output-y", "output", 300, 100, Facing.EAST, Map.of(), "Y"));
        ComponentDefinition definition = ComponentDefinition.fromCircuit(
                "SelectionDiagnostic", "Selection diagnostic", "Test", "Y", internal);

        Schematic schematic = new Schematic(GATES);
        ComponentInstance instance = schematic.addInstance(
                definition, "U1", 100, 100, Facing.EAST);
        GateGeometry.Bounds bounds = instance.bounds(definition);
        int clickX = bounds.x() + bounds.width() / 2;
        int clickY = bounds.y() + bounds.height() / 2;
        var clickHit = schematic.componentAt(clickX, clickY);
        List<PlacedComponent> marqueeHits = schematic.componentsIntersecting(bounds);

        boolean inClickAndMarqueeComponentList = schematic.components().stream()
                .anyMatch(component -> component.id().equals(instance.id()));
        boolean inSeparateInstanceList = schematic.componentInstances().stream()
                .anyMatch(candidate -> candidate.id().equals(instance.id()));

        Field selectedIds = CanvasPanel.class.getDeclaredField("selectedIds");
        String selectionType = selectedIds.getGenericType().getTypeName();
        boolean idFitsSelectionElementType = Set.class.isAssignableFrom(selectedIds.getType())
                && selectedIds.getGenericType().getTypeName().contains("java.lang.String");

        System.out.println("INSTANCE_SELECTION_DIAG bounds=" + bounds);
        System.out.println("INSTANCE_SELECTION_DIAG clickPoint=(" + clickX + "," + clickY
                + "); componentAt=" + clickHit);
        System.out.println("INSTANCE_SELECTION_DIAG click/marquee iterate schematic.components(): "
                + "containsInstance=" + inClickAndMarqueeComponentList
                + "; marqueeHits=" + marqueeHits.stream().map(PlacedComponent::id).toList());
        System.out.println("INSTANCE_SELECTION_DIAG separate schematic.componentInstances(): "
                + "containsInstance=" + inSeparateInstanceList);
        System.out.println("INSTANCE_SELECTION_DIAG selectedIds type=" + selectionType
                + "; accepts instance String id=" + idFitsSelectionElementType);

        assertEquals(new GateGeometry.Bounds(100, 100, 100, 40), bounds);
        assertTrue(clickHit.isEmpty());
        assertTrue(marqueeHits.isEmpty());
        assertFalse(inClickAndMarqueeComponentList);
        assertTrue(inSeparateInstanceList);
        assertTrue(idFitsSelectionElementType);
    }
}
