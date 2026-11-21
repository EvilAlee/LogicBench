package io.github.evilalee.gatelab.synth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, semantics-checked NAND/NOR/XNOR mapping for combinational netlists.
 * Every local rewrite removes at least one gate and requires single-consumer
 * inverter/inner gates, so the pass reaches a finite fixpoint.
 */
public final class InvertingGateMapper {
    private InvertingGateMapper() {
    }

    public static Netlist optimize(Netlist original) {
        if (original == null) throw new NullPointerException("original");
        if (original.feedbackAllowed() || original.inputs().size() > 12
                || original.gates().size() < 2) {
            return original;
        }

        final TruthTable before;
        try {
            before = TruthTableGenerator.generate(original);
        } catch (RuntimeException exception) {
            return original;
        }

        Netlist candidate = original;
        for (int pass = 0; pass < original.gates().size(); pass++) {
            Netlist next = rewriteOnce(candidate);
            if (next == candidate || next.gates().size() >= candidate.gates().size()) break;
            candidate = next;
        }
        if (candidate == original || candidate.gates().size() >= original.gates().size()) {
            return original;
        }

        try {
            TruthTable after = TruthTableGenerator.generate(candidate);
            return equivalent(before, after) ? candidate : original;
        } catch (RuntimeException exception) {
            return original;
        }
    }

    private static boolean equivalent(TruthTable first, TruthTable second) {
        if (!first.inputNames().equals(second.inputNames())
                || !first.outputNames().equals(second.outputNames())
                || first.rows().size() != second.rows().size()) return false;
        for (int row = 0; row < first.rows().size(); row++) {
            TruthTable.Row left = first.rows().get(row);
            TruthTable.Row right = second.rows().get(row);
            if (!left.inputBits().equals(right.inputBits())
                    || left.oscillating() != right.oscillating()
                    || !left.outputValues().equals(right.outputValues())) return false;
        }
        return true;
    }

    /** Performs one deterministic rewrite, returning the original on a miss. */
    private static Netlist rewriteOnce(Netlist netlist) {
        Map<String, Netlist.Gate> producers = new LinkedHashMap<>();
        netlist.gates().forEach(gate -> producers.put(gate.id(), gate));
        Map<String, Integer> consumers = consumerCounts(netlist);

        // NOT(AND/OR/XOR/NAND/NOR/XNOR) -> complementary gate; NOT(NOT x) -> x.
        for (Netlist.Gate outer : netlist.gates()) {
            if (outer.type() != GateOperator.NOT) continue;
            Netlist.Gate inner = producers.get(outer.inputs().getFirst());
            if (inner == null || consumers.getOrDefault(inner.id(), 0) != 1) continue;
            if (inner.type() == GateOperator.NOT) {
                String signal = inner.inputs().getFirst();
                return rebuild(netlist, Set.of(inner.id(), outer.id()), Map.of(),
                        Map.of(inner.id(), signal, outer.id(), signal));
            }
            GateOperator complement = complement(inner.type());
            if (complement != null) {
                Netlist.Gate replacement = new Netlist.Gate(
                        inner.id(), complement, inner.inputs());
                return rebuild(netlist, Set.of(outer.id()), Map.of(inner.id(), replacement),
                        Map.of(outer.id(), inner.id()));
            }
        }

        // AND(NOT a, NOT b) -> NOR(a,b); OR(NOT a, NOT b) -> NAND(a,b).
        for (Netlist.Gate gate : netlist.gates()) {
            if (gate.type() != GateOperator.AND && gate.type() != GateOperator.OR) continue;
            Netlist.Gate left = producers.get(gate.inputs().get(0));
            Netlist.Gate right = producers.get(gate.inputs().get(1));
            if (!isSingleConsumerNot(left, consumers)
                    || !isSingleConsumerNot(right, consumers)
                    || left.id().equals(right.id())) continue;
            GateOperator mapped = gate.type() == GateOperator.AND
                    ? GateOperator.NOR : GateOperator.NAND;
            Netlist.Gate replacement = new Netlist.Gate(gate.id(), mapped,
                    List.of(left.inputs().getFirst(), right.inputs().getFirst()));
            return rebuild(netlist, Set.of(left.id(), right.id()),
                    Map.of(gate.id(), replacement), Map.of());
        }

        // XOR(NOT a,b) and XOR(a,NOT b) -> XNOR(a,b).
        for (Netlist.Gate gate : netlist.gates()) {
            if (gate.type() != GateOperator.XOR) continue;
            Netlist.Gate left = producers.get(gate.inputs().get(0));
            Netlist.Gate right = producers.get(gate.inputs().get(1));
            if (isSingleConsumerNot(left, consumers)) {
                Netlist.Gate replacement = new Netlist.Gate(gate.id(), GateOperator.XNOR,
                        List.of(left.inputs().getFirst(), gate.inputs().get(1)));
                return rebuild(netlist, Set.of(left.id()), Map.of(gate.id(), replacement), Map.of());
            }
            if (isSingleConsumerNot(right, consumers)) {
                Netlist.Gate replacement = new Netlist.Gate(gate.id(), GateOperator.XNOR,
                        List.of(gate.inputs().get(0), right.inputs().getFirst()));
                return rebuild(netlist, Set.of(right.id()), Map.of(gate.id(), replacement), Map.of());
            }
        }
        return netlist;
    }

