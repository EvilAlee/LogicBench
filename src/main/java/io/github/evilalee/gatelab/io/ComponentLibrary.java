package io.github.evilalee.gatelab.io;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
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
    private static final String USER_EXTENSION = ".lbcomp.json";

    private final List<GateDef> gateDefinitions;
    private final Path userDirectory;
    private final Map<String, ComponentDefinition> definitions = new LinkedHashMap<>();
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
        if (builtInNames.contains(definition.name())) {
            throw new IllegalArgumentException(
                    "built-in component definitions cannot be overwritten: "
                            + definition.name());
        }
        Files.createDirectories(userDirectory);
        Path target = userDirectory.resolve(definition.name() + USER_EXTENSION);
        Files.writeString(target, ComponentDefinitionJson.toJson(definition),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        definitions.put(definition.name(), definition);
        return target;
    }

    private void loadUserDefinitions() throws IOException {
        if (!Files.isDirectory(userDirectory)) {
            return;
        }
        try (Stream<Path> files = Files.list(userDirectory)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(USER_EXTENSION))
                    .sorted()
                    .toList()) {
                ComponentDefinition definition;
                try {
                    definition = ComponentDefinitionJson.fromJson(
                            Files.readString(file, StandardCharsets.UTF_8), gateDefinitions);
                } catch (RuntimeException exception) {
                    throw new IOException(
                            "invalid component definition file " + file + ": "
                                    + exception.getMessage(), exception);
                }
                String expectedFileName = definition.name() + USER_EXTENSION;
                if (!file.getFileName().toString().equals(expectedFileName)) {
                    throw new IOException(
                            "component definition file must be named " + expectedFileName
                                    + ": " + file.getFileName());
                }
                if (definitions.containsKey(definition.name())) {
                    throw new IOException(
                            "duplicate component definition name: " + definition.name());
                }
                definitions.put(definition.name(), definition);
            }
        }
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
        result.add(ComponentDefinition.fromCircuit(
                "HA", "Half adder", "Arithmetic", null,
                new SchematicLayout(gateDefinitions).layout(halfAdder.netlist()).schematic()));

        ExpressionCompiler.Compilation fullAdder = ExpressionCompiler.compile(
                "S = (A ^ B) ^ CIN; COUT = (A & B) | (A & CIN) | (B & CIN)",
                false, true);
        result.add(ComponentDefinition.fromCircuit(
                "FA", "Full adder", "Arithmetic", null,
                new SchematicLayout(gateDefinitions).layout(fullAdder.netlist()).schematic()));
        return List.copyOf(result);
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
        return ComponentDefinition.fromCircuit(
                name, builtInDescription(name), "Sequential", "Q", layout.schematic());
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
