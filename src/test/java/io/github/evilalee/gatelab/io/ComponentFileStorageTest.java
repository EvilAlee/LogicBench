package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.Simulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentFileStorageTest {
    private static final List<GateDef> GATES = GateLibrary.load().all();

    @Test
    void portableFileRoundTripsPortsHashAndSimulation(@TempDir Path temp) throws IOException {
        ComponentDefinition original = inverter("Invert");
        Path file = temp.resolve("Invert.lbcomp");
        ComponentLibrary.exportDefinition(original, file);

        String json = Files.readString(file);
        assertTrue(json.contains("\"formatVersion\": 1"));
        assertTrue(json.contains("\"internalCircuit\""));
        ComponentDefinition loaded = ComponentLibrary.readDefinition(file, GATES);

        assertEquals(original.name(), loaded.name());
        assertEquals(original.ports(), loaded.ports());
        assertEquals(original.primaryOutput(), loaded.primaryOutput());
        assertEquals(original.hash(), loaded.hash());
        assertEquals(original.description(), loaded.description());
        assertEquals(original.internalCircuit().components(),
                loaded.internalCircuit().components());
        assertEquals(original.internalCircuit().wires(),
                loaded.internalCircuit().wires());
        assertEquals(Value.ZERO, evaluate(original, Value.ONE));
        assertEquals(Value.ZERO, evaluate(loaded, Value.ONE));
        assertEquals(Value.ONE, evaluate(original, Value.ZERO));
        assertEquals(Value.ONE, evaluate(loaded, Value.ZERO));
    }

    @Test
    void portableFilePreservesOrderedClockPortMetadata(@TempDir Path temp) throws IOException {
        Schematic inner = new Schematic(GATES);
        inner.add(new PlacedComponent(
                "data", "input", 0, 0, Facing.EAST, Map.of(), "D"));
        inner.add(new PlacedComponent(
                "clock", "clock", 0, 40, Facing.EAST, Map.of(), "CLK"));
        inner.add(new PlacedComponent(
                "result", "output", 240, 0, Facing.EAST, Map.of(), "Q"));
        connect(inner, "data", "out", "result", "in0");
        ComponentDefinition original = ComponentDefinition.fromCircuit(
                "ClockedPass", "Clock metadata sample", "Sequential", "Q", inner);

        Path file = temp.resolve("ClockedPass.lbcomp");
        ComponentLibrary.exportDefinition(original, file);
        ComponentDefinition loaded = ComponentLibrary.readDefinition(file, GATES);

        assertEquals(original.ports(), loaded.ports());
        assertEquals(List.of(
                new ComponentDefinition.Port("D", ComponentDefinition.Direction.IN, false),
                new ComponentDefinition.Port("CLK", ComponentDefinition.Direction.IN, true),
                new ComponentDefinition.Port("Q", ComponentDefinition.Direction.OUT, false)),
                loaded.ports());
        assertEquals("Q", loaded.primaryOutput());
        assertEquals(original.hash(), loaded.hash());
    }

    @Test
    void migratesOldPerComponentExtensionToLbcomp(@TempDir Path folder) throws IOException {
        ComponentDefinition definition = inverter("LegacyFile");
        Files.writeString(folder.resolve("LegacyFile.lbcomp.json"),
                ComponentDefinitionJson.toJson(definition));

        ComponentLibrary loaded = ComponentLibrary.load(GATES, folder);

        assertEquals(definition.hash(), loaded.get("LegacyFile").hash());
        assertTrue(Files.isRegularFile(folder.resolve("LegacyFile.lbcomp")));
        assertFalse(Files.exists(folder.resolve("LegacyFile.lbcomp.json")));
    }

    @Test
    void migratesLegacySingleFileAndIsIdempotent(@TempDir Path temp) throws IOException {
        Path userFolder = temp.resolve("components");
        Path legacy = temp.resolve("components.json");
        ComponentDefinition definition = inverter("LegacyInvert");
        ComponentDefinition secondDefinition = inverter("LegacyInvertTwo");
        Files.writeString(legacy, "{\"definitions\":["
                + ComponentDefinitionJson.toJson(definition) + ","
                + ComponentDefinitionJson.toJson(secondDefinition) + "]}");

        ComponentLibrary migrated = ComponentLibrary.load(GATES, userFolder);
        assertEquals(definition.hash(), migrated.get("LegacyInvert").hash());
        assertEquals(secondDefinition.hash(), migrated.get("LegacyInvertTwo").hash());
        assertTrue(Files.isRegularFile(temp.resolve("components.json.bak")));
        assertTrue(Files.isRegularFile(userFolder.resolve("LegacyInvert.lbcomp")));
        assertTrue(Files.isRegularFile(userFolder.resolve("LegacyInvertTwo.lbcomp")));
        assertTrue(migrated.notices().stream().anyMatch(message -> message.contains("Migrated")));

        ComponentLibrary secondStart = ComponentLibrary.load(GATES, userFolder);
        assertEquals(2, secondStart.userDefinitions().size());
        assertTrue(secondStart.notices().isEmpty());
        try (var files = Files.list(userFolder)) {
            assertEquals(2L, files.filter(path -> path.toString().endsWith(".lbcomp")).count());
        }
    }

    @Test
    void malformedFilesAreSkippedWithoutBlockingGoodDefinitions(@TempDir Path folder)
            throws IOException {
        ComponentLibrary first = ComponentLibrary.load(GATES, folder);
        first.save(inverter("Good"));
        Files.writeString(folder.resolve("Broken.lbcomp"), "{ not json");
        ComponentLibrary loaded = ComponentLibrary.load(GATES, folder);

        assertTrue(loaded.find("Good").isPresent());
        assertTrue(loaded.find("Broken").isEmpty());
        assertTrue(loaded.notices().stream().anyMatch(message ->
                message.contains("Broken.lbcomp")));
    }

    @Test
    void refreshLoadsNewFilesAndReportsDuplicateLegacyNames(@TempDir Path folder)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        ComponentDefinition definition = inverter("External");
        ComponentLibrary.exportDefinition(definition, folder.resolve("External.lbcomp"));
        Files.writeString(folder.resolve("External.lbcomp.json"),
                ComponentDefinitionJson.toJson(definition));

        List<String> notices = library.refresh();

        assertEquals(definition.hash(), library.get("External").hash());
        assertTrue(notices.stream().anyMatch(message -> message.contains("duplicate")));
    }

    @Test
    void componentNamesFollowIdentifierRules(@TempDir Path folder) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        ComponentDefinition valid = inverter("Valid_2");
        library.save(valid);
        assertTrue(library.find("Valid_2").isPresent());
        assertThrows(IllegalArgumentException.class, () ->
                new ComponentDefinition("2Bad", valid.description(), valid.category(),
                        valid.ports(), valid.primaryOutput(), valid.internalCircuit()));
        assertThrows(IllegalArgumentException.class, () ->
                new ComponentDefinition("Bad-Name", valid.description(), valid.category(),
                        valid.ports(), valid.primaryOutput(), valid.internalCircuit()));
    }

    @Test
    void importCollisionsSupportSameHashReplaceKeepBothAndSkip(@TempDir Path temp)
            throws IOException {
        Path sameFolder = temp.resolve("same");
        ComponentLibrary same = ComponentLibrary.load(GATES, sameFolder);
        ComponentDefinition first = inverter("Invert");
        same.save(first);
        ComponentLibrary.ImportResult identical = same.importDefinition(first,
                (existing, incoming) -> fail("identical hash must not ask for a choice"));
        assertEquals(ComponentLibrary.ImportAction.SKIPPED, identical.action());

        Path replaceFolder = temp.resolve("replace");
        ComponentLibrary replace = ComponentLibrary.load(GATES, replaceFolder);
        replace.save(first);
        ComponentDefinition changed = reDescription(first, "A changed inverter");
        ComponentLibrary.ImportResult replaced = replace.importDefinition(changed,
                (existing, incoming) -> ComponentLibrary.ImportChoice.REPLACE);
        assertEquals(ComponentLibrary.ImportAction.REPLACED, replaced.action());
        assertEquals(changed.hash(), replace.get("Invert").hash());

        Path bothFolder = temp.resolve("both");
        ComponentLibrary both = ComponentLibrary.load(GATES, bothFolder);
        both.save(first);
        ComponentLibrary.ImportResult kept = both.importDefinition(changed,
                (existing, incoming) -> ComponentLibrary.ImportChoice.KEEP_BOTH);
        assertEquals(ComponentLibrary.ImportAction.KEPT_BOTH, kept.action());
        assertEquals("Invert2", kept.definition().name());
        assertEquals(first.hash(), both.get("Invert").hash());
        assertEquals(changed.hash(), both.get("Invert2").hash());

        Path skipFolder = temp.resolve("skip");
        ComponentLibrary skip = ComponentLibrary.load(GATES, skipFolder);
        skip.save(first);
        ComponentLibrary.ImportResult skipped = skip.importDefinition(changed,
                (existing, incoming) -> ComponentLibrary.ImportChoice.SKIP);
        assertEquals(ComponentLibrary.ImportAction.SKIPPED, skipped.action());
        assertEquals(first.hash(), skip.get("Invert").hash());
    }

    @Test
    void builtInNameCollisionIsAlwaysSkipped(@TempDir Path folder) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        ComponentDefinition builtin = library.get("HA");
        ComponentLibrary.ImportResult result = library.importDefinition(builtin,
                (existing, incoming) -> fail("built-in collision must not prompt"));
        assertEquals(ComponentLibrary.ImportAction.SKIPPED, result.action());
        assertTrue(library.userDefinitions().isEmpty());
    }

    @Test
    void exportCanBeImportedIntoAnotherLibrary(@TempDir Path temp) throws IOException {
        ComponentDefinition definition = inverter("Portable");
        Path exported = temp.resolve("Portable.lbcomp");
        ComponentLibrary.exportDefinition(definition, exported);

        ComponentLibrary destination = ComponentLibrary.load(GATES, temp.resolve("other"));
        ComponentLibrary.ImportResult imported = destination.importDefinition(
                ComponentLibrary.readDefinition(exported, GATES),
                (existing, incoming) -> ComponentLibrary.ImportChoice.SKIP);
        assertEquals(ComponentLibrary.ImportAction.ADDED, imported.action());
        assertEquals(definition.hash(), destination.get("Portable").hash());
    }

    @Test
    void deletingRemovesOnlyTheSelectedFile(@TempDir Path folder) throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        library.save(inverter("First"));
        library.save(inverter("Second"));
        Path firstFile = library.componentFile("First");
        Path secondFile = library.componentFile("Second");

        library.delete("First");

        assertFalse(Files.exists(firstFile));
        assertTrue(Files.isRegularFile(secondFile));
        assertTrue(library.find("First").isEmpty());
        assertTrue(library.find("Second").isPresent());
    }

    @Test
    void embeddedDefinitionStillOpensWithAnEmptyLibrary(@TempDir Path folder) throws IOException {
        ComponentDefinition definition = inverter("Embedded");
        Schematic document = new Schematic(GATES);
        document.addInstance(definition, 200, 200, Facing.EAST);
        String json = SchematicJson.toJson(document);

        ComponentLibrary emptyLibrary = ComponentLibrary.load(GATES, folder);
        assertTrue(emptyLibrary.userDefinitions().isEmpty());
        Schematic reopened = SchematicJson.fromJson(
                json, GATES, emptyLibrary);

        assertEquals(definition.hash(),
                reopened.componentDefinitions().get("Embedded").hash());
        assertEquals(1, reopened.componentInstances().size());
    }

    @Test
    void failedAtomicWriteLeavesNoTemporaryPartialFile(@TempDir Path folder)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        Path obstructingDirectory = folder.resolve("Blocked.lbcomp");
        Files.createDirectory(obstructingDirectory);

        assertThrows(IOException.class, () -> library.save(inverter("Blocked")));
        assertTrue(Files.isDirectory(obstructingDirectory));
        try (var children = Files.list(folder)) {
            assertTrue(children.noneMatch(path ->
                    path.getFileName().toString().endsWith(".tmp")));
        }
        assertTrue(library.find("Blocked").isEmpty());
    }

    @Test
    void reportsDependentsButKeepsDocumentCopiesIndependent(@TempDir Path folder)
            throws IOException {
        ComponentLibrary library = ComponentLibrary.load(GATES, folder);
        ComponentDefinition base = inverter("Base");
        library.save(base);

        Schematic using = new Schematic(GATES);
        using.add(new PlacedComponent(
                "wrapper-input", "input", 0, 0, Facing.EAST, Map.of(), "A"));
        using.add(new PlacedComponent(
                "wrapper-output", "output", 300, 0, Facing.EAST, Map.of(), "Y"));
        using.addInstance(base, 150, 100, Facing.EAST);
        ComponentDefinition wrapper = ComponentDefinition.fromCircuit(
                "Wrapper", "Uses Base", "Custom", "Y", using);
        library.save(wrapper);

        List<String> dependents = library.dependentsOf("Base", using);
        assertTrue(dependents.contains("Component Wrapper"));
        assertTrue(dependents.contains("Open document"));
        library.delete("Base");
        assertEquals(base.hash(), using.componentDefinitions().get("Base").hash());
    }

    private static ComponentDefinition reDescription(
            ComponentDefinition original, String description) {
        return new ComponentDefinition(original.name(), description, original.category(),
                original.ports(), original.primaryOutput(), original.internalCircuit());
    }

    private static ComponentDefinition inverter(String name) {
        Schematic circuit = new Schematic(GATES);
        circuit.add(new PlacedComponent(
                "source-a", "input", 0, 0, Facing.EAST, Map.of(), "A"));
        circuit.add(new PlacedComponent("not-a", "not", 100, 0, Facing.EAST));
        circuit.add(new PlacedComponent(
                "probe-y", "output", 220, 0, Facing.EAST, Map.of(), "Y"));
        connect(circuit, "source-a", "out", "not-a", "in0");
        connect(circuit, "not-a", "out", "probe-y", "in0");
        return ComponentDefinition.fromCircuit(name, "Logic inverter", "Custom", "Y", circuit);
    }

    private static void connect(
            Schematic schematic, String firstId, String firstPin,
            String secondId, String secondPin) {
        var first = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(firstId) && pin.pinId().equals(firstPin))
                .findFirst().orElseThrow();
        var second = schematic.pinPositions().stream()
                .filter(pin -> pin.componentId().equals(secondId) && pin.pinId().equals(secondPin))
                .findFirst().orElseThrow();
        if (first.x() == second.x() || first.y() == second.y()) {
            schematic.addWire(new WireSegment(first.x(), first.y(), second.x(), second.y()));
        } else {
            schematic.addWire(new WireSegment(first.x(), first.y(), second.x(), first.y()));
            schematic.addWire(new WireSegment(second.x(), first.y(), second.x(), second.y()));
        }
    }

    private static Value evaluate(ComponentDefinition definition, Value input) {
        SchematicCompiler.CompiledCircuit compiled =
                SchematicCompiler.compileCircuit(definition.internalCircuit());
        Simulator simulator = new Simulator(compiled.circuit());
        simulator.setInputValue(compiled.inputs().get("source-a"), input);
        simulator.settle();
        return simulator.getProbeValue(compiled.outputs().get("probe-y"));
    }
}
