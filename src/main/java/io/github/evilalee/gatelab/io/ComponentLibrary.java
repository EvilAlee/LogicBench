package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Wire;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;
import io.github.evilalee.gatelab.synth.GateOperator;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.SequentialParts;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Built-in and user-defined black-box components. Built-ins are synthesized
 * into ordinary gate schematics at runtime; user definitions are JSON files.
 */
public final class ComponentLibrary {
    private static final String USER_EXTENSION = ".lbcomp";
    private static final String LEGACY_USER_EXTENSION = ".lbcomp.json";

    private final List<GateDef> gateDefinitions;
    private final Path userDirectory;
    private final Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Path> userFiles = new LinkedHashMap<>();
    private final List<String> notices = new ArrayList<>();
    private final Set<String> builtInNames;

    private ComponentLibrary(Collection<GateDef> gateDefinitions, Path userDirectory)
            throws IOException {
        this.gateDefinitions = List.copyOf(
                Objects.requireNonNull(gateDefinitions, "gateDefinitions"));
        this.userDirectory = Objects.requireNonNull(userDirectory, "userDirectory")
                .toAbsolutePath().normalize();

        List<ComponentDefinition> builtIns = createBuiltIns(this.gateDefinitions);
        for (ComponentDefinition definition : builtIns) {
            if (definitions.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalStateException(
                        "duplicate built-in component definition: " + definition.name());
            }
        }
        builtInNames = Set.copyOf(definitions.keySet());
        migrateLegacyLibrary();
        loadUserDefinitions();
    }

    /** Loads built-ins and user definitions from the platform configuration directory. */
    public static ComponentLibrary load() {
        try {
            return load(GateLibrary.load().all(), defaultUserDirectory());
        } catch (IOException exception) {
            throw new UncheckedIOException("could not load component library", exception);
        }
    }

    /** Isolated loader used by the application and headless tests. */
    public static ComponentLibrary load(
            Collection<GateDef> gateDefinitions, Path userDirectory) throws IOException {
        return new ComponentLibrary(gateDefinitions, userDirectory);
    }

    public static Path defaultUserDirectory() {
        String operatingSystem = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT);
        String appData = System.getenv("APPDATA");
        if (operatingSystem.contains("win") && appData != null && !appData.isBlank()) {
            return Path.of(appData).resolve("LogicBench").resolve("components");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg == null || xdg.isBlank()
                ? Path.of(System.getProperty("user.home"), ".config")
                : Path.of(xdg);
        return config.resolve("logicbench").resolve("components");
    }

    public List<ComponentDefinition> all() {
        return List.copyOf(definitions.values());
    }

    public List<ComponentDefinition> builtIns() {
        return definitions.values().stream()
                .filter(definition -> builtInNames.contains(definition.name()))
                .toList();
    }

    public List<ComponentDefinition> userDefinitions() {
        return definitions.values().stream()
                .filter(definition -> !builtInNames.contains(definition.name()))
                .toList();
    }

    public Set<String> builtInNames() {
        return builtInNames;
    }

    public Path userDirectory() {
        return userDirectory;
    }

    public Optional<ComponentDefinition> find(String name) {
        return Optional.ofNullable(definitions.get(
                Objects.requireNonNull(name, "name")));
    }

    public ComponentDefinition get(String name) {
        return find(name).orElseThrow(() ->
                new IllegalArgumentException("unknown component definition: " + name));
    }

    /** Saves or replaces a user definition without allowing built-ins to be shadowed. */
    public Path save(ComponentDefinition definition) throws IOException {
        Objects.requireNonNull(definition, "definition");
        validateUserName(definition.name());
        if (builtInNames.contains(definition.name())) {
            throw new IllegalArgumentException(
                    "built-in component definitions cannot be overwritten: " + definition.name());
        }
        Files.createDirectories(userDirectory);
        Path target = userDirectory.resolve(definition.name() + USER_EXTENSION);
        writeAtomically(target, ComponentFileJson.toJson(definition));
        definitions.put(definition.name(), definition);
        userFiles.put(definition.name(), target);
        return target;
    }

    public static void exportDefinition(ComponentDefinition definition, Path file) throws IOException {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(file, "file");
        Path target = file.getFileName().toString().endsWith(USER_EXTENSION)
                ? file : file.resolveSibling(file.getFileName() + USER_EXTENSION);
        writeAtomically(target, ComponentFileJson.toJson(definition));
    }

    public static ComponentDefinition readDefinition(Path file, Collection<GateDef> gates)
            throws IOException {
        return ComponentFileJson.fromJson(Files.readString(file, StandardCharsets.UTF_8), gates);
    }

    public enum ImportChoice { REPLACE, KEEP_BOTH, SKIP }
    public enum ImportAction { ADDED, REPLACED, KEPT_BOTH, SKIPPED }
    @FunctionalInterface
    public interface ImportDecision {
        ImportChoice choose(ComponentDefinition existing, ComponentDefinition incoming);
    }
    public record ImportResult(ImportAction action, ComponentDefinition definition, Path file) { }

    /** Imports one definition; conflict policy is supplied by the caller for headless testing. */
    public ImportResult importDefinition(ComponentDefinition incoming, ImportDecision decision)
            throws IOException {
        Objects.requireNonNull(incoming, "incoming");
        validateUserName(incoming.name());
        if (builtInNames.contains(incoming.name())) {
            return new ImportResult(ImportAction.SKIPPED, incoming, null);
        }
        ComponentDefinition existing = definitions.get(incoming.name());
        if (existing == null) {
            Path file = save(incoming);
            return new ImportResult(ImportAction.ADDED, incoming, file);
        }
        if (existing.hash().equals(incoming.hash())) {
            return new ImportResult(ImportAction.SKIPPED, existing, null);
        }
        ImportChoice choice = Objects.requireNonNull(
                decision, "decision").choose(existing, incoming);
        if (choice == ImportChoice.SKIP) {
            return new ImportResult(ImportAction.SKIPPED, existing, null);
        }
        if (choice == ImportChoice.REPLACE) {
            Path file = save(incoming);
            return new ImportResult(ImportAction.REPLACED, incoming, file);
        }
        String base = incoming.name();
        int suffix = 2;
        String unique;
        do {
            unique = base + suffix++;
        } while (definitions.containsKey(unique)
                || Files.exists(userDirectory.resolve(unique + USER_EXTENSION)));
        ComponentDefinition renamed = rename(incoming, unique);
        Path file = save(renamed);
        return new ImportResult(ImportAction.KEPT_BOTH, renamed, file);
    }

