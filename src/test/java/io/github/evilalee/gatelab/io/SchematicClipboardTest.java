package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicHistory;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicClipboardTest {
    private static final List<GateDef> DEFINITIONS = GateLibrary.load().all();

    @Test
    void clipboardCarriesOnlySelectedObjectsAndOnlySelectedWires() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent input = source.add("input", 0, 0, Facing.EAST);
        source.setLabel(input.id(), "DATA");
        source.setInputValue(input.id(), true);
        PlacedComponent clock = source.add("clock", 100, 100, Facing.EAST);
        source.setLabel(clock.id(), "CLK");
        source.setClockValue(clock.id(), true);
        WireSegment selectedWire = new WireSegment(0, 0, 40, 0);
        WireSegment unselectedWire = new WireSegment(0, 20, 40, 20);
        source.addWire(selectedWire);
        source.addWire(unselectedWire);

        String text = SchematicClipboard.encode(
                source, List.of(input.id(), clock.id()), List.of(selectedWire)).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                text, DEFINITIONS, null).orElseThrow();
        Schematic copied = payload.schematic();

        assertEquals(SchematicClipboard.MARKER, text.substring(0, text.indexOf('\n')));
        assertEquals(List.of("DATA", "CLK"), copied.components().stream()
                .map(PlacedComponent::label).toList());
        PlacedComponent copiedInput = copied.components().getFirst();
        PlacedComponent copiedClock = copied.components().getLast();
        assertFalse(copied.inputValue(copiedInput.id()),
                "copied live Input values must reset to LOW");
        assertFalse(copied.clockValue(copiedClock.id()),
                "copied Clock levels must reset to LOW");
        assertEquals(List.of(selectedWire), copied.wires());
        String roundTrip = SchematicClipboard.encode(
                copied,
                copied.components().stream().map(PlacedComponent::id).toList(),
                copied.wires()).orElseThrow();
        assertEquals(text, roundTrip, "clipboard JSON must remain stable after a round trip");
        String forcedHigh = text.replace("\"high\": false", "\"high\": true");
        Schematic decodedHigh = SchematicClipboard.decode(
                forcedHigh, DEFINITIONS, null).orElseThrow().schematic();
        assertFalse(decodedHigh.inputValue(decodedHigh.components().getFirst().id()));
        assertFalse(decodedHigh.clockValue(decodedHigh.components().getLast().id()));
    }

    @Test
    void pasteRemapsIdsResetsSourcesAndCommitsOneUndoableMutation() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent input = source.add("input", 0, 0, Facing.EAST);
        source.setLabel(input.id(), "ENABLE");
        source.setInputValue(input.id(), true);
        String text = SchematicClipboard.encode(
                source, List.of(input.id()), List.of()).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                text, DEFINITIONS, null).orElseThrow();

        Schematic target = new Schematic(DEFINITIONS);
        PlacedComponent existing = target.add("input", 300, 300, Facing.EAST);
        target.setInputValue(existing.id(), true);
        SchematicHistory history = new SchematicHistory();
        long beforeMutation = target.modificationCount();
        history.begin(target);

        SchematicClipboard.PasteResult result = SchematicClipboard.paste(
                target, payload, null, null, 20, 20).orElseThrow();
        assertTrue(history.commit(target));
        assertEquals(beforeMutation + 1, target.modificationCount(),
                "paste must replace the target snapshot only once");
        assertEquals(1, history.undoDepth());

        PlacedComponent pasted = target.components().stream()
                .filter(component -> component.id().equals(result.componentIds().getFirst()))
                .findFirst().orElseThrow();
        assertNotEquals(existing.id(), pasted.id());
        assertEquals("ENABLE", pasted.label());
        assertFalse(target.inputValue(pasted.id()));
        assertTrue(target.inputValue(existing.id()));

        assertTrue(history.undo(target));
        assertEquals(List.of(existing), target.components());
        assertFalse(history.canUndo());
    }

    @Test
    void exactOverlapUsesSpiralToAvoidJoiningExistingPinsOrWires() {
        Schematic target = new Schematic(DEFINITIONS);
        PlacedComponent existingInput = target.add("input", 0, 0, Facing.EAST);
        Schematic copiedInput = new Schematic(DEFINITIONS);
        PlacedComponent sourceInput = copiedInput.add("input", 0, 0, Facing.EAST);
        SchematicClipboard.Payload pinPayload = SchematicClipboard.decode(
                SchematicClipboard.encode(copiedInput, List.of(sourceInput.id()), List.of())
                        .orElseThrow(),
                DEFINITIONS, null).orElseThrow();

        SchematicClipboard.PasteResult pinPaste = SchematicClipboard.paste(
                target, pinPayload, null, null, 0, 0).orElseThrow();
        String pastedInputId = pinPaste.componentIds().getFirst();
        Connectivity.Result pinTopology = Connectivity.analyze(
                target.pinPositions(), target.wires());
        assertTrue(pinTopology.nets().stream().noneMatch(net ->
                net.pins().stream().anyMatch(pin -> pin.componentId().equals(existingInput.id()))
                        && net.pins().stream().anyMatch(pin -> pin.componentId().equals(pastedInputId))));
        assertNotEquals(0, pinPaste.deltaX() + pinPaste.deltaY());

        Schematic wireTarget = new Schematic(DEFINITIONS);
        WireSegment existingWire = new WireSegment(0, 0, 100, 0);
        wireTarget.addWire(existingWire);
        Schematic wireSource = new Schematic(DEFINITIONS);
        WireSegment sourceWire = new WireSegment(0, 0, 20, 0);
        wireSource.addWire(sourceWire);
        SchematicClipboard.Payload wirePayload = SchematicClipboard.decode(
                SchematicClipboard.encode(wireSource, List.of(), List.of(sourceWire))
                        .orElseThrow(),
                DEFINITIONS, null).orElseThrow();

        SchematicClipboard.PasteResult wirePaste = SchematicClipboard.paste(
                wireTarget, wirePayload, null, null, 0, 0).orElseThrow();
        assertNotEquals(0, wirePaste.deltaY(),
                "the pasted endpoint must not remain on the existing horizontal wire");
    }

    @Test
    void cutDeletionAndUndoRestoreComponentsAndSelectedWiresAsOneStep() {
        Schematic schematic = new Schematic(DEFINITIONS);
        PlacedComponent input = schematic.add("input", 0, 0, Facing.EAST);
        PlacedComponent output = schematic.add("output", 100, 0, Facing.EAST);
        WireSegment wire = new WireSegment(0, 0, 100, 0);
        schematic.addWire(wire);
        assertTrue(SchematicClipboard.encode(
                schematic, List.of(input.id()), List.of(wire)).isPresent());

        SchematicHistory history = new SchematicHistory();
        assertTrue(SchematicClipboard.deleteSelection(
                schematic, history, List.of(input.id()), List.of(wire)));
        assertEquals(1, history.undoDepth());
        assertEquals(List.of(output), schematic.components());
        assertTrue(schematic.wires().isEmpty());

        assertTrue(history.undo(schematic));
        assertEquals(List.of(input, output), schematic.components());
        assertEquals(List.of(wire), schematic.wires());
        assertFalse(history.canUndo());
    }

    @Test
    void fullAdderBoxDefinitionsAndSimulationSurviveCopyPaste(@TempDir Path temp) throws Exception {
        ComponentLibrary library = ComponentLibrary.load(DEFINITIONS, temp);
        ComponentDefinition fullAdder = library.get("FA");
        Schematic source = new Schematic(DEFINITIONS);
        ComponentInstance instance = source.addInstance(fullAdder, 200, 100, Facing.EAST);
        List<String> selectedIds = new ArrayList<>();
        selectedIds.add(instance.id());
        for (ComponentInstance.PinGeometry pin : instance.geometry(fullAdder).pins()) {
            String type = pin.direction() == ComponentDefinition.Direction.IN ? "input" : "output";
            PlacedComponent port = addPortAtPin(source, type, pin.x(), pin.y(), pin.name());
            selectedIds.add(port.id());
            if (type.equals("input")) {
                source.setInputValue(port.id(), true);
            }
        }

        Map<String, Value> expected = outputsByName(
                source, SchematicCompiler.compileAndSettle(source));
        String text = SchematicClipboard.encode(source, selectedIds, List.of()).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                text, DEFINITIONS, library).orElseThrow();
        Schematic target = new Schematic(DEFINITIONS);
        SchematicClipboard.paste(target, payload, null, null, 20, 20).orElseThrow();

        assertTrue(target.componentDefinitions().containsKey("FA"));
        assertEquals(1, target.componentInstances().size());
        for (PlacedComponent component : target.components()) {
            if (component.typeId().equals("input")) {
                assertFalse(target.inputValue(component.id()));
                target.setInputValue(component.id(), true);
            }
        }
        assertEquals(expected, outputsByName(target, SchematicCompiler.compileAndSettle(target)));
    }

    @Test
    void srLatchFeedbackNetsAndSimulationOutputsSurviveCopyPaste() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent set = named(source.add("input", 0, 20, Facing.EAST), source, "S_N");
        PlacedComponent reset = named(source.add("input", 0, 220, Facing.EAST), source, "R_N");
        PlacedComponent upper = source.add("nand", 200, 0, Facing.EAST);
        PlacedComponent lower = source.add("nand", 350, 200, Facing.EAST);
        Connectivity.PinPosition upperOutput = pin(source, upper, "out");
        Connectivity.PinPosition lowerOutput = pin(source, lower, "out");
        PlacedComponent q = addPortAtPin(
                source, "output", upperOutput.x(), upperOutput.y(), "Q");
        PlacedComponent qn = addPortAtPin(
                source, "output", lowerOutput.x(), lowerOutput.y(), "QN");

        connect(source, set, "out", upper, "in0");
        connect(source, reset, "out", lower, "in0");
        connect(source, upper, "out", lower, "in1");
        connect(source, lower, "out", upper, "in1");
        connect(source, upper, "out", q, "in0");
        connect(source, lower, "out", qn, "in0");

        List<String> ids = source.components().stream().map(PlacedComponent::id).toList();
        List<WireSegment> selectedWires = source.wires();
        Map<String, Value> expected = outputsByName(
                source, SchematicCompiler.compileAndSettle(source));
        String text = SchematicClipboard.encode(source, ids, selectedWires).orElseThrow();
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                text, DEFINITIONS, null).orElseThrow();
        Schematic target = new Schematic(DEFINITIONS);
        SchematicClipboard.PasteResult pasted = SchematicClipboard.paste(
                target, payload, null, null, 20, 20).orElseThrow();

        assertEquals(source.wires().size(), pasted.wires().size());
        assertEquals(expected, outputsByName(
                target, SchematicCompiler.compileAndSettle(target)));
    }

    @Test
    void repeatedPasteAdvancesByTwentyUnitsWithoutReusingIds() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent gate = source.add("and", 0, 0, Facing.EAST);
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                SchematicClipboard.encode(source, List.of(gate.id()), List.of()).orElseThrow(),
                DEFINITIONS, null).orElseThrow();
        Schematic target = new Schematic(DEFINITIONS);

        SchematicClipboard.PasteResult first = SchematicClipboard.paste(
                target, payload, null, null, 20, 20).orElseThrow();
        SchematicClipboard.PasteResult second = SchematicClipboard.paste(
                target, payload, null, null,
                first.deltaX() + 20, first.deltaY() + 20).orElseThrow();

        assertEquals(20, second.deltaX() - first.deltaX());
        assertEquals(20, second.deltaY() - first.deltaY());
        assertNotEquals(first.componentIds().getFirst(), second.componentIds().getFirst());
        assertEquals(2, target.components().size());
    }

    @Test
    void duplicateIoNamesAreReportedWithoutRenamingPorts() {
        Schematic source = new Schematic(DEFINITIONS);
        PlacedComponent copied = source.add("input", 0, 0, Facing.EAST);
        source.setLabel(copied.id(), "DATA");
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                SchematicClipboard.encode(source, List.of(copied.id()), List.of()).orElseThrow(),
                DEFINITIONS, null).orElseThrow();

        Schematic target = new Schematic(DEFINITIONS);
        PlacedComponent existing = target.add("input", 300, 300, Facing.EAST);
        target.setLabel(existing.id(), "DATA");
        SchematicClipboard.PasteResult result = SchematicClipboard.paste(
                target, payload, null, null, 20, 20).orElseThrow();

        assertTrue(result.notices().stream().anyMatch(notice -> notice.contains("DATA")));
        assertEquals("DATA", target.components().getLast().label());
    }

    @Test
    void embeddedDefinitionWinsOverDifferentLibraryHashWithNotice(
            @TempDir Path temp) throws Exception {
        ComponentLibrary library = ComponentLibrary.load(DEFINITIONS, temp);
        ComponentDefinition installed = library.get("FA");
        ComponentDefinition embedded = new ComponentDefinition(
                installed.name(), "Clipboard FA variant", installed.category(),
                installed.ports(), installed.primaryOutput(), installed.internalCircuit());
        assertNotEquals(installed.hash(), embedded.hash());

        Schematic source = new Schematic(DEFINITIONS);
        ComponentInstance instance = source.addInstance(embedded, 100, 100, Facing.EAST);
        SchematicClipboard.Payload payload = SchematicClipboard.decode(
                SchematicClipboard.encode(source, List.of(instance.id()), List.of())
                        .orElseThrow(),
                DEFINITIONS, library).orElseThrow();
        Schematic target = new Schematic(DEFINITIONS);
        SchematicClipboard.PasteResult result = SchematicClipboard.paste(
                target, payload, null, null, 20, 20).orElseThrow();

        assertTrue(result.notices().stream().anyMatch(notice ->
                notice.contains("overrides library version")));
        assertEquals(embedded.hash(), target.componentDefinitions().get("FA").hash());
    }

    @Test
    void invalidClipboardMarkerIsIgnored() {
        assertTrue(SchematicClipboard.decode(
                "OTHER-APP v1\n{}", DEFINITIONS, null).isEmpty());
        assertTrue(SchematicClipboard.decode(
                SchematicClipboard.MARKER + "\nnot json", DEFINITIONS, null).isEmpty());
        assertTrue(SchematicClipboard.decode(
                SchematicClipboard.MARKER, DEFINITIONS, null).isEmpty());
    }

    private static PlacedComponent addPortAtPin(
            Schematic schematic, String type, int pinX, int pinY, String label) {
        GateDef definition = DEFINITIONS.stream()
                .filter(candidate -> candidate.family().equals(type))
                .findFirst().orElseThrow();
        GateGeometry.Offset offset = type.equals("input")
                ? GateGeometry.outputOffset(definition, Facing.EAST)
                : GateGeometry.inputOffsets(definition, Facing.EAST).getFirst();
        PlacedComponent component = schematic.add(
                type, pinX - offset.x(), pinY - offset.y(), Facing.EAST);
        schematic.setLabel(component.id(), label);
        return component;
    }

    private static PlacedComponent named(
            PlacedComponent component, Schematic schematic, String label) {
        schematic.setLabel(component.id(), label);
        return schematic.components().stream()
                .filter(candidate -> candidate.id().equals(component.id()))
                .findFirst().orElseThrow();
    }

    private static Map<String, Value> outputsByName(
            Schematic schematic, SimulationResult result) {
        return schematic.components().stream()
                .filter(component -> component.typeId().equals("output"))
                .collect(Collectors.toMap(
                        PlacedComponent::label,
                        component -> result.outputValues().get(component.id())));
    }

    private static Connectivity.PinPosition pin(
            Schematic schematic, PlacedComponent component, String pinId) {
        return schematic.pinPositions().stream()
                .filter(position -> position.componentId().equals(component.id())
                        && position.pinId().equals(pinId))
                .findFirst().orElseThrow();
    }

    private static void connect(
            Schematic schematic,
            PlacedComponent first,
            String firstPin,
            PlacedComponent second,
            String secondPin) {
        Connectivity.PinPosition a = pin(schematic, first, firstPin);
        Connectivity.PinPosition b = pin(schematic, second, secondPin);
        if (a.x() == b.x() && a.y() == b.y()) {
            return;
        }
        if (a.x() == b.x() || a.y() == b.y()) {
            schematic.addWire(a.x(), a.y(), b.x(), b.y());
            return;
        }
        schematic.addWire(a.x(), a.y(), a.x(), b.y());
        schematic.addWire(a.x(), b.y(), b.x(), b.y());
    }
}
