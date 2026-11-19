package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.ClockStepper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SequentialExamplesBehaviorTest {
    @Test
    @Order(1)
    void dLatchSetsHoldsResetsAndCanStartFromAnUnknownSimulationReset() throws IOException {
        Schematic schematic = load("d-latch");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        assertEquals(Value.X, output(schematic, result, "Q"),
                "a fresh feedback latch should begin unknown");

        result = stepper.reset(schematic);
        assertEquals(Value.X, output(schematic, result, "Q"),
                "simulation reset must discard the prior latch state");

        setInput(schematic, "D", true);
        setInput(schematic, "EN", true);
        result = settle(schematic, result);
        assertEquals(Value.ONE, output(schematic, result, "Q"),
                "EN=1, D=1 must set Q from reset X");

        setInput(schematic, "EN", false);
        setInput(schematic, "D", false);
        result = settle(schematic, result);
        assertEquals(Value.ONE, output(schematic, result, "Q"),
                "EN=0 must hold Q even when D changes");

        setInput(schematic, "EN", true);
        result = settle(schematic, result);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "EN=1, D=0 must reset Q");
    }

    @Test
    @Order(2)
    void dFlipFlopResetsAndCapturesDOnlyOnTheRisingEdge() throws IOException {
        Schematic schematic = load("d-flip-flop");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "fixture's initial reset level should establish Q=0");

        // The committed fixture currently calls this input RESET_N; it is the R
        // signal referred to by the behaviour specification.
        schematic.setInputValue(reset.id(), true);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        result = fullCycle(stepper, schematic, result);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "R=1 for two clock cycles must hold Q at zero");

        schematic.setInputValue(reset.id(), false);
        setInput(schematic, "D", true);
        result = settle(schematic, result);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "D must not affect Q before the next rising edge");

        result = stepper.step(schematic, result); // rising edge
        assertEquals(Value.ONE, output(schematic, result, "Q"),
                "R=0, D=1 must be captured on the rising edge");

        setInput(schematic, "D", false);
        result = settle(schematic, result);
        assertEquals(Value.ONE, output(schematic, result, "Q"),
                "Q must hold while CLK remains high");
    }

    @Test
    @Order(3)
    void divideByTwoTogglesOncePerFullClockCycleAfterReset() throws IOException {
        Schematic schematic = load("divide-by-2");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "initial reset should establish Q=0");

        schematic.setInputValue(reset.id(), true);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "R=1 during the reset cycle must hold Q=0");

        schematic.setInputValue(reset.id(), false);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        assertEquals(Value.ONE, output(schematic, result, "Q"),
                "the first full clock cycle after reset must toggle Q high");
        result = fullCycle(stepper, schematic, result);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "the second full clock cycle must toggle Q low");
    }

    @Test
    @Order(4)
    void twoBitCounterCyclesThroughAllFourBinaryStates() throws IOException {
        Schematic schematic = load("counter-2bit");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        assertCounterState(schematic, result, "00", "reset state");

        schematic.setInputValue(reset.id(), true);
        result = settle(schematic, result);
        schematic.setInputValue(reset.id(), false);
        result = settle(schematic, result);

        String[] expected = {"01", "10", "11", "00"};
        for (String state : expected) {
            result = fullCycle(stepper, schematic, result);
            assertCounterState(schematic, result, state, "after full clock cycle");
        }
    }

    private static Schematic load(String name) throws IOException {
        return Main.loadExampleSchematic("examples/" + name + ".lbc");
    }

    private static PlacedComponent resetInput(Schematic schematic) {
        return schematic.components().stream()
                .filter(component -> component.typeId().equals("input")
                        && (component.label().equals("R")
                                || component.label().equals("RESET_N")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No reset input labelled R or RESET_N; found " + inputLabels(schematic)));
    }

    private static List<String> inputLabels(Schematic schematic) {
        return schematic.components().stream()
                .filter(component -> component.typeId().equals("input")
                        || component.typeId().equals("clock"))
                .map(PlacedComponent::label)
                .toList();
    }

    private static void setInput(Schematic schematic, String label, boolean high) {
        PlacedComponent input = schematic.components().stream()
                .filter(component -> component.typeId().equals("input")
                        && component.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing input " + label));
        schematic.setInputValue(input.id(), high);
    }

    private static Value output(
            Schematic schematic, SimulationResult result, String label) {
        PlacedComponent output = schematic.components().stream()
                .filter(component -> component.typeId().equals("output")
                        && component.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing output " + label));
        return result.outputValues().get(output.id());
    }

    private static SimulationResult settle(Schematic schematic, SimulationResult previous) {
        return SchematicCompiler.compileAndSettle(schematic, previous);
    }

    private static SimulationResult fullCycle(
            ClockStepper stepper, Schematic schematic, SimulationResult result) {
        result = stepper.step(schematic, result);
        return stepper.step(schematic, result);
    }

    private static void assertCounterState(
            Schematic schematic, SimulationResult result, String state, String stage) {
        Value q1 = output(schematic, result, "Q1");
        Value q0 = output(schematic, result, "Q0");
        assertEquals(Value.valueOf(String.valueOf(state.charAt(0))),
                q1, stage + ": expected Q1Q0=" + state);
        assertEquals(Value.valueOf(String.valueOf(state.charAt(1))),
                q0, stage + ": expected Q1Q0=" + state);
    }
}
