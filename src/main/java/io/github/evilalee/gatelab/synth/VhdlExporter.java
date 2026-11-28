package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.Simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exports a placed schematic as structural VHDL. Every placed gate remains a
 * concurrent assignment; component definitions remain hierarchical entities.
 */
public final class VhdlExporter {
    private static final Set<String> RESERVED = Set.of(
            "abs", "access", "after", "alias", "all", "and", "architecture",
            "array", "assert", "attribute", "begin", "block", "body", "buffer",
            "bus", "case", "component", "configuration", "constant", "disconnect",
            "downto", "else", "elsif", "end", "entity", "exit", "file", "for",
            "function", "generate", "generic", "group", "guarded", "if", "impure",
            "in", "inertial", "inout", "is", "label", "library", "linkage", "literal",
            "loop", "map", "mod", "nand", "new", "next", "nor", "not", "null",
            "of", "on", "open", "or", "others", "out", "package", "parameter",
            "port", "postponed", "private", "procedure", "process", "protected",
            "pure", "range", "record", "register", "reject", "release", "rem",
            "report", "return", "rol", "ror", "select", "severity", "signal",
            "shared", "sla", "sll", "sra", "srl", "subtype", "then", "to",
            "transport", "type", "unaffected", "units", "until", "use", "variable",
            "wait", "when", "while", "with", "xnor", "xor", "bit", "boolean",
            "integer", "natural", "positive", "real", "std_logic", "std_logic_vector",
            "time", "unsigned", "signed", "work", "true", "false", "character");

    private static final Comparator<PlacedComponent> VISUAL_ORDER = Comparator
            .comparingInt(PlacedComponent::x)
            .thenComparingInt(PlacedComponent::y)
            .thenComparing(PlacedComponent::id);
    private static final Comparator<ComponentInstance> INSTANCE_ORDER =
            Comparator.comparing(ComponentInstance::id);

    private VhdlExporter() {
    }

    public record TestbenchCapability(boolean enabled, String reason) {
        public TestbenchCapability {
            reason = reason == null ? "" : reason;
        }
    }

    public record Export(String text, String testbench, String entityName,
            TestbenchCapability testbenchCapability) {
        public Export {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(entityName, "entityName");
            Objects.requireNonNull(testbenchCapability, "testbenchCapability");
        }

        public boolean hasTestbench() {
            return testbench != null;
        }
    }

    private record PinKey(String componentId, String pinId) {
    }

    private record Port(String sourceName, String emittedName, String direction,
            boolean clock, String componentId, String pinId) {
    }

    private enum DriverKind {
        INPUT, CLOCK, CONSTANT, GATE, INSTANCE
    }

    private record Driver(DriverKind kind, String componentId, String pinId,
            String nodeKey, String constantValue) {
    }

    private static final class NetPlan {
        private final Connectivity.Net net;
        private final String key;
        private Driver driver;
        private String targetName;
        private String inputPortName;
        private final List<Port> outputPorts = new ArrayList<>();

        private NetPlan(Connectivity.Net net) {
            this.net = net;
            this.key = netKey(net);
        }
    }

    private static final class IdentifierAllocator {
        private final Set<String> used = new HashSet<>();

        private IdentifierAllocator(String... alreadyUsed) {
            for (String name : alreadyUsed) {
                used.add(name.toLowerCase(Locale.ROOT));
            }
        }

