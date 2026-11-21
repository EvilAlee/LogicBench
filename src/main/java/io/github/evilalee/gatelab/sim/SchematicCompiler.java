package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.WireSegment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles geometric schematic nets, recursively flattening black-box instances. */
public final class SchematicCompiler {
    public static final int MAX_COMPONENT_NESTING_DEPTH = 8;

    private SchematicCompiler() {
    }

    /** Named, top-level sources and probes for headless sequence execution. */
    public record CompiledCircuit(
            Circuit circuit,
            Map<String, InputPin> inputs,
            Set<String> clockIds,
            Map<String, OutputProbe> outputs) {
        public CompiledCircuit {
            Objects.requireNonNull(circuit, "circuit");
            inputs = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(inputs, "inputs")));
            clockIds = Set.copyOf(Objects.requireNonNull(clockIds, "clockIds"));
            outputs = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(outputs, "outputs")));
        }
    }

    public static CompiledCircuit compileCircuit(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        Flattened flattened = flatten(schematic);
        return new CompiledCircuit(
                flattened.circuit(), flattened.inputs(),
                flattened.clockIds(), flattened.outputs());
    }

    public static SimulationResult compileAndSettle(Schematic schematic) {
        return compileAndSettle(schematic, null);
    }

    /**
     * Every call creates a fresh flattened engine graph. Stable hierarchical
     * gate ids let settled feedback state survive unrelated schematic changes.
     */
    public static SimulationResult compileAndSettle(
            Schematic schematic, SimulationResult previous) {
        Objects.requireNonNull(schematic, "schematic");
        Flattened flattened = flatten(schematic);
        Simulator simulator = new Simulator(flattened.circuit());
        simulator.setInitialGateOutputValues(
                previous == null ? Map.of() : previous.gateOutputValues());

        for (Map.Entry<String, InputPin> entry : flattened.inputs().entrySet()) {
            String id = entry.getKey();
            boolean high = flattened.clockIds().contains(id)
                    ? schematic.clockValue(id) : schematic.inputValue(id);
            simulator.setInputValue(entry.getValue(), high ? Value.ONE : Value.ZERO);
        }
        simulator.settle();

        Map<Connectivity.Net, Value> netValues = new LinkedHashMap<>();
        Map<WireSegment, Value> wireValues = new LinkedHashMap<>();
        for (Connectivity.Net net : flattened.rootScope().topology().nets()) {
            NetRef ref = flattened.rootScope().netByConnectivityNet().get(net);
            Pin representative = flattened.representativePins().get(
                    flattened.unionFind().find(ref));
            Value value = representative == null
                    ? Value.X : simulator.getNetValue(representative);
            netValues.put(net, value);
            for (WireSegment segment : net.segments()) {
                wireValues.put(segment, value);
            }
        }

        Map<String, Value> inputValues = new LinkedHashMap<>();
        for (String id : flattened.inputs().keySet()) {
            boolean high = flattened.clockIds().contains(id)
                    ? schematic.clockValue(id) : schematic.inputValue(id);
            inputValues.put(id, high ? Value.ONE : Value.ZERO);
        }

        Map<String, Value> outputValues = new LinkedHashMap<>();
        flattened.outputs().forEach((id, probe) ->
                outputValues.put(id, simulator.getProbeValue(probe)));

        Map<String, Value> gateOutputValues = new LinkedHashMap<>();
        for (String id : flattened.allGateIds()) {
            Gate gate = flattened.gates().get(id);
            gateOutputValues.put(id, gate == null
                    ? Value.X : simulator.getGateOutputValue(gate));
        }

        return new SimulationResult(flattened.rootScope().topology(),
                netValues, wireValues, outputValues, inputValues,
                gateOutputValues, simulator.isOscillating());
    }

    static void checkDefinitionEntry(
            List<String> activeDefinitions, String definitionName, String instanceId) {
        int cycleStart = activeDefinitions.indexOf(definitionName);
        if (cycleStart >= 0) {
            List<String> cycle = new ArrayList<>(
                    activeDefinitions.subList(cycleStart, activeDefinitions.size()));
            cycle.add(definitionName);
            throw new IllegalArgumentException(
                    "component definition cycle: " + String.join(" -> ", cycle)
                            + " (at instance " + instanceId + ")");
        }
    }

    private static Flattened flatten(Schematic root) {
        Flattener flattener = new Flattener(root);
        flattener.compileScope(root, "", true, Map.of(), 0, List.of());
        flattener.connectNets();
        return flattener.result();
    }

    private static boolean supportsUnconnectedInputPruning(String typeId) {
        return switch (typeId) {
            case "and", "or", "nand", "nor", "xor", "xnor" -> true;
            default -> false;
        };
    }

    private static GateType singleInputEquivalent(GateType type) {
        return switch (type) {
            case AND, OR, XOR -> GateType.BUFFER;
            case NAND, NOR, XNOR -> GateType.NOT;
            default -> throw new IllegalArgumentException(
                    "no single-input equivalent for " + type);
        };
    }

    private static boolean isBoundaryPort(PlacedComponent component) {
        return component.typeId().equals("input")
                || component.typeId().equals("clock")
                || component.typeId().equals("output");
    }

    private static String boundaryPinId(ComponentDefinition.Port port) {
        return port.direction() == ComponentDefinition.Direction.OUT ? "in0" : "out";
    }

    private record PinKey(String componentId, String pinId) {
    }

    private record ScopedPinKey(String scopePath, String componentId, String pinId) {
    }

    private record NetRef(String scopePath, int index) {
    }

    private record PendingPin(NetRef net, Pin pin) {
    }

    private record ScopeTopology(
            String path,
            Connectivity.Result topology,
            Map<Connectivity.Net, NetRef> netByConnectivityNet,
            Map<PinKey, NetRef> netByPin,
            Set<PinKey> barePins) {
        private NetRef requireNet(String componentId, String pinId) {
            NetRef ref = netByPin.get(new PinKey(componentId, pinId));
            if (ref == null) {
                throw new IllegalStateException(
                        "no schematic net for " + path + componentId + "." + pinId);
            }
            return ref;
        }
    }

    private record Flattened(
            Circuit circuit,
            Map<String, InputPin> inputs,
            Set<String> clockIds,
            Map<String, OutputProbe> outputs,
            Map<String, Gate> gates,
            Set<String> allGateIds,
            ScopeTopology rootScope,
            UnionFind<NetRef> unionFind,
            Map<NetRef, Pin> representativePins) {
    }

    private static final class Flattener {
        private final Circuit circuit = new Circuit();
        private final Map<String, InputPin> inputs = new LinkedHashMap<>();
        private final Set<String> clockIds = new LinkedHashSet<>();
        private final Map<String, OutputProbe> outputs = new LinkedHashMap<>();
        private final Map<String, Gate> gates = new LinkedHashMap<>();
        private final Set<String> allGateIds = new LinkedHashSet<>();
        private final List<PendingPin> pendingPins = new ArrayList<>();
        private final UnionFind<NetRef> unionFind = new UnionFind<>();
        private final Map<NetRef, Pin> representativePins = new LinkedHashMap<>();
        private ScopeTopology rootScope;

        private Flattener(Schematic root) {
            Objects.requireNonNull(root, "root");
        }

        private void compileScope(
                Schematic schematic,
                String scopePath,
                boolean topLevel,
                Map<String, NetRef> boundaryBindings,
                int nestingDepth,
                List<String> activeDefinitions) {
            ScopeTopology scope = buildScopeTopology(schematic, scopePath);
            if (topLevel) {
                rootScope = scope;
            } else {
                bindBoundaryPorts(schematic, scope, boundaryBindings);
            }

            boolean ignoreBareInputs =
                    schematic.undefinedInputMode() == UndefinedInputMode.IGNORE;
            for (PlacedComponent placed : schematic.components()) {
                if (!topLevel && isBoundaryPort(placed)) {
                    continue;
                }
                String engineId = scopePath + placed.id();
                switch (placed.typeId()) {
                    case "input" -> {
                        InputPin input = new InputPin(engineId);
                        circuit.addComponent(input);
                        inputs.put(placed.id(), input);
                        addPending(scope, placed.id(), "out", input.output());
                    }
                    case "clock" -> {
                        InputPin clock = new InputPin(engineId);
                        circuit.addComponent(clock);
                        inputs.put(placed.id(), clock);
                        clockIds.add(placed.id());
                        addPending(scope, placed.id(), "out", clock.output());
                    }
                    case "output" -> {
                        OutputProbe output = new OutputProbe(engineId);
                        circuit.addComponent(output);
                        outputs.put(placed.id(), output);
                        addPending(scope, placed.id(), "in0", output.input());
                    }
                    default -> compileGate(
                            schematic, scope, placed, engineId, ignoreBareInputs);
                }
            }

            for (ComponentInstance instance : schematic.componentInstances()) {
                ComponentDefinition definition =
                        schematic.componentDefinitionFor(instance);
                SchematicCompiler.checkDefinitionEntry(
                        activeDefinitions, definition.name(), instance.id());
                int childDepth = nestingDepth + 1;
                if (childDepth > MAX_COMPONENT_NESTING_DEPTH) {
                    throw new IllegalArgumentException(
                            "component nesting depth exceeds " + MAX_COMPONENT_NESTING_DEPTH
                                    + " at " + scopePath + instance.id()
                                    + " (definition " + definition.name() + ")");
                }

                Map<String, NetRef> childBindings = new LinkedHashMap<>();
                for (ComponentDefinition.Port port : definition.ports()) {
                    childBindings.put(port.name(),
                            scope.requireNet(instance.id(), port.name()));
                }
                List<String> nextStack = new ArrayList<>(activeDefinitions);
                nextStack.add(definition.name());
                String childPath = scopePath + instance.id() + "/";
                Schematic internal = definition.internalCircuit();
                compileScope(internal, childPath, false, childBindings,
                        childDepth, List.copyOf(nextStack));
            }
        }

        private ScopeTopology buildScopeTopology(Schematic schematic, String path) {
            Connectivity.Result topology = Connectivity.analyze(
                    schematic.pinPositions(), schematic.wires());
            Map<Connectivity.Net, NetRef> netByConnectivityNet = new LinkedHashMap<>();
            Map<PinKey, NetRef> netByPin = new LinkedHashMap<>();
            Set<PinKey> barePins = new HashSet<>();
            int index = 0;
            for (Connectivity.Net net : topology.nets()) {
                NetRef ref = new NetRef(path, index++);
                netByConnectivityNet.put(net, ref);
                unionFind.add(ref);
                for (Connectivity.PinPosition pin : net.pins()) {
                    netByPin.put(new PinKey(pin.componentId(), pin.pinId()), ref);
                }
                if (net.segments().isEmpty() && net.pins().size() == 1) {
                    Connectivity.PinPosition onlyPin = net.pins().iterator().next();
                    barePins.add(new PinKey(onlyPin.componentId(), onlyPin.pinId()));
                }
            }
            return new ScopeTopology(path, topology,
                    Map.copyOf(netByConnectivityNet), Map.copyOf(netByPin), Set.copyOf(barePins));
        }

        private void bindBoundaryPorts(
                Schematic internal,
                ScopeTopology childScope,
                Map<String, NetRef> parentBindings) {
            Map<String, PlacedComponent> componentByPortName = new HashMap<>();
            for (PlacedComponent component : internal.components()) {
                if (!isBoundaryPort(component)) {
                    continue;
                }
                if (component.label().isBlank()) {
                    throw new IllegalArgumentException(
                            "component definition boundary port has no Name: "
                                    + childScope.path() + component.id());
                }
                PlacedComponent previous = componentByPortName.putIfAbsent(
                        component.label(), component);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate component definition port name: " + component.label());
                }
            }
            for (Map.Entry<String, NetRef> binding : parentBindings.entrySet()) {
                PlacedComponent portComponent = componentByPortName.get(binding.getKey());
                if (portComponent == null) {
                    throw new IllegalArgumentException(
                            "definition port component is missing: " + binding.getKey());
                }
                String pinId;
                if (portComponent.typeId().equals("output")) {
                    pinId = "in0";
                } else {
                    pinId = "out";
                }
                NetRef internalNet = childScope.requireNet(portComponent.id(), pinId);
                unionFind.union(internalNet, binding.getValue());
            }
        }

        private void compileGate(
                Schematic schematic,
                ScopeTopology scope,
                PlacedComponent placed,
                String engineId,
                boolean ignoreBareInputs) {
            GateDef definition = schematic.definitionFor(placed);
            allGateIds.add(engineId);
            List<Integer> retainedInputIndexes = new ArrayList<>();
            for (int index = 0; index < definition.inputs().size(); index++) {
                PinKey key = new PinKey(placed.id(), "in" + index);
                if (ignoreBareInputs && supportsUnconnectedInputPruning(placed.typeId())
                        && scope.barePins().contains(key)) {
                    continue;
                }
                retainedInputIndexes.add(index);
            }

            if (retainedInputIndexes.isEmpty()) {
                // Keep the output terminal present without inventing a gate driver.
                OutputProbe undrivenOutput = new OutputProbe(engineId);
                circuit.addComponent(undrivenOutput);
                addPending(scope, placed.id(), "out", undrivenOutput.input());
                return;
            }

            GateType gateType = GateType.valueOf(
                    placed.typeId().toUpperCase(java.util.Locale.ROOT));
            if (retainedInputIndexes.size() == 1
                    && supportsUnconnectedInputPruning(placed.typeId())) {
                gateType = singleInputEquivalent(gateType);
            }
            Gate gate = new Gate(engineId, gateType, retainedInputIndexes.size());
            circuit.addComponent(gate);
            gates.put(engineId, gate);
            for (int engineIndex = 0;
                    engineIndex < retainedInputIndexes.size(); engineIndex++) {
                int schematicIndex = retainedInputIndexes.get(engineIndex);
                addPending(scope, placed.id(), "in" + schematicIndex,
                        gate.input(engineIndex));
            }
            addPending(scope, placed.id(), "out", gate.output());
        }

        private void addPending(ScopeTopology scope, String componentId, String pinId, Pin pin) {
            pendingPins.add(new PendingPin(scope.requireNet(componentId, pinId), pin));
        }

        private void connectNets() {
            for (PendingPin pending : pendingPins) {
                NetRef root = unionFind.find(pending.net());
                Pin first = representativePins.putIfAbsent(root, pending.pin());
                if (first != null && first != pending.pin()) {
                    circuit.connect(first, pending.pin());
                }
            }
        }

        private Flattened result() {
            if (rootScope == null) {
                throw new IllegalStateException("top-level schematic was not compiled");
            }
            return new Flattened(circuit,
                    Map.copyOf(inputs), Set.copyOf(clockIds), Map.copyOf(outputs),
                    Map.copyOf(gates), Set.copyOf(allGateIds), rootScope,
                    unionFind, Map.copyOf(representativePins));
        }
    }

    /** Immutable values rendered by the UI and reused for stateful rebuilds. */
    public record SimulationResult(
            Connectivity.Result topology,
            Map<Connectivity.Net, Value> netValues,
            Map<WireSegment, Value> wireValues,
            Map<String, Value> outputValues,
            Map<String, Value> inputValues,
            Map<String, Value> gateOutputValues,
            boolean oscillating) {
        public SimulationResult {
            Objects.requireNonNull(topology, "topology");
            netValues = Map.copyOf(Objects.requireNonNull(netValues, "netValues"));
            wireValues = Map.copyOf(Objects.requireNonNull(wireValues, "wireValues"));
            outputValues = Map.copyOf(Objects.requireNonNull(outputValues, "outputValues"));
            inputValues = Map.copyOf(Objects.requireNonNull(inputValues, "inputValues"));
            gateOutputValues = Map.copyOf(Objects.requireNonNull(gateOutputValues, "gateOutputValues"));
        }

        public Value valueOf(WireSegment wire) {
            return wireValues.getOrDefault(
                    Objects.requireNonNull(wire, "wire"), Value.X);
        }
    }
}