    /** Refreshes user files while preserving the runtime-generated built-ins. */
    public List<String> refresh() {
        definitions.keySet().removeIf(name -> !builtInNames.contains(name));
        userFiles.clear();
        notices.clear();
        migrateLegacyLibrary();
        loadUserDefinitions();
        return notices();
    }

    public List<String> notices() {
        return List.copyOf(notices);
    }

    public Path componentFile(String name) {
        if (builtInNames.contains(name) || !definitions.containsKey(name)) {
            throw new IllegalArgumentException("not a user component: " + name);
        }
        return userFiles.getOrDefault(name, userDirectory.resolve(name + USER_EXTENSION));
    }

    /** Deletes exactly the file from which the selected user definition was loaded. */
    public Path delete(String name) throws IOException {
        if (builtInNames.contains(name)) {
            throw new IllegalArgumentException("built-in components cannot be deleted: " + name);
        }
        ComponentDefinition existing = definitions.get(name);
        if (existing == null) {
            throw new IllegalArgumentException("unknown user component: " + name);
        }
        Path file = userFiles.get(name);
        if (file == null) {
            throw new IOException("The component is still being served from the legacy library file; "
                    + "individual deletion is unavailable until that library can be migrated.");
        }
        Files.deleteIfExists(file);
        definitions.remove(name);
        userFiles.remove(name);
        return file;
    }

    /** Lists library definitions and the open document that directly or transitively use a name. */
    public List<String> dependentsOf(String name, Schematic openDocument) {
        List<String> result = new ArrayList<>();
        for (ComponentDefinition candidate : userDefinitions()) {
            if (!candidate.name().equals(name)
                    && schematicUses(candidate.internalCircuit(), name, new java.util.HashSet<>())) {
                result.add("Component " + candidate.name());
            }
        }
        if (openDocument != null
                && schematicUses(openDocument, name, new java.util.HashSet<>())) {
            result.add("Open document");
        }
        return List.copyOf(result);
    }

    private static boolean schematicUses(
            Schematic schematic, String name, Set<String> visited) {
        for (var instance : schematic.componentInstances()) {
            if (instance.definitionName().equals(name)) {
                return true;
            }
            ComponentDefinition embedded = schematic.componentDefinitionFor(instance);
            String identity = embedded.name() + "@" + embedded.hash();
            if (visited.add(identity)
                    && schematicUses(embedded.internalCircuit(), name, visited)) {
                return true;
            }
        }
        return false;
    }