    private static boolean isSingleConsumerNot(
            Netlist.Gate gate, Map<String, Integer> consumers) {
        return gate != null && gate.type() == GateOperator.NOT
                && consumers.getOrDefault(gate.id(), 0) == 1;
    }

    private static GateOperator complement(GateOperator operator) {
        return switch (operator) {
            case AND -> GateOperator.NAND;
            case NAND -> GateOperator.AND;
            case OR -> GateOperator.NOR;
            case NOR -> GateOperator.OR;
            case XOR -> GateOperator.XNOR;
            case XNOR -> GateOperator.XOR;
            default -> null;
        };
    }

    private static Map<String, Integer> consumerCounts(Netlist netlist) {
        Map<String, Integer> counts = new HashMap<>();
        for (Netlist.Gate gate : netlist.gates()) {
            for (String input : gate.inputs()) counts.merge(input, 1, Integer::sum);
        }
        for (Netlist.Output output : netlist.outputs()) {
            counts.merge(output.source(), 1, Integer::sum);
        }
        return counts;
    }

    private static Netlist rebuild(
            Netlist source, Set<String> removed, Map<String, Netlist.Gate> replacements,
            Map<String, String> aliases) {
        List<Netlist.Gate> gates = new ArrayList<>();
        for (Netlist.Gate original : source.gates()) {
            if (removed.contains(original.id())) continue;
            Netlist.Gate selected = replacements.getOrDefault(original.id(), original);
            List<String> inputs = selected.inputs().stream()
                    .map(signal -> resolve(signal, aliases)).toList();
            gates.add(new Netlist.Gate(selected.id(), selected.type(), inputs));
        }
        List<Netlist.Output> outputs = source.outputs().stream()
                .map(output -> new Netlist.Output(output.name(), resolve(output.source(), aliases)))
                .toList();
        Netlist result = new Netlist(source.inputs(), outputs, gates);
        if (result.gates().size() >= source.gates().size()) {
            throw new IllegalStateException("inverting-gate rewrite did not reduce gate count");
        }
        return result;
    }

    private static String resolve(String signal, Map<String, String> aliases) {
        String current = signal;
        Set<String> seen = new LinkedHashSet<>();
        while (aliases.containsKey(current)) {
            if (!seen.add(current)) throw new IllegalStateException("cyclic signal alias");
            current = aliases.get(current);
        }
        return current;
    }
}
