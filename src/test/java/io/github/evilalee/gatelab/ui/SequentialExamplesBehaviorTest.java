package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.ClockStepper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    @TestFactory
    @Order(2)
    Stream<DynamicTest> dFlipFlopResetsAndCapturesDOnlyOnTheRisingEdge() throws IOException {
        Schematic schematic = load("d-flip-flop");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "fixture's initial reset level should establish Q=0");

        // The committed fixture calls this input RESET_N; it is the R
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
        Value observed = output(schematic, result, "Q");
        String display = "D flip-flop after R=0, D=1 and rising edge: observed Q=" + observed;
        return Stream.of(DynamicTest.dynamicTest(display,
                () -> assertEquals(Value.ONE, observed,
                        "the rising edge must capture D=1")));
    }

    @TestFactory
    @Order(3)
    Stream<DynamicTest> divideByTwoTogglesOncePerFullClockCycleAfterReset() throws IOException {
        Schematic schematic = load("divide-by-2");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        assertEquals(Value.ZERO, output(schematic, result, "Q"),
                "initial reset should establish Q=0");

        schematic.setInputValue(reset.id(), true);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        Value duringReset = output(schematic, result, "Q");
        List<DynamicTest> checks = new ArrayList<>();
        checks.add(DynamicTest.dynamicTest(
                "Divide-by-2 during R=1 reset cycle: observed Q=" + duringReset,
                () -> assertEquals(Value.ZERO, duringReset,
                        "R=1 during the reset cycle must hold Q=0")));
        if (duringReset != Value.ZERO) {
            return checks.stream();
        }

        schematic.setInputValue(reset.id(), false);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        Value first = output(schematic, result, "Q");
        checks.add(DynamicTest.dynamicTest(
                "Divide-by-2 after first released cycle: observed Q=" + first,
                () -> assertEquals(Value.ONE, first, "the first cycle must toggle Q high")));
        if (first != Value.ONE) {
            return checks.stream();
        }

        result = fullCycle(stepper, schematic, result);
        Value second = output(schematic, result, "Q");
        checks.add(DynamicTest.dynamicTest(
                "Divide-by-2 after second released cycle: observed Q=" + second,
                () -> assertEquals(Value.ZERO, second, "the second cycle must toggle Q low")));
        return checks.stream();
    }

    @TestFactory
    @Order(4)
    Stream<DynamicTest> twoBitCounterCyclesThroughAllFourBinaryStates() throws IOException {
        Schematic schematic = load("counter-2bit");
        ClockStepper stepper = new ClockStepper();
        SimulationResult result = SchematicCompiler.compileAndSettle(schematic);
        PlacedComponent reset = resetInput(schematic);
        List<DynamicTest> checks = new ArrayList<>();
        String initial = counterState(schematic, result);
        checks.add(DynamicTest.dynamicTest(
                "2-bit counter initial reset state: observed Q1Q0=" + initial,
                () -> assertEquals("00", initial, "initial reset state")));
        if (!"00".equals(initial)) {
            return checks.stream();
        }

        schematic.setInputValue(reset.id(), true);
        result = settle(schematic, result);
        result = fullCycle(stepper, schematic, result);
        String duringReset = counterState(schematic, result);
        checks.add(DynamicTest.dynamicTest(
                "2-bit counter during R=1 reset cycle: observed Q1Q0=" + duringReset,
                () -> assertEquals("00", duringReset,
                        "R=1 during reset must hold the counter at 00")));
        if (!"00".equals(duringReset)) {
            return checks.stream();
        }

        schematic.setInputValue(reset.id(), false);
        result = settle(schematic, result);
        addCounterCheck(checks, schematic, result, "00", "released reset state");

        for (String expected : new String[]{"01", "10", "11", "00"}) {
            result = fullCycle(stepper, schematic, result);
            String observed = counterState(schematic, result);
            checks.add(DynamicTest.dynamicTest(
                    "2-bit counter after full cycle: expected " + expected
                            + ", observed Q1Q0=" + observed,
                    () -> assertEquals(expected, observed,
                            "counter must advance once per full clock cycle")));
            if (!expected.equals(observed)) {
                break;
            }
        }
        return checks.stream();
    }

    private static void addCounterCheck(
            List<DynamicTest> checks, Schematic schematic, SimulationResult result,
            String expected, String stage) {
        String observed = counterState(schematic, result);
        checks.add(DynamicTest.dynamicTest(
                "2-bit counter " + stage + ": observed Q1Q0=" + observed,
                () -> assertEquals(expected, observed, stage)));
    }

    private static String counterState(Schematic schematic, SimulationResult result) {
        Value q1 = output(schematic, result, "Q1");
        Value q0 = output(schematic, result, "Q0");
        return String.valueOf(bit(q1)) + bit(q0);
    }

    private static char bit(Value value) {
        return value == Value.ONE ? '1' : value == Value.ZERO ? '0' : 'X';
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
}
