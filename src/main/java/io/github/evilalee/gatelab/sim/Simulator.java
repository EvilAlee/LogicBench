package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Circuit;
import io.github.evilalee.gatelab.model.Component;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.InputPin;
import io.github.evilalee.gatelab.model.OutputProbe;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.model.Wire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Headless, event-driven simulator. Each changed gate output is visible on its
 * net after that gate's tick; affected gates are scheduled for the next tick.
 */
public final class Simulator {
    /**
     * Minimum default tick budget for small circuits. Larger circuits receive
     * a budget derived from their gate count.
     */
    public static final int DEFAULT_TICK_LIMIT = 256;

    private static final int TICKS_PER_GATE = 4;
    private static final int TICK_LIMIT_OVERHEAD = 32;

    private final Circuit circuit;
    private final int tickLimit;
    private final Map<Pin, NetState> netByPin = new HashMap<>();
    private final List<NetState> nets = new ArrayList<>();
    private final Map<Pin, Value> gateOutputValues = new HashMap<>();
    private final Set<Gate> scheduledGates = new LinkedHashSet<>();

    private long builtRevision = Long.MIN_VALUE;
    private boolean oscillating;
    private int ticks;

    public Simulator(Circuit circuit) {
        this(Objects.requireNonNull(circuit, "circuit"), calculateDefaultTickLimit(circuit));
    }

    public Simulator(Circuit circuit, int tickLimit) {
        this.circuit = Objects.requireNonNull(circuit, "circuit");
        if (tickLimit < 1) {
            throw new IllegalArgumentException("tick limit must be at least one");
        }
        this.tickLimit = tickLimit;
    }