    private void loadUserDefinitions() {
        if (!Files.isDirectory(userDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(userDirectory)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(USER_EXTENSION) || name.endsWith(LEGACY_USER_EXTENSION);
                    })
                    .sorted()
                    .toList()) {
                ComponentDefinition definition;
                try {
                    definition = readDefinition(file, gateDefinitions);
                } catch (IOException | RuntimeException exception) {
                    notices.add("Skipped " + file.getFileName() + ": "
                            + safeMessage(exception));
                    continue;
                }
                String expected = definition.name() + USER_EXTENSION;
                String legacyExpected = definition.name() + LEGACY_USER_EXTENSION;
                String actual = file.getFileName().toString();
                if (!actual.equals(expected) && !actual.equals(legacyExpected)) {
                    notices.add("Skipped " + actual + ": expected " + expected);
                    continue;
                }
                if (builtInNames.contains(definition.name())) {
                    notices.add("Skipped " + actual + ": built-in name "
                            + definition.name() + " cannot be replaced");
                    continue;
                }
                if (definitions.containsKey(definition.name())) {
                    notices.add("Skipped " + actual + ": duplicate component name "
                            + definition.name());
                    continue;
                }
                if (actual.equals(legacyExpected)) {
                    Path migratedFile = userDirectory.resolve(expected);
                    try {
                        try {
                            Files.move(file, migratedFile, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException exception) {
                            Files.move(file, migratedFile);
                        }
                        file = migratedFile;
                    } catch (IOException exception) {
                        notices.add("Could not rename legacy file " + actual
                                + "; it remains usable: " + safeMessage(exception));
                    }
                }
                definitions.put(definition.name(), definition);
                userFiles.put(definition.name(), file);
            }
        } catch (IOException exception) {
            notices.add("Could not scan component library folder: " + safeMessage(exception));
        }
    }

    private void migrateLegacyLibrary() {
        if (Files.isDirectory(userDirectory)) {
            return;
        }
        Path legacy = null;
        if (Files.isRegularFile(userDirectory)) {
            legacy = userDirectory;
        } else {
            for (String filename : List.of(
                    "components.json", "component-library.json", "component_library.json",
                    "componentLibrary.json", "library.json", "user-library.json",
                    "user-components.json", "components.lbcomp.json", "components.lbcomp")) {
                Path candidate = userDirectory.resolveSibling(filename);
                if (Files.isRegularFile(candidate)) {
                    legacy = candidate;
                    break;
                }
            }
        }
        if (legacy == null) {
            return;
        }

        List<ComponentDefinition> migrated;
        try {
            migrated = readLegacyLibrary(legacy);
        } catch (IOException | RuntimeException exception) {
            notices.add("Could not migrate " + legacy.getFileName()
                    + "; the old library file was kept: " + safeMessage(exception));
            return;
        }

        Path parent = userDirectory.getParent();
        Path staging = null;
        Path backup = uniqueBackupPath(legacy);
        try {
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, ".components-migration-");
            Set<String> names = new java.util.LinkedHashSet<>();
            for (ComponentDefinition original : migrated) {
                ComponentDefinition definition = original;
                String name = definition.name();
                if (builtInNames.contains(name) || !names.add(name)) {
                    int suffix = 2;
                    String candidate;
                    do {
                        candidate = name + suffix++;
                    } while (builtInNames.contains(candidate) || !names.add(candidate));
                    definition = rename(definition, candidate);
                }
                writeAtomically(staging.resolve(definition.name() + USER_EXTENSION),
                        ComponentFileJson.toJson(definition));
            }
            Files.move(legacy, backup);
            try {
                moveDirectory(staging, userDirectory);
                staging = null;
            } catch (IOException exception) {
                Files.move(backup, legacy);
                throw exception;
            }
            notices.add("Migrated " + migrated.size()
                    + " component(s) to " + userDirectory + ".");
        } catch (IOException | RuntimeException exception) {
            if (staging != null) {
                deleteTreeQuietly(staging);
            }
            for (ComponentDefinition definition : migrated) {
                if (!builtInNames.contains(definition.name())) {
                    definitions.putIfAbsent(definition.name(), definition);
                }
            }
            notices.add("Could not finish migration from " + legacy.getFileName()
                    + "; the old library remains available: " + safeMessage(exception));
        }
    }

    private List<ComponentDefinition> readLegacyLibrary(Path file) throws IOException {
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(
                Files.readString(file, StandardCharsets.UTF_8));
        List<com.google.gson.JsonElement> entries = new ArrayList<>();
        if (root.isJsonArray()) {
            root.getAsJsonArray().forEach(entries::add);
        } else if (root.isJsonObject()) {
            com.google.gson.JsonObject object = root.getAsJsonObject();
            com.google.gson.JsonElement wrapped = object.has("definitions")
                    ? object.get("definitions")
                    : object.has("components") ? object.get("components")
                    : object.has("userComponents") ? object.get("userComponents")
                    : object.has("library") ? object.get("library") : null;
            if (wrapped != null && wrapped.isJsonArray()) {
                wrapped.getAsJsonArray().forEach(entries::add);
            } else if (wrapped != null && wrapped.isJsonObject()) {
                wrapped.getAsJsonObject().entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonObject()) {
                        entries.add(entry.getValue());
                    }
                });
            } else if (wrapped != null) {
                throw new IOException("legacy definitions must be an array or object");
            } else if (object.has("name")) {
                entries.add(root);
            } else {
                object.entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonObject()) {
                        entries.add(entry.getValue());
                    }
                });
            }
        } else {
            throw new IOException("legacy component library must be a JSON object or array");
        }
        List<ComponentDefinition> result = new ArrayList<>();
        for (com.google.gson.JsonElement item : entries) {
            if (!item.isJsonObject()) {
                throw new IOException("legacy component entry must be an object");
            }
            String json = item.toString();
            ComponentDefinition definition;
            try {
                definition = ComponentFileJson.fromJson(json, gateDefinitions);
            } catch (RuntimeException first) {
                definition = ComponentDefinitionJson.fromJson(json, gateDefinitions);
            }
            result.add(definition);
        }
        return List.copyOf(result);
    }

    private static void validateUserName(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "component name must start with a letter and contain only letters, digits and underscores");
        }
    }

    private static ComponentDefinition rename(ComponentDefinition definition, String name) {
        return new ComponentDefinition(name, definition.description(), definition.category(),
                definition.ports(), definition.primaryOutput(), definition.internalCircuit());
    }

    private static Path uniqueBackupPath(Path legacy) {
        Path backup = legacy.resolveSibling(legacy.getFileName() + ".bak");
        int suffix = 2;
        while (Files.exists(backup)) {
            backup = legacy.resolveSibling(legacy.getFileName() + ".bak" + suffix++);
        }
        return backup;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTreeQuietly(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Keep the original legacy library intact if staging cleanup itself fails.
        }
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static List<ComponentDefinition> createBuiltIns(List<GateDef> gateDefinitions) {
        List<ComponentDefinition> result = new ArrayList<>();
        result.add(sequentialBuiltIn(gateDefinitions, "DLATCH",
                List.of("D", "EN"), null));
        result.add(sequentialBuiltIn(gateDefinitions, "DFF",
                List.of("D", "CLK"), "CLK"));
        result.add(sequentialBuiltIn(gateDefinitions, "TFF",
                List.of("T", "CLK"), "CLK"));
        result.add(sequentialBuiltIn(gateDefinitions, "JKFF",
                List.of("J", "K", "CLK"), "CLK"));

        ExpressionCompiler.Compilation halfAdder = ExpressionCompiler.compile(
                "S = A ^ B; C = A & B", false, true);
        result.add(new ComponentDefinition(
                "HA", "Half adder", "Arithmetic",
                ports(List.of("A", "B"), null, List.of("S", "C")), null,
                new SchematicLayout(gateDefinitions).layout(halfAdder.netlist()).schematic()));

        ExpressionCompiler.Compilation fullAdder = ExpressionCompiler.compile(
                "S = (A ^ B) ^ CIN; COUT = (A & B) | (A & CIN) | (B & CIN)",
                false, true);
        result.add(new ComponentDefinition(
                "FA", "Full adder", "Arithmetic",
                ports(List.of("A", "B", "CIN"), null, List.of("S", "COUT")), null,
                new SchematicLayout(gateDefinitions).layout(fullAdder.netlist()).schematic()));
        result.addAll(createTtlBuiltIns(gateDefinitions));
        return List.copyOf(result);
    }

    private record ChipPortSpec(
            String name, int pin, ComponentDefinition.Direction direction) {
    }

    private record ChipFunction(
            String output, List<String> inputs, GateOperator operation) {
        private ChipFunction {
            inputs = List.copyOf(inputs);
        }
    }

    private record ChipOutputExpression(String portName, String expression) {
        private ChipOutputExpression {
            Objects.requireNonNull(portName, "portName");
            Objects.requireNonNull(expression, "expression");
        }
    }

    private static ChipPortSpec in(String name, int pin) {
        return new ChipPortSpec(name, pin, ComponentDefinition.Direction.IN);
    }

    private static ChipPortSpec out(String name, int pin) {
        return new ChipPortSpec(name, pin, ComponentDefinition.Direction.OUT);
    }

    private static ChipFunction fn(String output, GateOperator operation, String... inputs) {
        return new ChipFunction(output, List.of(inputs), operation);
    }

    private static List<ComponentDefinition> createTtlBuiltIns(List<GateDef> gateDefinitions) {
        List<ComponentDefinition> chips = new ArrayList<>();
        List<ChipPortSpec> quad14 = List.of(
                in("1A", 1), in("1B", 2), out("1Y", 3),
                in("2A", 4), in("2B", 5), out("2Y", 6),
                in("GND", 7), out("3Y", 8), in("3A", 9), in("3B", 10),
                out("4Y", 11), in("4A", 12), in("4B", 13), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7400", "Quad 2-input NAND gates", quad14,
                List.of(
                        fn("1Y", GateOperator.NAND, "1A", "1B"),
                        fn("2Y", GateOperator.NAND, "2A", "2B"),
                        fn("3Y", GateOperator.NAND, "3A", "3B"),
                        fn("4Y", GateOperator.NAND, "4A", "4B"))));

        List<ChipPortSpec> nor14 = List.of(
                out("1Y", 1), in("1A", 2), in("1B", 3),
                out("2Y", 4), in("2A", 5), in("2B", 6),
                in("GND", 7), in("3A", 8), in("3B", 9), out("3Y", 10),
                in("4A", 11), in("4B", 12), out("4Y", 13), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7402", "Quad 2-input NOR gates", nor14,
                List.of(
                        fn("1Y", GateOperator.NOR, "1A", "1B"),
                        fn("2Y", GateOperator.NOR, "2A", "2B"),
                        fn("3Y", GateOperator.NOR, "3A", "3B"),
                        fn("4Y", GateOperator.NOR, "4A", "4B"))));

        List<ChipPortSpec> inverter14 = List.of(
                in("1A", 1), out("1Y", 2), in("2A", 3), out("2Y", 4),
                in("3A", 5), out("3Y", 6), in("GND", 7),
                out("4Y", 8), in("4A", 9), out("5Y", 10),
                in("5A", 11), out("6Y", 12), in("6A", 13), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7404", "Hex inverter", inverter14,
                List.of(
                        fn("1Y", GateOperator.NOT, "1A"),
                        fn("2Y", GateOperator.NOT, "2A"),
                        fn("3Y", GateOperator.NOT, "3A"),
                        fn("4Y", GateOperator.NOT, "4A"),
                        fn("5Y", GateOperator.NOT, "5A"),
                        fn("6Y", GateOperator.NOT, "6A"))));

        chips.add(chip(gateDefinitions, "7408", "Quad 2-input AND gates", quad14,
                List.of(
                        fn("1Y", GateOperator.AND, "1A", "1B"),
                        fn("2Y", GateOperator.AND, "2A", "2B"),
                        fn("3Y", GateOperator.AND, "3A", "3B"),
                        fn("4Y", GateOperator.AND, "4A", "4B"))));
        chips.add(chip(gateDefinitions, "7432", "Quad 2-input OR gates", quad14,
                List.of(
                        fn("1Y", GateOperator.OR, "1A", "1B"),
                        fn("2Y", GateOperator.OR, "2A", "2B"),
                        fn("3Y", GateOperator.OR, "3A", "3B"),
                        fn("4Y", GateOperator.OR, "4A", "4B"))));
        chips.add(chip(gateDefinitions, "7486", "Quad 2-input XOR gates", quad14,
                List.of(
                        fn("1Y", GateOperator.XOR, "1A", "1B"),
                        fn("2Y", GateOperator.XOR, "2A", "2B"),
                        fn("3Y", GateOperator.XOR, "3A", "3B"),
                        fn("4Y", GateOperator.XOR, "4A", "4B"))));

        List<ChipPortSpec> triple14 = List.of(
                in("1A", 1), in("1B", 2), in("2A", 3), in("2B", 4),
                in("2C", 5), out("2Y", 6), in("GND", 7), out("3Y", 8),
                in("3A", 9), in("3B", 10), in("3C", 11), out("1Y", 12),
                in("1C", 13), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7410", "Triple 3-input NAND gates", triple14,
                List.of(
                        fn("1Y", GateOperator.NAND, "1A", "1B", "1C"),
                        fn("2Y", GateOperator.NAND, "2A", "2B", "2C"),
                        fn("3Y", GateOperator.NAND, "3A", "3B", "3C"))));
        chips.add(chip(gateDefinitions, "7411", "Triple 3-input AND gates", triple14,
                List.of(
                        fn("1Y", GateOperator.AND, "1A", "1B", "1C"),
                        fn("2Y", GateOperator.AND, "2A", "2B", "2C"),
                        fn("3Y", GateOperator.AND, "3A", "3B", "3C"))));
        chips.add(chip(gateDefinitions, "7427", "Triple 3-input NOR gates", triple14,
                List.of(
                        fn("1Y", GateOperator.NOR, "1A", "1B", "1C"),
                        fn("2Y", GateOperator.NOR, "2A", "2B", "2C"),
                        fn("3Y", GateOperator.NOR, "3A", "3B", "3C"))));

        List<ChipPortSpec> dual4 = List.of(
                in("1A", 1), in("1B", 2), in("1C", 4), in("1D", 5),
                out("1Y", 6), in("GND", 7), out("2Y", 8), in("2A", 9),
                in("2B", 10), in("2C", 12), in("2D", 13), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7420", "Dual 4-input NAND gates", dual4,
                List.of(
                        fn("1Y", GateOperator.NAND, "1A", "1B", "1C", "1D"),
                        fn("2Y", GateOperator.NAND, "2A", "2B", "2C", "2D"))));

        List<ChipPortSpec> nand8 = List.of(
                in("A", 1), in("B", 2), in("C", 3), in("D", 4), in("E", 5),
                in("F", 6), in("GND", 7), out("Y", 8), in("G", 11),
                in("H", 12), in("VCC", 14));
        chips.add(chip(gateDefinitions, "7430", "8-input NAND gate", nand8,
                List.of(fn("Y", GateOperator.NAND, "A", "B", "C", "D", "E", "F", "G", "H"))));

        List<ChipPortSpec> decoder7442 = List.of(
                out("0", 1), out("1", 2), out("2", 3), out("3", 4),
                out("4", 5), out("5", 6), out("6", 7), in("GND", 8),
                out("7", 9), out("8", 10), out("9", 11), in("D", 12),
                in("C", 13), in("B", 14), in("A", 15), in("VCC", 16));
        List<ChipOutputExpression> decoderRows = new ArrayList<>();
        List<String> inputOrder = List.of("D", "C", "B", "A");
        for (int decimal = 0; decimal < 10; decimal++) {
            List<String> mismatches = new ArrayList<>(4);
            for (int bit = 3; bit >= 0; bit--) {
                String input = inputOrder.get(3 - bit);
                mismatches.add(((decimal >>> bit) & 1) == 0 ? input : "!" + input);
            }
            decoderRows.add(new ChipOutputExpression(
                    Integer.toString(decimal), String.join(" | ", mismatches)));
        }
        chips.add(expressionChip(gateDefinitions, "7442",
                "BCD-to-decimal decoder with ten active-low outputs", decoder7442,
                decoderRows));

        List<ChipPortSpec> decoder74138 = List.of(
                in("A", 1), in("B", 2), in("C", 3), in("G2A", 4),
                in("G2B", 5), in("G1", 6), out("Y7", 7), in("GND", 8),
                out("Y6", 9), out("Y5", 10), out("Y4", 11), out("Y3", 12),
                out("Y2", 13), out("Y1", 14), out("Y0", 15), in("VCC", 16));
        List<ChipOutputExpression> decoder138Rows = new ArrayList<>();
        List<String> decoder138Inputs = List.of("A", "B", "C");
        for (int selected = 0; selected < 8; selected++) {
            List<String> highConditions = new ArrayList<>(
                    List.of("!G1", "G2A", "G2B"));
            for (int bit = 0; bit < decoder138Inputs.size(); bit++) {
                String input = decoder138Inputs.get(bit);
                highConditions.add(((selected >>> bit) & 1) == 0
                        ? input : "!" + input);
            }
            decoder138Rows.add(new ChipOutputExpression(
                    "Y" + selected, String.join(" | ", highConditions)));
        }
        chips.add(expressionChip(gateDefinitions, "74138",
                "3-to-8 decoder/demultiplexer with active-low outputs",
                decoder74138, decoder138Rows));

        List<ChipPortSpec> decoder74139 = List.of(
                in("1G", 1), in("1A", 2), in("1B", 3),
                out("1Y0", 4), out("1Y1", 5), out("1Y2", 6), out("1Y3", 7),
                in("GND", 8), out("2Y3", 9), out("2Y2", 10),
                out("2Y1", 11), out("2Y0", 12), in("2B", 13),
                in("2A", 14), in("2G", 15), in("VCC", 16));
        List<ChipOutputExpression> decoder139Rows = List.of(
                new ChipOutputExpression("1Y0", "1G | 1B | 1A"),
                new ChipOutputExpression("1Y1", "1G | 1B | !1A"),
                new ChipOutputExpression("1Y2", "1G | !1B | 1A"),
                new ChipOutputExpression("1Y3", "1G | !1B | !1A"),
                new ChipOutputExpression("2Y0", "2G | 2B | 2A"),
                new ChipOutputExpression("2Y1", "2G | 2B | !2A"),
                new ChipOutputExpression("2Y2", "2G | !2B | 2A"),
                new ChipOutputExpression("2Y3", "2G | !2B | !2A"));
        chips.add(expressionChip(gateDefinitions, "74139",
                "Dual 2-to-4 decoders/demultiplexers with active-low outputs",
                decoder74139, decoder139Rows));

        List<ChipPortSpec> priority148 = List.of(
                in("4", 1), in("5", 2), in("6", 3), in("7", 4),
                in("EI", 5), out("A2", 6), out("A1", 7), in("GND", 8),
                out("A0", 9), in("0", 10), in("1", 11), in("2", 12),
                in("3", 13), out("GS", 14), out("EO", 15), in("VCC", 16));
        List<String> priorityTerms = new ArrayList<>();
        for (int selected = 0; selected < 8; selected++) {
            List<String> conditions = new ArrayList<>();
            conditions.add("!EI");
            for (int higherPriority = 0; higherPriority < selected; higherPriority++) {
                conditions.add(Integer.toString(higherPriority));
            }
            conditions.add("!" + selected);
            priorityTerms.add("(" + String.join(" & ", conditions) + ")");
        }
        String anyPriorityRequest = "(" + String.join(" | ", priorityTerms) + ")";
        String a2Request = "(" + String.join(" | ",
                priorityTerms.subList(4, 8)) + ")";
        String a1Request = "(" + String.join(" | ", List.of(
                priorityTerms.get(2), priorityTerms.get(3),
                priorityTerms.get(6), priorityTerms.get(7))) + ")";
        String a0Request = "(" + String.join(" | ", List.of(
                priorityTerms.get(1), priorityTerms.get(3),
                priorityTerms.get(5), priorityTerms.get(7))) + ")";
        List<ChipOutputExpression> priority148Rows = List.of(
                new ChipOutputExpression("A2", "!" + a2Request),
                new ChipOutputExpression("A1", "!" + a1Request),
                new ChipOutputExpression("A0", "!" + a0Request),
                new ChipOutputExpression("GS", "!" + anyPriorityRequest),
                new ChipOutputExpression("EO", "EI | " + anyPriorityRequest));
        chips.add(expressionChip(gateDefinitions, "74148",
                "8-to-3 priority encoder with active-low data inputs and encoded outputs",
                priority148, priority148Rows));

        List<ChipPortSpec> mux151 = List.of(
                in("D3", 1), in("D2", 2), in("D1", 3), in("D0", 4),
                out("Y", 5), out("W", 6), in("G", 7), in("GND", 8),
                in("C", 9), in("B", 10), in("A", 11), in("D7", 12),
                in("D6", 13), in("D5", 14), in("D4", 15), in("VCC", 16));
        List<String> muxTerms = new ArrayList<>();
        for (int selected = 0; selected < 8; selected++) {
            List<String> conditions = new ArrayList<>();
            conditions.add("!G");
            conditions.add(((selected >>> 2) & 1) == 1 ? "C" : "!C");
            conditions.add(((selected >>> 1) & 1) == 1 ? "B" : "!B");
            conditions.add((selected & 1) == 1 ? "A" : "!A");
            conditions.add("D" + selected);
            muxTerms.add("(" + String.join(" & ", conditions) + ")");
        }
        String mux151Y = "(" + String.join(" | ", muxTerms) + ")";
        chips.add(expressionChip(gateDefinitions, "74151",
                "8-to-1 multiplexer with complementary outputs and active-low strobe",
                mux151, List.of(
                        new ChipOutputExpression("Y", mux151Y),
                        new ChipOutputExpression("W", "!(" + mux151Y + ")"))));

        List<ChipPortSpec> mux153 = List.of(
                in("1G", 1), in("B", 2), in("1C3", 3), in("1C2", 4),
                in("1C1", 5), in("1C0", 6), out("1Y", 7), in("GND", 8),
                out("2Y", 9), in("2C0", 10), in("2C1", 11), in("2C2", 12),
                in("2C3", 13), in("A", 14), in("2G", 15), in("VCC", 16));
        List<String> mux153FirstTerms = new ArrayList<>();
        List<String> mux153SecondTerms = new ArrayList<>();
        for (int selected = 0; selected < 4; selected++) {
            String selectB = ((selected >>> 1) & 1) == 1 ? "B" : "!B";
            String selectA = (selected & 1) == 1 ? "A" : "!A";
            mux153FirstTerms.add("(!1G & " + selectB + " & " + selectA
                    + " & 1C" + selected + ")");
            mux153SecondTerms.add("(!2G & " + selectB + " & " + selectA
                    + " & 2C" + selected + ")");
        }
        chips.add(expressionChip(gateDefinitions, "74153",
                "Dual 4-to-1 multiplexers with shared select inputs and active-low strobes",
                mux153, List.of(
                        new ChipOutputExpression("1Y",
                                "(" + String.join(" | ", mux153FirstTerms) + ")"),
                        new ChipOutputExpression("2Y",
                                "(" + String.join(" | ", mux153SecondTerms) + ")"))));

        List<ChipPortSpec> mux157 = List.of(
                in("A/B", 1), in("1A", 2), in("1B", 3), out("1Y", 4),
                in("2A", 5), in("2B", 6), out("2Y", 7), in("GND", 8),
                out("3Y", 9), in("3B", 10), in("3A", 11), out("4Y", 12),
                in("4B", 13), in("4A", 14), in("G", 15), in("VCC", 16));
        List<ChipOutputExpression> mux157Outputs = new ArrayList<>();
        for (int channel = 1; channel <= 4; channel++) {
            String muxExpression = "!G & ((!A/B & " + channel + "A) | (A/B & "
                    + channel + "B))";
            mux157Outputs.add(new ChipOutputExpression(
                    channel + "Y", "(" + muxExpression + ")"));
        }
        chips.add(expressionChip(gateDefinitions, "74157",
                "Quad 2-to-1 multiplexer with shared A/B select and active-low strobe",
                mux157, mux157Outputs));
        return List.copyOf(chips);
    }

    /**
     * Synthesizes a multi-output combinational chip through the existing expression
     * compiler and schematic layout path, then adds the two physical supply ports.
     */
    private static ComponentDefinition expressionChip(
            List<GateDef> gateDefinitions,
            String name,
            String description,
            List<ChipPortSpec> chipPorts,
            List<ChipOutputExpression> outputExpressions) {
        List<String> functionalInputs = chipPorts.stream()
                .filter(port -> port.direction() == ComponentDefinition.Direction.IN)
                .map(ChipPortSpec::name)
                .filter(port -> !port.equals("VCC") && !port.equals("GND"))
                .toList();
        if (functionalInputs.isEmpty() || outputExpressions.isEmpty()) {
            throw new IllegalArgumentException("chip requires at least one signal input and output");
        }

        Map<String, String> inputAliases = new LinkedHashMap<>();
        for (int index = 0; index < functionalInputs.size(); index++) {
            inputAliases.put(functionalInputs.get(index), "P" + index);
        }

        Map<String, String> outputAliases = new LinkedHashMap<>();
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < outputExpressions.size(); index++) {
            ChipOutputExpression output = outputExpressions.get(index);
            String alias = "O" + index;
            outputAliases.put(alias, output.portName());
            String safeExpression = output.expression();
            // Physical port names may begin with digits (e.g. the 74139's 1G).
            // Translate whole identifiers to expression-safe aliases; the public
            // ComponentDefinition still exposes the exact datasheet pin names.
            for (Map.Entry<String, String> inputAlias : inputAliases.entrySet()) {
                String token = "(?<![A-Za-z0-9_])"
                        + java.util.regex.Pattern.quote(inputAlias.getKey())
                        + "(?![A-Za-z0-9_])";
                safeExpression = safeExpression.replaceAll(token, inputAlias.getValue());
            }
            source.append(alias).append(" = ").append(safeExpression).append(";\n");
        }
        ExpressionCompiler.Compilation compilation =
                ExpressionCompiler.compile(source.toString(), false, true);
        if (compilation.netlist().inputs().size() != inputAliases.size()
                || !new java.util.LinkedHashSet<>(compilation.netlist().inputs())
                        .equals(new java.util.LinkedHashSet<>(inputAliases.values()))) {
            throw new IllegalStateException(
                    name + " expression inputs do not match the physical signal ports");
        }

        SchematicLayout.LayoutResult layout =
                new SchematicLayout(gateDefinitions).layout(compilation.netlist());
        Schematic internal = layout.schematic();
        for (String input : functionalInputs) {
            internal.setLabel(layout.inputComponentIds().get(inputAliases.get(input)), input);
        }
        outputAliases.forEach((alias, portName) ->
                internal.setLabel(layout.outputComponentIds().get(alias), portName));

        int bottom = internal.pinPositions().stream()
                .mapToInt(Connectivity.PinPosition::y).max().orElse(0);
        internal.add(new PlacedComponent(
                "chip-vcc", "input", 0, bottom + 80,
                Facing.EAST, Map.of(), "VCC"));
        internal.add(new PlacedComponent(
                "chip-gnd", "input", 0, bottom + 120,
                Facing.EAST, Map.of(), "GND"));

        List<ComponentDefinition.Port> ports = new ArrayList<>();
        for (ChipPortSpec port : chipPorts) {
            ports.add(new ComponentDefinition.Port(
                    port.name(), port.direction(), false, port.pin()));
        }
        return new ComponentDefinition(
                name, description, "TTL 74xx", ports, null, "DIP16", internal);
    }

    private static ComponentDefinition chip(
            List<GateDef> gateDefinitions,
            String name,
            String description,
            List<ChipPortSpec> chipPorts,
            List<ChipFunction> functions) {
        List<String> functionalInputs = chipPorts.stream()
                .filter(port -> port.direction() == ComponentDefinition.Direction.IN)
                .map(ChipPortSpec::name)
                .filter(port -> !port.equals("VCC") && !port.equals("GND"))
                .toList();
        List<String> functionalOutputs = chipPorts.stream()
                .filter(port -> port.direction() == ComponentDefinition.Direction.OUT)
                .map(ChipPortSpec::name)
                .toList();
        Map<String, String> inputAlias = new LinkedHashMap<>();
        for (int index = 0; index < functionalInputs.size(); index++) {
            inputAlias.put(functionalInputs.get(index), "P" + index);
        }

        Map<String, String> outputSource = new LinkedHashMap<>();
        List<Netlist.Gate> gates = new ArrayList<>();
        int gateNumber = 0;
        for (ChipFunction function : functions) {
            String current = inputAlias.get(function.inputs().getFirst());
            for (int index = 1; index < function.inputs().size(); index++) {
                boolean finalStep = index == function.inputs().size() - 1;
                GateOperator operator = function.operation();
                // Multi-input NAND/NOR use a positive reduction followed by the final inversion.
                if (function.inputs().size() > 2
                        && (function.operation() == GateOperator.NAND
                                || function.operation() == GateOperator.NOR)
                        && !finalStep) {
                    operator = function.operation() == GateOperator.NAND
                            ? GateOperator.AND : GateOperator.OR;
                }
                String next = inputAlias.get(function.inputs().get(index));
                String gateId = "g" + gateNumber++;
                gates.add(new Netlist.Gate(gateId, operator, List.of(current, next)));
                current = gateId;
            }
            if (function.operation() == GateOperator.NOT) {
                String gateId = "g" + gateNumber++;
                gates.add(new Netlist.Gate(gateId, GateOperator.NOT, List.of(current)));
                current = gateId;
            }
            outputSource.put(function.output(), current);
        }

        List<Netlist.Output> outputs = new ArrayList<>();
        Map<String, String> outputAlias = new LinkedHashMap<>();
        for (int index = 0; index < functionalOutputs.size(); index++) {
            String portName = functionalOutputs.get(index);
            String alias = "O" + index;
            outputAlias.put(portName, alias);
            String source = outputSource.get(portName);
            if (source == null) {
                throw new IllegalStateException(name + " has no logic function for output " + portName);
            }
            outputs.add(new Netlist.Output(alias, source));
        }
        Netlist netlist = new Netlist(
                new ArrayList<>(inputAlias.values()), outputs, gates);
        SchematicLayout.LayoutResult layout = new SchematicLayout(gateDefinitions).layout(netlist);
        Schematic internal = layout.schematic();
        inputAlias.forEach((portName, alias) ->
                internal.setLabel(layout.inputComponentIds().get(alias), portName));
        outputAlias.forEach((portName, alias) ->
                internal.setLabel(layout.outputComponentIds().get(alias), portName));

        int bottom = internal.pinPositions().stream()
                .mapToInt(Connectivity.PinPosition::y).max().orElse(0);
        internal.add(new io.github.evilalee.gatelab.model.PlacedComponent(
                "chip-vcc", "input", 0, bottom + 80,
                io.github.evilalee.gatelab.model.Facing.EAST, Map.of(), "VCC"));
        internal.add(new io.github.evilalee.gatelab.model.PlacedComponent(
                "chip-gnd", "input", 0, bottom + 120,
                io.github.evilalee.gatelab.model.Facing.EAST, Map.of(), "GND"));

        List<ComponentDefinition.Port> ports = new ArrayList<>();
        for (ChipPortSpec port : chipPorts) {
            ports.add(new ComponentDefinition.Port(
                    port.name(), port.direction(), false, port.pin()));
        }
        return new ComponentDefinition(
                name, description, "TTL 74xx", ports, null, "DIP14", internal);
    }

    private static ComponentDefinition sequentialBuiltIn(
            List<GateDef> gateDefinitions,
            String name,
            List<String> inputNames,
            String clockName) {
        Circuit circuit = new Circuit();
        Map<String, InputPin> inputs = new LinkedHashMap<>();
        for (String inputName : inputNames) {
            InputPin input = new InputPin(inputName);
            circuit.addComponent(input);
            inputs.put(inputName, input);
        }

        OutputProbe q = new OutputProbe("Q");
        OutputProbe qn = new OutputProbe("QN");
        circuit.addComponent(q);
        circuit.addComponent(qn);

        SequentialParts.Outputs outputs = switch (name) {
            case "DLATCH" -> SequentialParts.dLatch(
                    circuit, "PART", inputs.get("D").output(), inputs.get("EN").output());
            case "DFF" -> SequentialParts.dff(
                    circuit, "PART", inputs.get("D").output(), inputs.get("CLK").output());
            case "TFF" -> SequentialParts.tff(
                    circuit, "PART", inputs.get("T").output(), inputs.get("CLK").output());
            case "JKFF" -> SequentialParts.jkff(
                    circuit, "PART", inputs.get("J").output(), inputs.get("K").output(),
                    inputs.get("CLK").output());
            default -> throw new IllegalArgumentException(
                    "unknown sequential built-in: " + name);
        };
        circuit.connect(outputs.q(), q.input());
        circuit.connect(outputs.qn(), qn.input());

        Map<String, OutputProbe> namedOutputs = new LinkedHashMap<>();
        namedOutputs.put("Q", q);
        namedOutputs.put("QN", qn);
        Netlist netlist = toFeedbackNetlist(circuit, inputs, namedOutputs);
        FeedbackCircuitExtractor.Extraction raw = FeedbackCircuitExtractor.extract(netlist)
                .orElseThrow(() -> new IllegalStateException(
                        name + " expansion did not produce a feedback circuit"));
        FeedbackCircuitExtractor.Extraction extraction = clockName == null
                ? raw
                : new FeedbackCircuitExtractor.Extraction(
                        raw.dagNetlist(),
                        raw.feedbackEdges(),
                        raw.inputComponentIds(),
                        Map.of(clockName, clockName),
                        raw.outputComponentIds(),
                        raw.gateComponentIds());
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(gateDefinitions).layoutFeedback(extraction);
        return new ComponentDefinition(
                name, builtInDescription(name), "Sequential",
                ports(inputNames, clockName, List.of("Q", "QN")), "Q", layout.schematic());
    }

    private static List<ComponentDefinition.Port> ports(
            List<String> inputs, String clockName, List<String> outputs) {
        List<ComponentDefinition.Port> result = new ArrayList<>();
        for (String input : inputs) {
            result.add(new ComponentDefinition.Port(
                    input, ComponentDefinition.Direction.IN, input.equals(clockName)));
        }
        for (String output : outputs) {
            result.add(new ComponentDefinition.Port(
                    output, ComponentDefinition.Direction.OUT, false));
        }
        return List.copyOf(result);
    }

    private static String builtInDescription(String name) {
        return switch (name) {
            case "DLATCH" -> "Transparent-high NAND D latch";
            case "DFF" -> "Positive-edge master/slave D flip-flop";
            case "TFF" -> "Positive-edge toggle flip-flop";
            case "JKFF" -> "Positive-edge JK flip-flop";
            default -> throw new IllegalArgumentException("unknown sequential part: " + name);
        };
    }

    /** Converts an existing gate-level Circuit to a feedback-capable signal netlist. */
    private static Netlist toFeedbackNetlist(
            Circuit circuit,
            Map<String, InputPin> namedInputs,
            Map<String, OutputProbe> namedOutputs) {
        Map<Pin, Pin> parents = new IdentityHashMap<>();
        for (Pin pin : circuit.pins()) {
            parents.put(pin, pin);
        }
        for (Wire wire : circuit.wires()) {
            union(parents, wire.first(), wire.second());
        }

        Map<Pin, String> signalByNet = new IdentityHashMap<>();
        for (Map.Entry<String, InputPin> entry : namedInputs.entrySet()) {
            addDriver(signalByNet, find(parents, entry.getValue().output()), entry.getKey());
        }

        Map<String, String> gateIds = new LinkedHashMap<>();
        int nextGate = 1;
        for (Component component : circuit.components()) {
            if (!(component instanceof Gate gate)) {
                continue;
            }
            String signal = "g" + nextGate++;
            gateIds.put(gate.id(), signal);
            addDriver(signalByNet, find(parents, gate.output()), signal);
        }

        List<Netlist.Gate> gates = new ArrayList<>();
        for (Component component : circuit.components()) {
            if (!(component instanceof Gate gate)) {
                continue;
            }
            List<String> gateInputs = new ArrayList<>(gate.inputs().size());
            for (Pin input : gate.inputs()) {
                String source = signalByNet.get(find(parents, input));
                if (source == null) {
                    throw new IllegalStateException(
                            "sequential expansion has an undriven gate input: " + input.id());
                }
                gateInputs.add(source);
            }
            gates.add(new Netlist.Gate(
                    gateIds.get(gate.id()),
                    GateOperator.valueOf(gate.type().name()),
                    gateInputs));
        }

        List<Netlist.Output> outputs = new ArrayList<>();
        for (Map.Entry<String, OutputProbe> entry : namedOutputs.entrySet()) {
            String source = signalByNet.get(find(parents, entry.getValue().input()));
            if (source == null) {
                throw new IllegalStateException(
                        "sequential output is not driven: " + entry.getKey());
            }
            outputs.add(new Netlist.Output(entry.getKey(), source));
        }
        return new Netlist(new ArrayList<>(namedInputs.keySet()), outputs, gates, true);
    }

    private static void addDriver(
            Map<Pin, String> signalByNet, Pin root, String signal) {
        String previous = signalByNet.putIfAbsent(root, signal);
        if (previous != null && !previous.equals(signal)) {
            throw new IllegalStateException(
                    "sequential expansion connected distinct drivers: "
                            + previous + " and " + signal);
        }
    }

    private static Pin find(Map<Pin, Pin> parents, Pin pin) {
        Pin parent = parents.get(pin);
        if (parent == null) {
            throw new IllegalArgumentException("pin does not belong to the sequential circuit");
        }
        if (parent != pin) {
            parent = find(parents, parent);
            parents.put(pin, parent);
        }
        return parent;
    }

    private static void union(Map<Pin, Pin> parents, Pin first, Pin second) {
        Pin rootA = find(parents, first);
        Pin rootB = find(parents, second);
        if (rootA != rootB) {
            parents.put(rootB, rootA);
        }
    }
}
