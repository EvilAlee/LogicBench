package io.github.evilalee.gatelab.synth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated, combinational signal graph.
 *
 * Inputs and gate ids share one signal namespace. Output labels are a separate
 * namespace because an output may legitimately be named after an input signal.
 * Output sources may be the literal "0" or "1" for constant-only expressions.
 */
public record Netlist(List<String> inputs, List<Output> outputs, List<Gate> gates) {
    public Netlist {
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        gates = List.copyOf(Objects.requireNonNull(gates, "gates"));
        validate(inputs, outputs, gates);
    }

    public record Output(String name, String source) {
        public Output {
            name = requireIdentifier(name, "output name");
            source = requireText(source, "output source");
        }
    }

    public record Gate(String id, GateOperator type, List<String> inputs) {
        public Gate {
            id = requireIdentifier(id, "gate id");
            Objects.requireNonNull(type, "type");
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            for (String input : inputs) {
                requireIdentifier(input, "gate input reference");
            }
        }
    }

    /** Gates ordered so every referenced gate appears before its consumer. */
    public List<Gate> topologicalGates() {
        Map<String, Gate> byId = new LinkedHashMap<>();
        gates.forEach(gate -> byId.put(gate.id(), gate));
        Map<String, Integer> state = new HashMap<>();
        List<Gate> ordered = new ArrayList<>(gates.size());
        List<String> path = new ArrayList<>();
        for (Gate gate : gates) {
            visit(gate, byId, state, path, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visit(
            Gate gate,
            Map<String, Gate> byId,
            Map<String, Integer> state,
            List<String> path,
            List<Gate> ordered) {
        int currentState = state.getOrDefault(gate.id(), 0);
        if (currentState == 2) {
            return;
        }
        if (currentState == 1) {
            int start = path.indexOf(gate.id());
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(gate.id());
            throw new IllegalArgumentException("combinational cycle detected: "
                    + String.join(" -> ", cycle));
        }

        state.put(gate.id(), 1);
        path.add(gate.id());
        for (String reference : gate.inputs()) {
            Gate dependency = byId.get(reference);
            if (dependency != null) {
                visit(dependency, byId, state, path, ordered);
            }
        }
        path.removeLast();
        state.put(gate.id(), 2);
        ordered.add(gate);
    }

    private static void validate(List<String> inputs, List<Output> outputs, List<Gate> gates) {
        if (outputs.isEmpty()) {
            throw new IllegalArgumentException("netlist must define at least one output");
        }

        Set<String> signalNames = new HashSet<>();
        for (String input : inputs) {
            String name = requireIdentifier(input, "input name");
            if (!signalNames.add(name)) {
                throw new IllegalArgumentException("duplicate input name: " + name);
            }
        }

        Map<String, Gate> gatesById = new LinkedHashMap<>();
        for (Gate gate : gates) {
            Objects.requireNonNull(gate, "gate");
            if (!signalNames.add(gate.id())) {
                throw new IllegalArgumentException(
                        "duplicate signal name or gate id: " + gate.id());
            }
            if (gate.inputs().size() != gate.type().inputCount()) {
                throw new IllegalArgumentException("gate " + gate.id() + " of type "
                        + gate.type() + " requires " + gate.type().inputCount()
                        + " inputs, got " + gate.inputs().size());
            }
            gatesById.put(gate.id(), gate);
        }

        for (Gate gate : gates) {
            for (int index = 0; index < gate.inputs().size(); index++) {
                String reference = gate.inputs().get(index);
                if (!signalNames.contains(reference)) {
                    throw new IllegalArgumentException("gate " + gate.id() + " input["
                            + index + "] references unknown signal: " + reference);
                }
            }
        }

        Set<String> outputNames = new HashSet<>();
        for (Output output : outputs) {
            Objects.requireNonNull(output, "output");
            if (!outputNames.add(output.name())) {
                throw new IllegalArgumentException("duplicate output name: " + output.name());
            }
            if (!output.source().equals("0") && !output.source().equals("1")
                    && !signalNames.contains(output.source())) {
                throw new IllegalArgumentException("output " + output.name()
                        + " references unknown source: " + output.source());
            }
        }

        // Cycles are representable for expression feedback. Combinational consumers
        // still call topologicalGates(), which rejects them before DAG-only work.
    }

    private static void validateAcyclic(List<Gate> gates, Map<String, Gate> gatesById) {
        Map<String, Integer> state = new HashMap<>();
        List<String> path = new ArrayList<>();
        List<Gate> ignoredOrder = new ArrayList<>();
        for (Gate gate : gates) {
            visit(gate, gatesById, state, path, ignoredOrder);
        }
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    label + " must match [A-Za-z][A-Za-z0-9_]*: " + value);
        }
        return value;
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
