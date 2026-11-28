package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Wire;
import io.github.evilalee.gatelab.sim.SchematicCompiler.CompiledCircuit;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Exact bit-parallel evaluator for flattened combinational circuits.
 * Bit r of word w represents input row 64*w+r. Unknown values retain
 * three-valued engine semantics and never count as known zeroes.
 */
public final class BitParallelEvaluator {
    public static final int MAX_INPUTS = 16;
    private static final int CHECK_WORD_INTERVAL = 8;

    private BitParallelEvaluator() {
    }

    public static final class Result {
        private final List<String> inputNames;
        private final List<String> outputNames;
        private final Map<String, Signal> outputs;
        private final int rowCount;

        private Result(List<String> inputs, List<String> names,
                       Map<String, Signal> values, int rows) {
            inputNames = List.copyOf(inputs);
            outputNames = List.copyOf(names);
            outputs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            rowCount = rows;
        }

        public List<String> inputNames() { return inputNames; }
        public List<String> outputNames() { return outputNames; }
        public int rowCount() { return rowCount; }

        public boolean isKnown(String output, int row) {
            Signal signal = requireOutput(output);
            checkRow(row);
            return (signal.known[row >>> 6] & (1L << (row & 63))) != 0;
        }

        public Value outputValue(String output, int row) {
            Signal signal = requireOutput(output);
            checkRow(row);
            int word = row >>> 6;
            long bit = 1L << (row & 63);
            if ((signal.known[word] & bit) == 0) return Value.X;
            return (signal.ones[word] & bit) == 0 ? Value.ZERO : Value.ONE;
        }

        public String inputBits(int row) {
            checkRow(row);
            StringBuilder bits = new StringBuilder(inputNames.size());
            for (int index = 0; index < inputNames.size(); index++) {
                bits.append(((row >>> (inputNames.size() - index - 1)) & 1) == 0 ? '0' : '1');
            }
            return bits.toString();
        }

        /** Corruption seam used by the sampled real-engine differential test. */
        public Result withOutputBitFlippedForTesting(String output, int row) {
            Signal original = requireOutput(output);
            checkRow(row);
            long[] ones = original.ones.clone();
            int word = row >>> 6;
            long bit = 1L << (row & 63);
            if ((original.known[word] & bit) != 0) ones[word] ^= bit;
            Map<String, Signal> changed = new LinkedHashMap<>(outputs);
            changed.put(output, new Signal(ones, original.known.clone()));
            return new Result(inputNames, outputNames, changed, rowCount);
        }

        private Signal requireOutput(String output) {
            Signal signal = outputs.get(Objects.requireNonNull(output, "output"));
            if (signal == null) throw new IllegalArgumentException("unknown parallel output: " + output);
            return signal;
        }

        private void checkRow(int row) {
            if (row < 0 || row >= rowCount) throw new IndexOutOfBoundsException("invalid row " + row);
        }
    }

    private record Signal(long[] ones, long[] known) { }