        private String allocate(String raw) {
            String base = sanitize(raw);
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = base + "_" + suffix++;
            }
            return candidate;
        }
    }

    private static final class Module {
        private final String entityName;
        private final Schematic schematic;
        private final ComponentDefinition definition;
        private final List<Port> ports;
        private final Map<String, Port> portsByComponent;
        private final Map<String, String> definitionPortNames;
        private final IdentifierAllocator identifiers;
        private final Map<PinKey, NetPlan> netByPin = new HashMap<>();
        private final List<NetPlan> nets = new ArrayList<>();
        private final Map<String, PlacedComponent> componentsById = new LinkedHashMap<>();
        private final Map<String, ComponentInstance> instancesById = new LinkedHashMap<>();
        private final Map<String, String> instanceLabels = new LinkedHashMap<>();
        private final List<String> signals = new ArrayList<>();
        private final Map<String, String> gateAssignments = new LinkedHashMap<>();
        private final List<String> outputAliases = new ArrayList<>();
        private final List<String> constantAssignments = new ArrayList<>();
        private final List<String> instanceStatements = new ArrayList<>();
        private final boolean feedback;

        private Module(String entityName, Schematic schematic,
                ComponentDefinition definition, Module rootContext) {
            this.entityName = entityName;
            this.schematic = Objects.requireNonNull(schematic, "schematic");
            this.definition = definition;
            for (PlacedComponent component : schematic.components()) {
                componentsById.put(component.id(), component);
            }
            for (ComponentInstance instance : schematic.componentInstances()) {
                instancesById.put(instance.id(), instance);
            }

            this.identifiers = new IdentifierAllocator(entityName, "structural");
            this.ports = definition == null
                    ? topPorts(schematic, identifiers)
                    : definitionPorts(schematic, definition, identifiers);
            Map<String, Port> byComponent = new LinkedHashMap<>();
            Map<String, String> byPortName = new LinkedHashMap<>();
            for (Port port : ports) {
                byComponent.put(port.componentId(), port);
                byPortName.put(port.sourceName(), port.emittedName());
            }
            this.portsByComponent = Map.copyOf(byComponent);
            this.definitionPortNames = Map.copyOf(byPortName);

            buildNets();
            this.feedback = hasFeedback();
        }

        private void buildNets() {
            List<Connectivity.PinPosition> positions = schematic.pinPositions();
            Connectivity.Result topology = Connectivity.analyze(positions, schematic.wires());
            List<Connectivity.Net> sorted = topology.nets().stream()
                    .sorted(Comparator.comparing(VhdlExporter::netKey))
                    .toList();
            for (Connectivity.Net net : sorted) {
                NetPlan plan = new NetPlan(net);
                nets.add(plan);
                for (Connectivity.PinPosition pin : net.pins()) {
                    netByPin.put(new PinKey(pin.componentId(), pin.pinId()), plan);
                }
            }

            Map<String, ComponentInstance> instances = instancesById;
            for (NetPlan net : nets) {
                List<Driver> drivers = new ArrayList<>();
                for (Connectivity.PinPosition pin : net.net.pins()) {
                    PlacedComponent placed = componentsById.get(pin.componentId());
                    if (placed != null) {
                        if (!pin.pinId().equals("out")) {
                            continue;
                        }
                        switch (placed.typeId()) {
                            case "input" -> drivers.add(new Driver(
                                    DriverKind.INPUT, placed.id(), pin.pinId(), null, null));
                            case "clock" -> drivers.add(new Driver(
                                    DriverKind.CLOCK, placed.id(), pin.pinId(), null, null));
                            case "constant", "vcc", "gnd" -> {
                                String value = placed.fixedSourceValue()
                                        == io.github.evilalee.gatelab.model.Value.ONE ? "1" : "0";
                                drivers.add(new Driver(
                                        DriverKind.CONSTANT, placed.id(), pin.pinId(),
                                        null, value));
                            }
                            default -> drivers.add(new Driver(
                                    DriverKind.GATE, placed.id(), pin.pinId(),
                                    "G:" + placed.id(), null));
                        }
                    } else {
                        ComponentInstance instance = instances.get(pin.componentId());
                        if (instance == null) {
                            throw new IllegalStateException(
                                    "Unknown pin component " + pin.componentId());
                        }
                        ComponentDefinition child = schematic.componentDefinitionFor(instance);
                        ComponentDefinition.Port childPort = child.ports().stream()
                                .filter(port -> port.name().equals(pin.pinId()))
                                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                        "Unknown instance pin " + instance.id() + "." + pin.pinId()));
                        if (childPort.direction() == ComponentDefinition.Direction.OUT) {
                            drivers.add(new Driver(
                                    DriverKind.INSTANCE, instance.id(), pin.pinId(),
                                    "I:" + instance.id(), null));
                        }
                    }
                }
                if (drivers.size() > 1) {
                    throw new IllegalArgumentException(
                            "Net has multiple drivers: " + net.key);
                }
                net.driver = drivers.isEmpty() ? null : drivers.getFirst();

                for (Port port : ports) {
                    if (!port.direction().equals("out")) {
                        continue;
                    }
                    NetPlan portNet = netByPin.get(new PinKey(port.componentId(), port.pinId()));
                    if (portNet == net) {
                        net.outputPorts.add(port);
                    }
                }
                for (Port port : ports) {
                    if (!port.direction().equals("in")) {
                        continue;
                    }
                    NetPlan portNet = netByPin.get(new PinKey(port.componentId(), port.pinId()));
                    if (portNet == net) {
                        if (net.inputPortName != null) {
                            throw new IllegalArgumentException(
                                    "Two input ports are electrically connected: " + net.key);
                        }
                        net.inputPortName = port.emittedName();
                    }
                }
            }

            for (NetPlan net : nets) {
                if (net.driver != null) {
                    if ((net.driver.kind() == DriverKind.GATE
                            || net.driver.kind() == DriverKind.INSTANCE)
                            && !net.outputPorts.isEmpty()) {
                        net.targetName = net.outputPorts.getFirst().emittedName();
                    } else if (net.driver.kind() == DriverKind.INPUT
                            || net.driver.kind() == DriverKind.CLOCK) {
                        net.targetName = portsByComponent.get(net.driver.componentId()).emittedName();
                    } else if (net.inputPortName != null) {
                        net.targetName = net.inputPortName;
                    } else if (net.driver.kind() == DriverKind.CONSTANT
                            && !net.outputPorts.isEmpty()) {
                        net.targetName = net.outputPorts.getFirst().emittedName();
                    } else {
                        net.targetName = identifiers.allocate("net" + (signals.size() + 1));
                        signals.add(net.targetName);
                    }
                }
            }

            for (PlacedComponent component : schematic.components().stream()
                    .sorted(VISUAL_ORDER).toList()) {
                if (isGate(component)) {
                    GateOperator operator;
                    try {
                        operator = GateOperator.valueOf(component.typeId().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(
                                "Unsupported component for VHDL export: " + component.typeId());
                    }
                    int actualInputs = schematic.definitionFor(component).inputs().size();
                    if (!operator.acceptsInputCount(actualInputs)) {
                        throw new IllegalArgumentException(
                                "Unsupported input count for " + component.typeId()
                                        + ": expected " + operator.minimumInputCount()
                                        + ".." + operator.maximumInputCount()
                                        + ", found " + actualInputs);
                    }
                    List<String> args = new ArrayList<>();
                    for (int index = 0; index < schematic.definitionFor(component).inputs().size(); index++) {
                        args.add(sourceFor(netByPin.get(new PinKey(
                                component.id(), "in" + index))));
                    }
                    NetPlan output = netByPin.get(new PinKey(component.id(), "out"));
                    if (output == null || output.driver == null
                            || output.driver.kind() != DriverKind.GATE) {
                        throw new IllegalArgumentException(
                                "Gate output is not represented by a driven net: " + component.id());
                    }
                    gateAssignments.put(output.targetName,
                            gateExpression(component.typeId(), args));
                }
            }

            for (NetPlan net : nets) {
                if (net.driver != null && net.driver.kind() == DriverKind.CONSTANT
                        && net.targetName != null && net.outputPorts.isEmpty()
                        && net.inputPortName == null) {
                    constantAssignments.add(net.targetName + " <= '" + net.driver.constantValue() + "';");
                }
                String source = sourceFor(net);
                for (int index = 0; index < net.outputPorts.size(); index++) {
                    Port output = net.outputPorts.get(index);
                    boolean isDrivenDirectly = net.driver != null
                            && (net.driver.kind() == DriverKind.GATE
                                || net.driver.kind() == DriverKind.INSTANCE)
                            && index == 0;
                    if (!isDrivenDirectly) {
                        outputAliases.add(output.emittedName() + " <= " + source + ";");
                    }
                }
            }

            for (ComponentInstance instance : schematic.componentInstances().stream()
                    .sorted(INSTANCE_ORDER).toList()) {
                ComponentDefinition child = schematic.componentDefinitionFor(instance);
                String label = identifiers.allocate(
                        instance.label().isBlank() ? instance.id() : instance.label());
                instanceLabels.put(instance.id(), label);
                List<String> associations = new ArrayList<>();
                for (ComponentDefinition.Port childPort : child.ports()) {
                    String actual = sourceFor(netByPin.get(new PinKey(instance.id(), childPort.name())));
                    if (childPort.direction() == ComponentDefinition.Direction.OUT) {
                        NetPlan net = netByPin.get(new PinKey(instance.id(), childPort.name()));
                        if (net == null || net.driver == null
                                || net.driver.kind() != DriverKind.INSTANCE
                                || !net.driver.componentId().equals(instance.id())) {
                            throw new IllegalArgumentException(
                                    "Instance output is not connected to a valid net: "
                                            + instance.id() + "." + childPort.name());
                        }
                        actual = net.targetName;
                    }
                    associations.add("            " + childPortName(child, childPort.name())
                            + " => " + actual);
                }
                instanceStatements.add("    " + label + " : entity work."
                        + childEntity(child) + System.lineSeparator()
                        + "        port map (" + System.lineSeparator()
                        + String.join("," + System.lineSeparator(), associations)
                        + System.lineSeparator() + "        );");
            }
        }

        private String childPortName(ComponentDefinition child, String sourceName) {
            return childPorts.getOrDefault(child.hash(), Map.of()).getOrDefault(sourceName, sourceName);
        }

        private String childEntity(ComponentDefinition child) {
            return childEntities.getOrDefault(child.hash(), child.name());
        }

        private String sourceFor(NetPlan net) {
            if (net == null || net.driver == null) {
                return "'X'";
            }
            return switch (net.driver.kind()) {
                case INPUT, CLOCK -> portsByComponent.get(net.driver.componentId()).emittedName();
                case CONSTANT -> "'" + net.driver.constantValue() + "'";
                case GATE, INSTANCE -> net.targetName;
            };
        }

        private boolean hasFeedback() {
            Map<String, Set<String>> edges = new LinkedHashMap<>();
            for (NetPlan net : nets) {
                if (net.driver == null
                        || (net.driver.kind() != DriverKind.GATE
                            && net.driver.kind() != DriverKind.INSTANCE)) {
                    continue;
                }
                String source = net.driver.nodeKey();
                edges.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
                for (Connectivity.PinPosition pin : net.net.pins()) {
                    String consumer = null;
                    PlacedComponent placed = componentsById.get(pin.componentId());
                    if (placed != null && !pin.pinId().equals("out")
                            && !placed.typeId().equals("input")
                            && !placed.typeId().equals("clock")
                            && !placed.typeId().equals("constant")
                            && !placed.typeId().equals("vcc")
                            && !placed.typeId().equals("gnd")
                            && !placed.typeId().equals("output")) {
                        consumer = "G:" + placed.id();
                    } else if (instancesById.containsKey(pin.componentId())) {
                        ComponentInstance instance = instancesById.get(pin.componentId());
                        ComponentDefinition child = schematic.componentDefinitionFor(instance);
                        boolean input = child.ports().stream().anyMatch(port ->
                                port.name().equals(pin.pinId())
                                        && port.direction() == ComponentDefinition.Direction.IN);
                        if (input) {
                            consumer = "I:" + instance.id();
                        }
                    }
                    if (consumer != null) {
                        edges.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(consumer);
                        edges.computeIfAbsent(consumer, ignored -> new LinkedHashSet<>());
                    }
                }
            }
            Map<String, Integer> state = new HashMap<>();
            for (String node : edges.keySet()) {
                if (visit(node, edges, state)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, String> childEntities = new HashMap<>();
    private static final Map<String, Map<String, String>> childPorts = new HashMap<>();

    public static Export export(Schematic schematic, String sourceFileName) {
        Objects.requireNonNull(schematic, "schematic");
        Exporter exporter = new Exporter(schematic, sourceFileName);
        String text = exporter.render();
        TestbenchCapability capability = testbenchCapability(schematic);
        String testbench = capability.enabled()
                ? generateTestbench(schematic, exporter.top, exporter.topEntity)
                : null;
        return new Export(text, testbench, exporter.topEntity, capability);
    }

    public static Export export(Schematic schematic) {
        return export(schematic, null);
    }

    public static TestbenchCapability testbenchCapability(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        if (schematic.components().stream().anyMatch(c -> c.typeId().equals("clock"))) {
            return new TestbenchCapability(false,
                    "Testbench is unavailable for circuits containing a Clock component.");
        }
        if (schematic.components().stream().filter(c -> c.typeId().equals("input")).count() > 10) {
            return new TestbenchCapability(false,
                    "Testbench supports at most 10 input components.");
        }
        try {
            Exporter exporter = new Exporter(schematic, null);
            if (exporter.modules.stream().anyMatch(module -> module.feedback)) {
                return new TestbenchCapability(false,
                        "Testbench is unavailable for circuits containing combinational feedback.");
            }
            if (exporter.containsClockPort) {
                return new TestbenchCapability(false,
                        "Testbench is unavailable because a component definition contains a Clock port.");
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new TestbenchCapability(false,
                    "Testbench unavailable: " + exception.getMessage());
        }
        return new TestbenchCapability(true, "");
    }

    private static String generateTestbench(Schematic schematic, Module top, String entityName) {
        List<Port> inputs = top.ports.stream().filter(port -> port.direction().equals("in")).toList();
        List<Port> outputs = top.ports.stream().filter(port -> port.direction().equals("out")).toList();
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic.copy());
        Simulator simulator = new Simulator(compiled.circuit());

        String base = sanitize(entityName + "_tb");
        StringBuilder text = new StringBuilder();
        append(text, "library ieee;");
        append(text, "use ieee.std_logic_1164.all;");
        append(text, "");
        append(text, "entity " + base + " is");
        append(text, "end entity " + base + ";");
        append(text, "");
        append(text, "architecture test of " + base + " is");
        for (Port input : inputs) {
            append(text, "    signal " + input.emittedName() + " : std_logic := '0';");
        }
        for (Port output : outputs) {
            append(text, "    signal " + output.emittedName() + " : std_logic;");
        }
        append(text, "begin");
        append(text, "    dut : entity work." + entityName);
        if (!top.ports.isEmpty()) {
            append(text, "        port map (");
            List<String> maps = new ArrayList<>();
            for (Port port : top.ports) {
                maps.add("            " + port.emittedName() + " => " + port.emittedName());
            }
            text.append(String.join("," + System.lineSeparator(), maps)).append(System.lineSeparator());
            append(text, "        );");
        } else {
            append(text, "        ;");
        }
        append(text, "");
        append(text, "    stimulus : process");
        append(text, "    begin");

        int rows = 1 << inputs.size();
        for (int row = 0; row < rows; row++) {
            for (int index = 0; index < inputs.size(); index++) {
                boolean high = ((row >>> (inputs.size() - 1 - index)) & 1) != 0;
                String value = high ? "1" : "0";
                Port port = inputs.get(index);
                String sourceId = port.componentId();
                simulator.setInputValue(compiled.inputs().get(sourceId),
                        high ? Value.ONE : Value.ZERO);
                append(text, "        " + port.emittedName() + " <= '" + value + "';");
            }
            simulator.settle();
            append(text, "        wait for 10 ns;");
            List<String> assertions = new ArrayList<>();
            for (Port output : outputs) {
                Value value = simulator.getProbeValue(compiled.outputs().get(output.componentId()));
                assertions.add(output.emittedName() + " = '" + vhdlValue(value) + "'");
            }
            if (!assertions.isEmpty()) {
                final int currentRow = row;
                String rowBits = inputs.stream().map(port -> {
                    int index = inputs.indexOf(port);
                    return ((currentRow >>> (inputs.size() - 1 - index)) & 1) == 1 ? "1" : "0";
                }).reduce("", String::concat);
                append(text, "        assert (" + String.join(" and ", assertions)
                        + ") report \"Mismatch for input row " + rowBits
                        + "\" severity failure;");
            }
        }
        append(text, "        report \"TEST PASSED\" severity note;");
        append(text, "        wait;");
        append(text, "    end process;");
        append(text, "end architecture test;");
        return text.toString();
    }

    private static String vhdlValue(Value value) {
        return switch (value) {
            case ZERO -> "0";
            case ONE -> "1";
            case X -> "X";
        };
    }

    private static final class Exporter {
        private final Schematic root;
        private final String sourceFileName;
        private final String topEntity;
        private final List<ComponentDefinition> definitions = new ArrayList<>();
        private final List<Module> modules = new ArrayList<>();
        private final Map<String, String> entitiesByHash = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> portNamesByHash = new LinkedHashMap<>();
        private final Set<String> active = new LinkedHashSet<>();
        private boolean containsClockPort;
        private final Module top;

        private Exporter(Schematic root, String sourceFileName) {
            this.root = root;
            this.sourceFileName = sourceFileName;
            String entityBase = sourceFileName == null || sourceFileName.isBlank()
                    ? "logicbench_circuit" : stem(sourceFileName);
            IdentifierAllocator entityNames = new IdentifierAllocator();
            topEntity = entityNames.allocate(entityBase);

            collectDefinitions(root);
            for (ComponentDefinition definition : definitions) {
                entitiesByHash.put(definition.hash(), entityNames.allocate(definition.name()));
            }
            childEntities.clear();
            childEntities.putAll(entitiesByHash);
            childPorts.clear();
            for (ComponentDefinition definition : definitions) {
                Schematic internal = definition.internalCircuit();
                childPorts.put(definition.hash(), definitionPorts(
                        internal, definition,
                        new IdentifierAllocator(entitiesByHash.get(definition.hash()), "structural"))
                        .stream().collect(java.util.stream.Collectors.toMap(
                                Port::sourceName, Port::emittedName, (first, second) -> first,
                                LinkedHashMap::new)));
            }
            for (ComponentDefinition definition : definitions) {
                Schematic internal = definition.internalCircuit();
                Module module = new Module(entitiesByHash.get(definition.hash()),
                        internal, definition, null);
                modules.add(module);
                portNamesByHash.put(definition.hash(), module.definitionPortNames);
                for (ComponentDefinition.Port port : definition.ports()) {
                    containsClockPort |= port.isClock();
                }
            }
            childEntities.clear();
            childEntities.putAll(entitiesByHash);
            childPorts.clear();
            childPorts.putAll(portNamesByHash);

            top = new Module(topEntity, root, null, null);
            modules.add(top);
        }

        private void collectDefinitions(Schematic schematic) {
            for (ComponentInstance instance : schematic.componentInstances().stream()
                    .sorted(INSTANCE_ORDER).toList()) {
                ComponentDefinition definition = schematic.componentDefinitionFor(instance);
                String name = definition.name();
                if (active.contains(name)) {
                    throw new IllegalArgumentException(
                            "Component definition cycle: " + String.join(" -> ", active)
                                    + " -> " + name);
                }
                if (definitions.stream().anyMatch(existing ->
                        existing.name().equals(name) && !existing.hash().equals(definition.hash()))) {
                    throw new IllegalArgumentException(
                            "Conflicting component definitions named " + name + ".");
                }
                if (definitions.stream().anyMatch(existing ->
                        existing.hash().equals(definition.hash()))) {
                    continue;
                }
                active.add(name);
                collectDefinitions(definition.internalCircuit());
                active.remove(name);
                definitions.add(definition);
            }
        }

        private String render() {
            StringBuilder text = new StringBuilder();
            append(text, "-- Generated by LogicBench structural VHDL exporter");
            if (sourceFileName != null && !sourceFileName.isBlank()) {
                append(text, "-- Source file: " + sourceFileName.replace("\n", " ").replace("\r", " "));
            }
            append(text, "");
            append(text, "library ieee;");
            append(text, "use ieee.std_logic_1164.all;");
            append(text, "");
            for (Module module : modules) {
                renderModule(text, module);
            }
            return text.toString();
        }
    }

    private static void renderModule(StringBuilder text, Module module) {
        append(text, "entity " + module.entityName + " is");
        if (!module.ports.isEmpty()) {
            append(text, "    port (");
            List<String> portLines = new ArrayList<>();
            for (Port port : module.ports) {
                portLines.add("        " + port.emittedName() + " : "
                        + port.direction() + " std_logic");
            }
            text.append(String.join(";" + System.lineSeparator(), portLines))
                    .append(System.lineSeparator());
            append(text, "    );");
        }
        append(text, "end entity " + module.entityName + ";");
        append(text, "");
        if (module.feedback) {
            append(text, "-- Contains combinational feedback (latches). Simulates in VHDL simulators; synthesis tools may warn.");
        }
        append(text, "architecture structural of " + module.entityName + " is");
        for (String signal : module.signals) {
            append(text, "    signal " + signal + " : std_logic;");
        }
        append(text, "begin");
        for (String statement : module.constantAssignments) {
            append(text, "    " + statement);
        }
        for (Map.Entry<String, String> gate : module.gateAssignments.entrySet()) {
            append(text, "    " + gate.getKey() + " <= " + gate.getValue() + ";");
        }
        for (String statement : module.instanceStatements) {
            append(text, statement);
        }
        for (String statement : module.outputAliases) {
            append(text, "    " + statement);
        }
        append(text, "end architecture structural;");
        append(text, "");
    }

    private static List<Port> topPorts(Schematic schematic, IdentifierAllocator identifiers) {
        List<PlacedComponent> boundary = schematic.components().stream()
                .filter(component -> component.typeId().equals("input")
                        || component.typeId().equals("clock")
                        || component.typeId().equals("output"))
                .sorted(VISUAL_ORDER).toList();
        Map<String, Integer> fallback = new HashMap<>();
        List<Port> ports = new ArrayList<>();
        for (PlacedComponent component : boundary) {
            String direction = component.typeId().equals("output") ? "out" : "in";
            String prefix = switch (component.typeId()) {
                case "input" -> "In";
                case "output" -> "Out";
                case "clock" -> "Clk";
                default -> throw new IllegalStateException("unknown boundary component");
            };
            String raw = component.label().isBlank()
                    ? prefix + fallback.merge(prefix, 1, Integer::sum)
                    : component.label();
            String name = identifiers.allocate(raw);
            String pin = component.typeId().equals("output") ? "in0" : "out";
            ports.add(new Port(raw, name, direction,
                    component.typeId().equals("clock"), component.id(), pin));
        }
        return List.copyOf(ports);
    }

    private static List<Port> definitionPorts(
            Schematic schematic, ComponentDefinition definition, IdentifierAllocator identifiers) {
        List<Port> ports = new ArrayList<>();
        for (ComponentDefinition.Port port : definition.ports()) {
            PlacedComponent component = schematic.components().stream()
                    .filter(candidate -> candidate.label().equals(port.name())
                            && (candidate.typeId().equals("output")
                                == (port.direction() == ComponentDefinition.Direction.OUT))
                            && (candidate.typeId().equals("clock") == port.isClock()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Definition " + definition.name()
                                    + " is missing internal port component " + port.name()));
            ports.add(new Port(port.name(), identifiers.allocate(port.name()),
                    port.direction() == ComponentDefinition.Direction.IN ? "in" : "out",
                    port.isClock(), component.id(),
                    port.direction() == ComponentDefinition.Direction.OUT ? "in0" : "out"));
        }
        return List.copyOf(ports);
    }

    private static boolean isGate(PlacedComponent component) {
        return !Set.of("input", "clock", "constant", "vcc", "gnd", "output")
                .contains(component.typeId());
    }

    private static String gateExpression(String typeId, List<String> args) {
        if (typeId.equals("not")) {
            return "(not " + args.getFirst() + ")";
        }
        if (typeId.equals("buffer")) {
            return "(" + args.getFirst() + ")";
        }
        String operator = switch (typeId.toLowerCase(Locale.ROOT)) {
            case "and" -> "and";
            case "or" -> "or";
            case "xor" -> "xor";
            case "nand" -> "not";
            case "nor" -> "not";
            case "xnor" -> "not";
            default -> throw new IllegalArgumentException("Unsupported gate type: " + typeId);
        };
        if (operator.equals("not")) {
            String innerOperator = typeId.equalsIgnoreCase("nand") ? "and"
                    : typeId.equalsIgnoreCase("nor") ? "or" : "xor";
            return "(not (" + String.join(" " + innerOperator + " ", args) + "))";
        }
        return "(" + String.join(" " + operator + " ", args) + ")";
    }

    private static boolean visit(String node, Map<String, Set<String>> edges,
            Map<String, Integer> state) {
        int current = state.getOrDefault(node, 0);
        if (current == 1) {
            return true;
        }
        if (current == 2) {
            return false;
        }
        state.put(node, 1);
        for (String next : edges.getOrDefault(node, Set.of())) {
            if (visit(next, edges, state)) {
                return true;
            }
        }
        state.put(node, 2);
        return false;
    }

    private static String netKey(Connectivity.Net net) {
        List<String> pins = net.pins().stream()
                .map(pin -> pin.componentId() + "." + pin.pinId())
                .sorted().toList();
        List<String> segments = net.segments().stream()
                .map(Object::toString).sorted().toList();
        return String.join("|", pins) + "#" + String.join("|", segments);
    }

    private static String sanitize(String raw) {
        String source = raw == null ? "" : raw.trim();
        StringBuilder result = new StringBuilder();
        boolean previousUnderscore = false;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            boolean valid = (value >= 'A' && value <= 'Z')
                    || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9');
            if (valid) {
                result.append(value);
                previousUnderscore = false;
            } else if (!previousUnderscore) {
                result.append('_');
                previousUnderscore = true;
            }
        }
        while (!result.isEmpty() && result.charAt(result.length() - 1) == '_') {
            result.setLength(result.length() - 1);
        }
        if (result.isEmpty()) {
            result.append('n');
        }
        if (!((result.charAt(0) >= 'A' && result.charAt(0) <= 'Z')
                || (result.charAt(0) >= 'a' && result.charAt(0) <= 'z'))) {
            result.insert(0, "n_");
        }
        String name = result.toString();
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
            name += "_n";
        }
        return name;
    }

    private static String stem(String fileName) {
        String name = fileName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void append(StringBuilder target, String line) {
        target.append(line).append(System.lineSeparator());
    }
}
