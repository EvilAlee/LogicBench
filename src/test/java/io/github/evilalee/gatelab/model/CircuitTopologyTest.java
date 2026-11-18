package io.github.evilalee.gatelab.model;

import io.github.evilalee.gatelab.sim.Simulator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitTopologyTest {
    @Test
    void unionFindMergesNetsAndTopologyRebuildSplitsThemAfterWireRemoval() {
        Circuit circuit = new Circuit();
        InputPin source = new InputPin("source");
        OutputProbe first = new OutputProbe("first");
        OutputProbe second = new OutputProbe("second");
        circuit.addComponent(source);
        circuit.addComponent(first);
        circuit.addComponent(second);

        Wire firstWire = circuit.connect(source.output(), first.input());
        Wire secondWire = circuit.connect(first.input(), second.input());

        Simulator simulator = new Simulator(circuit);
        simulator.setInputValue(source, Value.ONE);
        simulator.settle();

        assertEquals(3, simulator.getNetPins(source.output()).size());
        assertTrue(simulator.getNetPins(source.output()).containsAll(
                Set.of(source.output(), first.input(), second.input())));
        assertEquals(Value.ONE, simulator.getProbeValue(first));
        assertEquals(Value.ONE, simulator.getProbeValue(second));
        assertEquals(1, simulator.getNetCount());

        assertTrue(circuit.disconnect(secondWire));
        simulator.settle();

        assertEquals(2, simulator.getNetPins(source.output()).size());
        assertEquals(1, simulator.getNetPins(second.input()).size());
        assertEquals(Value.ONE, simulator.getNetValue(source.output()));
        assertEquals(Value.X, simulator.getProbeValue(second));
        assertFalse(circuit.disconnect(secondWire));
        assertTrue(circuit.disconnect(firstWire));
        simulator.settle();
        assertEquals(1, simulator.getNetPins(source.output()).size());
        assertEquals(Value.ONE, simulator.getNetValue(source.output()));
    }

    @Test
    void removingAComponentAlsoRemovesItsIncidentWires() {
        Circuit circuit = new Circuit();
        InputPin source = new InputPin("source");
        OutputProbe probe = new OutputProbe("probe");
        circuit.addComponent(source);
        circuit.addComponent(probe);
        circuit.connect(source.output(), probe.input());

        assertTrue(circuit.removeComponent(probe));
        assertTrue(circuit.wires().isEmpty());
        assertFalse(circuit.contains(probe));
    }
}