    private static int calculateDefaultTickLimit(Circuit circuit) {
        long gateCount = circuit.components().stream()
                .filter(Gate.class::isInstance)
                .count();
        long scaledLimit = gateCount * TICKS_PER_GATE + TICK_LIMIT_OVERHEAD;
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max((long) DEFAULT_TICK_LIMIT, scaledLimit));
    }

    public void setInputValue(InputPin input, Value value) {
        Objects.requireNonNull(input, "input");
        if (!circuit.contains(input)) {
            throw new IllegalArgumentException("input is not part of this circuit: " + input.id());
        }
        input.setValue(Objects.requireNonNull(value, "value"));
    }

    /**
     * Propagates pending changes until no gates remain scheduled or the tick
     * limit is reached. Remaining events survive the call, so another settle()
     * resumes an oscillating circuit instead of silently dropping its queue.
     */
    public void settle() {
        ensureTopology();
        oscillating = false;
        ticks = 0;
        refreshAllNets(scheduledGates);

        while (!scheduledGates.isEmpty() && ticks < tickLimit) {
            List<Gate> currentTick = List.copyOf(scheduledGates);
            scheduledGates.clear();

            Map<Pin, Value> updates = new LinkedHashMap<>();
            for (Gate gate : currentTick) {
                List<Value> inputs = gate.inputs().stream()
                        .map(this::valueOnPin)
                        .toList();
                updates.put(gate.output(), gate.evaluate(inputs));
            }

            Set<NetState> affectedNets = new LinkedHashSet<>();
            for (Map.Entry<Pin, Value> update : updates.entrySet()) {
                Pin output = update.getKey();
                Value next = update.getValue();
                Value previous = gateOutputValues.put(output, next);
                if (previous != next) {
                    NetState net = netByPin.get(output);
                    if (net != null) {
                        affectedNets.add(net);
                    }
                }
            }

            for (NetState net : affectedNets) {
                Value next = resolve(net);
                if (next != net.value) {
                    net.value = next;
                    enqueueInputGates(net, scheduledGates);
                }
            }
            ticks++;
        }

        oscillating = !scheduledGates.isEmpty();
        updateProbes();
    }

    public Value getPinValue(Pin pin) {
        return getNetValue(pin);
    }

    public Value getNetValue(Pin pin) {
        requireCircuitPin(pin);
        ensureTopology();
        NetState net = netByPin.get(pin);
        return net == null ? Value.X : net.value;
    }

    public Set<Pin> getNetPins(Pin pin) {
        requireCircuitPin(pin);
        ensureTopology();
        NetState net = netByPin.get(pin);
        return net == null ? Set.of() : Set.copyOf(net.pins);
    }

    public Value getProbeValue(OutputProbe probe) {
        Objects.requireNonNull(probe, "probe");
        if (!circuit.contains(probe)) {
            throw new IllegalArgumentException("probe is not part of this circuit: " + probe.id());
        }
        return getNetValue(probe.input());
    }

    public int getNetCount() {
        ensureTopology();
        return nets.size();
    }

    public int ticksUsed() {
        return ticks;
    }

    public boolean isOscillating() {
        return oscillating;
    }

    private void ensureTopology() {
        if (builtRevision == circuit.revision()) {
            return;
        }

        List<Pin> pins = circuit.pins();
        UnionFind<Pin> unionFind = new UnionFind<>();
        pins.forEach(unionFind::add);
        for (Wire wire : circuit.wires()) {
            unionFind.union(wire.first(), wire.second());
        }

        Map<Pin, Set<Pin>> groups = new LinkedHashMap<>();
        for (Pin pin : pins) {
            groups.computeIfAbsent(unionFind.find(pin), ignored -> new LinkedHashSet<>()).add(pin);
        }

        netByPin.clear();
        nets.clear();
        for (Set<Pin> group : groups.values()) {
            NetState net = new NetState(group);
            nets.add(net);
            for (Pin pin : group) {
                netByPin.put(pin, net);
            }
        }

        scheduledGates.clear();
        Set<Pin> activeGateOutputs = new LinkedHashSet<>();
        for (Component component : circuit.components()) {
            if (component instanceof Gate gate) {
                scheduledGates.add(gate);
                activeGateOutputs.add(gate.output());
                // A topology rebuild must not invent a concrete power-up state.
                gateOutputValues.put(gate.output(), Value.X);
            }
        }
        gateOutputValues.keySet().retainAll(activeGateOutputs);
        builtRevision = circuit.revision();
    }

    private void refreshAllNets(Set<Gate> pending) {
        for (NetState net : nets) {
            Value next = resolve(net);
            if (next != net.value) {
                net.value = next;
                enqueueInputGates(net, pending);
            }
        }
    }

    private Value resolve(NetState net) {
        Value driven = null;
        boolean hasDriver = false;

        for (Pin pin : net.pins) {
            if (pin.direction() != Pin.Direction.OUTPUT) {
                continue;
            }
            hasDriver = true;
            Value candidate;
            if (pin.component() instanceof InputPin input) {
                candidate = input.value();
            } else {
                candidate = gateOutputValues.getOrDefault(pin, Value.X);
            }

            if (candidate == Value.X) {
                return Value.X;
            }
            if (driven == null) {
                driven = candidate;
            } else if (driven != candidate) {
                // The legacy engine uses null for electrical contention.
                return Value.X;
            }
        }

        return hasDriver && driven != null ? driven : Value.X;
    }

    private void enqueueInputGates(NetState net, Set<Gate> pending) {
        for (Pin pin : net.pins) {
            if (pin.direction() == Pin.Direction.INPUT && pin.component() instanceof Gate gate) {
                pending.add(gate);
            }
        }
    }

    private Value valueOnPin(Pin pin) {
        NetState net = netByPin.get(pin);
        return net == null ? Value.X : net.value;
    }

    private void updateProbes() {
        for (Component component : circuit.components()) {
            if (component instanceof OutputProbe probe) {
                probe.updateValue(valueOnPin(probe.input()));
            }
        }
    }

    private void requireCircuitPin(Pin pin) {
        Objects.requireNonNull(pin, "pin");
        if (!circuit.contains(pin)) {
            throw new IllegalArgumentException("pin is not part of this circuit: " + pin.id());
        }
    }

    private static final class NetState {
        private final Set<Pin> pins;
        private Value value = Value.X;

        private NetState(Collection<Pin> pins) {
            this.pins = Set.copyOf(pins);
        }
    }
}