    public static Result evaluate(
            CompiledCircuit compiled,
            Map<String, String> inputComponentIds,
            Map<String, String> outputComponentIds) {
        Objects.requireNonNull(compiled, "compiled");
        Objects.requireNonNull(inputComponentIds, "inputComponentIds");
        Objects.requireNonNull(outputComponentIds, "outputComponentIds");
        List<String> inputNames = List.copyOf(inputComponentIds.keySet());
        List<String> outputNames = List.copyOf(outputComponentIds.keySet());
        if (inputNames.isEmpty() || inputNames.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("parallel evaluator supports 1 to 16 inputs");
        }
        if (outputNames.isEmpty()) throw new IllegalArgumentException("at least one output is required");
        int rows = 1 << inputNames.size();
        Evaluator evaluator = new Evaluator(compiled, inputNames, inputComponentIds, rows);
        Map<String, Signal> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : outputComponentIds.entrySet()) {
            OutputProbe probe = compiled.outputs().get(entry.getValue());
            if (probe == null) {
                throw new IllegalArgumentException("missing flattened output " + entry.getValue());
            }
            result.put(entry.getKey(), evaluator.signalOn(probe.input()));
        }
        return new Result(inputNames, outputNames, result, rows);
    }

    private static final class Evaluator {
        private final int rowCount;
        private final int wordCount;
        private final long[] valid;
        private final IdentityHashMap<Pin, Integer> netByPin = new IdentityHashMap<>();
        private final java.util.ArrayList<java.util.List<Pin>> drivers = new java.util.ArrayList<>();
        private final IdentityHashMap<InputPin, Signal> sources = new IdentityHashMap<>();
        private final IdentityHashMap<Gate, Signal> gateValues = new IdentityHashMap<>();
        private final IdentityHashMap<Gate, Boolean> evaluating = new IdentityHashMap<>();

        Evaluator(CompiledCircuit compiled, List<String> inputNames,
                  Map<String, String> inputIds, int rows) {
            rowCount = rows;
            wordCount = (rows + 63) >>> 6;
            valid = new long[wordCount];
            Arrays.fill(valid, -1L);
            int tail = rows & 63;
            if (tail != 0) valid[wordCount - 1] = (1L << tail) - 1L;

            buildNets(compiled.circuit());
            Map<String, InputPin> byId = new LinkedHashMap<>(compiled.inputs());
            for (int index = 0; index < inputNames.size(); index++) {
                String name = inputNames.get(index);
                InputPin pin = byId.get(inputIds.get(name));
                if (pin == null) throw new IllegalArgumentException("missing flattened input " + name);
                sources.put(pin, makeInput(index, inputNames.size()));
            }
            compiled.fixedInputs().forEach((pin, value) -> sources.put(pin, makeConstant(value)));
        }

        private void buildNets(Circuit circuit) {
            List<Pin> pins = circuit.pins();
            IdentityHashMap<Pin, Integer> positions = new IdentityHashMap<>();
            for (int index = 0; index < pins.size(); index++) positions.put(pins.get(index), index);
            DisjointSet dsu = new DisjointSet(pins.size());
            for (Wire wire : circuit.wires()) {
                Integer a = positions.get(wire.first()), b = positions.get(wire.second());
                if (a == null || b == null) throw new IllegalArgumentException("wire pin not in circuit");
                dsu.union(a, b);
            }
            Map<Integer, Integer> rootNets = new LinkedHashMap<>();
            for (int index = 0; index < pins.size(); index++) {
                int root = dsu.find(index);
                int net = rootNets.computeIfAbsent(root, ignored -> {
                    drivers.add(new java.util.ArrayList<>());
                    return drivers.size() - 1;
                });
                Pin pin = pins.get(index);
                netByPin.put(pin, net);
                if (pin.direction() == Pin.Direction.OUTPUT) drivers.get(net).add(pin);
            }
        }

        private Signal makeInput(int inputIndex, int inputCount) {
            long[] ones = new long[wordCount];
            long[] known = valid.clone();
            int shift = inputCount - inputIndex - 1;
            for (int word = 0; word < wordCount; word++) {
                check(word);
                int start = word << 6;
                long bits = 0;
                for (int bit = 0; bit < 64 && start + bit < rowCount; bit++) {
                    if ((((start + bit) >>> shift) & 1) != 0) bits |= 1L << bit;
                }
                ones[word] = bits;
            }
            return new Signal(ones, known);
        }

        private Signal makeConstant(Value value) {
            long[] ones = new long[wordCount];
            long[] known = new long[wordCount];
            if (value != Value.X) {
                known = valid.clone();
                if (value == Value.ONE) ones = valid.clone();
            }
            return new Signal(ones, known);
        }

        private Signal signalOn(Pin sink) {
            Integer net = netByPin.get(sink);
            if (net == null) throw new IllegalArgumentException("pin absent from flattened circuit: " + sink);
            return resolve(drivers.get(net));
        }

        private Signal resolve(List<Pin> netDrivers) {
            if (netDrivers.isEmpty()) return makeConstant(Value.X);
            long[] firstOnes = new long[wordCount];
            long[] known = valid.clone();
            long[] mismatches = new long[wordCount];
            boolean first = true;
            for (Pin driver : netDrivers) {
                Component component = driver.component();
                Signal value;
                if (component instanceof InputPin input) {
                    value = sources.get(input);
                    if (value == null) value = makeConstant(input.value());
                } else if (component instanceof Gate gate) {
                    value = gate(gate);
                } else {
                    value = makeConstant(Value.X);
                }
                if (first) {
                    System.arraycopy(value.ones, 0, firstOnes, 0, wordCount);
                    System.arraycopy(value.known, 0, known, 0, wordCount);
                    first = false;
                } else {
                    for (int word = 0; word < wordCount; word++) {
                        check(word);
                        mismatches[word] |= firstOnes[word] ^ value.ones[word];
                        known[word] &= value.known[word];
                    }
                }
            }
            long[] resolvedKnown = new long[wordCount];
            long[] resolvedOnes = new long[wordCount];
            for (int word = 0; word < wordCount; word++) {
                resolvedKnown[word] = known[word] & ~mismatches[word] & valid[word];
                resolvedOnes[word] = firstOnes[word] & resolvedKnown[word];
            }
            return new Signal(resolvedOnes, resolvedKnown);
        }

        private Signal gate(Gate gate) {
            Signal cached = gateValues.get(gate);
            if (cached != null) return cached;
            if (Boolean.TRUE.equals(evaluating.get(gate))) {
                throw new IllegalArgumentException("feedback reaches gate " + gate.id()
                        + "; bit-parallel AI verification is combinational only");
            }
            evaluating.put(gate, true);
            List<Signal> inputs = gate.inputs().stream().map(this::signalOn).toList();
            Signal value = apply(gate.type(), inputs);
            evaluating.remove(gate);
            gateValues.put(gate, value);
            return value;
        }

        private Signal apply(GateType type, List<Signal> inputs) {
            long[] ones = new long[wordCount];
            long[] known = new long[wordCount];
            for (int word = 0; word < wordCount; word++) {
                check(word);
                switch (type) {
                    case AND, NAND -> {
                        long knownZero = 0L, knownOne = valid[word];
                        for (Signal input : inputs) {
                            knownZero |= input.known[word] & ~input.ones[word];
                            knownOne &= input.known[word] & input.ones[word];
                        }
                        known[word] = (knownZero | knownOne) & valid[word];
                        ones[word] = knownOne & valid[word];
                        if (type == GateType.NAND) ones[word] = ~ones[word] & known[word];
                    }
                    case OR, NOR -> {
                        long knownOne = 0L, knownZero = valid[word];
                        for (Signal input : inputs) {
                            knownOne |= input.known[word] & input.ones[word];
                            knownZero &= input.known[word] & ~input.ones[word];
                        }
                        known[word] = (knownOne | knownZero) & valid[word];
                        ones[word] = knownOne & valid[word];
                        if (type == GateType.NOR) ones[word] = ~ones[word] & known[word];
                    }
                    case XOR, XNOR -> {
                        long parity = 0L, allKnown = valid[word];
                        for (Signal input : inputs) {
                            parity ^= input.ones[word];
                            allKnown &= input.known[word];
                        }
                        known[word] = allKnown & valid[word];
                        ones[word] = parity & known[word];
                        if (type == GateType.XNOR) ones[word] = ~ones[word] & known[word];
                    }
                    case NOT -> {
                        known[word] = inputs.getFirst().known[word] & valid[word];
                        ones[word] = ~inputs.getFirst().ones[word] & known[word];
                    }
                    case BUFFER -> {
                        known[word] = inputs.getFirst().known[word] & valid[word];
                        ones[word] = inputs.getFirst().ones[word] & known[word];
                    }
                }
            }
            return new Signal(ones, known);
        }

        private void check(int word) {
            if (word % CHECK_WORD_INTERVAL == 0 && Thread.currentThread().isInterrupted()) {
                throw new CancellationException("bit-parallel verification cancelled");
            }
        }
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;
        DisjointSet(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) parent[index] = index;
        }
        int find(int value) {
            if (parent[value] != value) parent[value] = find(parent[value]);
            return parent[value];
        }
        void union(int first, int second) {
            int a = find(first), b = find(second);
            if (a == b) return;
            if (rank[a] < rank[b]) parent[a] = b;
            else if (rank[a] > rank[b]) parent[b] = a;
            else { parent[b] = a; rank[a]++; }
        }
    }
}
