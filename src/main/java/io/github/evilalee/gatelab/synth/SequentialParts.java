package io.github.evilalee.gatelab.synth;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.GateType;
import io.github.evilalee.gatelab.model.Pin;

import java.util.Objects;

/**
 * Gate-level expansions for common sequential parts.
 *
 * <p>Each method appends fresh ordinary gates to the supplied circuit. No
 * sequential primitive or simulator-specific state is introduced.</p>
 */
public final class SequentialParts {
    private SequentialParts() {
    }

    /**
     * Expands a transparent-high NAND latch:
     * S_N=NAND(D,EN), R_N=NAND(NOT(D),EN),
     * Q=NAND(S_N,QN), QN=NAND(R_N,Q).
     */
    public static Outputs dLatch(Circuit circuit, String id, Pin data, Pin enable) {
        Objects.requireNonNull(circuit, "circuit");
        requireOutputPin(circuit, data, "data");
        requireOutputPin(circuit, enable, "enable");
        String prefix = requirePrefix(id);
        requireUnusedPrefix(circuit, prefix);

        Gate dataNot = addGate(circuit, prefix + ".data-not", GateType.NOT, 1);
        Gate setNand = addGate(circuit, prefix + ".set-nand", GateType.NAND, 2);
        Gate resetNand = addGate(circuit, prefix + ".reset-nand", GateType.NAND, 2);
        Gate qNand = addGate(circuit, prefix + ".q-nand", GateType.NAND, 2);
        Gate qnNand = addGate(circuit, prefix + ".qn-nand", GateType.NAND, 2);

        circuit.connect(data, dataNot.input(0));
        circuit.connect(data, setNand.input(0));
        circuit.connect(enable, setNand.input(1));
        circuit.connect(dataNot.output(), resetNand.input(0));
        circuit.connect(enable, resetNand.input(1));

        circuit.connect(setNand.output(), qNand.input(0));
        circuit.connect(qnNand.output(), qNand.input(1));
        circuit.connect(resetNand.output(), qnNand.input(0));
        circuit.connect(qNand.output(), qnNand.input(1));

        return new Outputs(qNand.output(), qnNand.output());
    }

    /**
     * Expands a positive-edge D flip-flop as two transparent-high NAND
     * latches in master/slave order. The master enable is NOT(CLK), the
     * slave enable is CLK, and the public output is the slave Q.
     */
    public static Outputs dff(Circuit circuit, String id, Pin data, Pin clock) {
        Objects.requireNonNull(circuit, "circuit");
        requireOutputPin(circuit, data, "data");
        requireOutputPin(circuit, clock, "clock");
        String prefix = requirePrefix(id);
        requireUnusedPrefix(circuit, prefix);

        Gate clockNot = addGate(circuit, prefix + ".clock-not", GateType.NOT, 1);
        circuit.connect(clock, clockNot.input(0));

        Outputs master = dLatch(circuit, prefix + ".master", data, clockNot.output());
        Outputs slave = dLatch(circuit, prefix + ".slave", master.q(), clock);
        return new Outputs(slave.q(), slave.qn());
    }

    /** Expands a T flip-flop with D=T XOR Q and a positive-edge D flip-flop. */
    public static Outputs tff(Circuit circuit, String id, Pin toggle, Pin clock) {
        Objects.requireNonNull(circuit, "circuit");
        requireOutputPin(circuit, toggle, "toggle");
        requireOutputPin(circuit, clock, "clock");
        String prefix = requirePrefix(id);
        requireUnusedPrefix(circuit, prefix);

        Gate nextData = addGate(circuit, prefix + ".toggle-xor", GateType.XOR, 2);
        circuit.connect(toggle, nextData.input(0));

        Outputs storage = dff(circuit, prefix + ".storage", nextData.output(), clock);
        circuit.connect(storage.q(), nextData.input(1));
        return storage;
    }

    /**
     * Expands a JK flip-flop using
     * D=(J AND NOT(Q)) OR (NOT(K) AND Q), followed by a positive-edge DFF.
     */
    public static Outputs jkff(Circuit circuit, String id, Pin j, Pin k, Pin clock) {
        Objects.requireNonNull(circuit, "circuit");
        requireOutputPin(circuit, j, "J");
        requireOutputPin(circuit, k, "K");
        requireOutputPin(circuit, clock, "clock");
        String prefix = requirePrefix(id);
        requireUnusedPrefix(circuit, prefix);

        Gate qNot = addGate(circuit, prefix + ".q-not", GateType.NOT, 1);
        Gate kNot = addGate(circuit, prefix + ".k-not", GateType.NOT, 1);
        Gate setTerm = addGate(circuit, prefix + ".set-term", GateType.AND, 2);
        Gate holdTerm = addGate(circuit, prefix + ".hold-term", GateType.AND, 2);
        Gate nextData = addGate(circuit, prefix + ".next-data", GateType.OR, 2);

        Outputs storage = dff(circuit, prefix + ".storage", nextData.output(), clock);

        circuit.connect(storage.q(), qNot.input(0));
        circuit.connect(k, kNot.input(0));
        circuit.connect(j, setTerm.input(0));
        circuit.connect(qNot.output(), setTerm.input(1));
        circuit.connect(kNot.output(), holdTerm.input(0));
        circuit.connect(storage.q(), holdTerm.input(1));
        circuit.connect(setTerm.output(), nextData.input(0));
        circuit.connect(holdTerm.output(), nextData.input(1));

        return storage;
    }

    private static Gate addGate(Circuit circuit, String id, GateType type, int inputCount) {
        Gate gate = new Gate(id, type, inputCount);
        circuit.addComponent(gate);
        return gate;
    }

    private static void requireOutputPin(Circuit circuit, Pin pin, String name) {
        Objects.requireNonNull(pin, name);
        if (!circuit.contains(pin)) {
            throw new IllegalArgumentException(name + " pin is not part of the circuit: " + pin.id());
        }
        if (pin.direction() != Pin.Direction.OUTPUT) {
            throw new IllegalArgumentException(name + " pin must be an output: " + pin.id());
        }
    }

    private static String requirePrefix(String id) {
        Objects.requireNonNull(id, "id");
        String prefix = id.trim();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("part id must not be blank");
        }
        return prefix;
    }

    private static void requireUnusedPrefix(Circuit circuit, String prefix) {
        for (Component component : circuit.components()) {
            if (component.id().equals(prefix) || component.id().startsWith(prefix + ".")) {
                throw new IllegalArgumentException(
                        "part id prefix is already in use: " + prefix);
            }
        }
    }

    /** The two outputs exposed by latch and flip-flop expansions. */
    public record Outputs(Pin q, Pin qn) {
        public Outputs {
            Objects.requireNonNull(q, "q");
            Objects.requireNonNull(qn, "qn");
        }
    }
}
