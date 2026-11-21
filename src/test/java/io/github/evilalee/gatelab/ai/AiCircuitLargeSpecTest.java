package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.synth.GateOperator;
import io.github.evilalee.gatelab.synth.Netlist;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitLargeSpecTest {
    private record Output(String name, String expression) { }

    private static AiCircuitService service(String response) {
        AiProvider provider = (prompt, conversation, schema) -> response;
        return new AiCircuitService(provider, GateLibrary.load().all());
    }

    private static JsonObject named(String name, String expression) {
        JsonObject value = new JsonObject();
        value.addProperty("name", name);
        value.addProperty("expression", expression);
        return value;
    }

    private static JsonObject response(
            List<String> inputs, List<Output> outputs,
            List<Output> signals, JsonObject spec) {
        JsonObject root = new JsonObject();
        root.addProperty("summary", "integration circuit");
        JsonArray inputArray = new JsonArray();
        inputs.forEach(inputArray::add);
        root.add("inputs", inputArray);
        JsonArray outputArray = new JsonArray();
        outputs.forEach(output -> outputArray.add(named(output.name(), output.expression())));
        root.add("outputs", outputArray);
        JsonArray signalArray = new JsonArray();
        signals.forEach(signal -> signalArray.add(named(signal.name(), signal.expression())));
        root.add("signals", signalArray);
        root.add("spec", spec);
        return root;
    }

    private static JsonObject spec(
            String inputBusA, List<String> inputPinsA,
            String inputBusB, List<String> inputPinsB,
            String outputBus, List<String> outputPins,
            String outputExpression, String carryExpression, String carryPin) {
        JsonObject root = new JsonObject();
        JsonObject buses = new JsonObject();
        JsonArray a = new JsonArray();
        inputPinsA.forEach(a::add);
        buses.add(inputBusA, a);
        JsonArray b = new JsonArray();
        inputPinsB.forEach(b::add);
        buses.add(inputBusB, b);
        JsonArray output = new JsonArray();
        outputPins.forEach(output::add);
        buses.add(outputBus, output);
        root.add("buses", buses);

        JsonArray rules = new JsonArray();
        JsonObject outputRule = new JsonObject();
        outputRule.addProperty("out", outputBus);
        outputRule.addProperty("expr", outputExpression);
        rules.add(outputRule);
        if (carryPin != null) {
            JsonObject carryRule = new JsonObject();
            carryRule.addProperty("out", carryPin);
            carryRule.addProperty("expr", carryExpression);
            rules.add(carryRule);
        }
        root.add("rules", rules);
        return root;
    }

    private static String fourBitAdder() {
        List<String> inputs = List.of(
                "A3", "A2", "A1", "A0", "B3", "B2", "B1", "B0", "CIN");
        List<Output> signals = List.of(
                new Output("C1", "(A0 & B0) | (A0 & CIN) | (B0 & CIN)"),
                new Output("C2", "(A1 & B1) | (A1 & C1) | (B1 & C1)"),
                new Output("C3", "(A2 & B2) | (A2 & C2) | (B2 & C2)"),
                new Output("C4", "(A3 & B3) | (A3 & C3) | (B3 & C3)"));
        List<Output> outputs = List.of(
                new Output("S0", "A0 ^ B0 ^ CIN"),
                new Output("S1", "A1 ^ B1 ^ C1"),
                new Output("S2", "A2 ^ B2 ^ C2"),
                new Output("S3", "A3 ^ B3 ^ C3"),
                new Output("COUT", "C4"));
        JsonObject spec = spec("A", List.of("A3", "A2", "A1", "A0"),
                "B", List.of("B3", "B2", "B1", "B0"),
                "S", List.of("S3", "S2", "S1", "S0"),
                "A + B + CIN", "(A + B + CIN) >> 4", "COUT");
        return response(inputs, outputs, signals, spec).toString();
    }

    private static String fourByFourMultiplier() {
        List<String> inputs = List.of(
                "A3", "A2", "A1", "A0", "B3", "B2", "B1", "B0");
        List<Output> signals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            signals.add(new Output("p" + i, "A" + i + " & B0"));
            signals.add(new Output("q" + i, "A" + i + " & B1"));
        }

        signals.add(new Output("r0", "p0"));
        signals.add(new Output("r1", "p1 ^ q0"));
        signals.add(new Output("c2", "p1 & q0"));
        signals.add(new Output("r2", "p2 ^ q1 ^ c2"));
        signals.add(new Output("c3", "(p2 & q1) | (p2 & c2) | (q1 & c2)"));
        signals.add(new Output("r3", "p3 ^ q2 ^ c3"));
        signals.add(new Output("c4", "(p3 & q2) | (p3 & c3) | (q2 & c3)"));
        signals.add(new Output("r4", "q3 ^ c4"));
        signals.add(new Output("r5", "q3 & c4"));

        for (int i = 0; i < 4; i++) {
            signals.add(new Output("t" + i, "A" + i + " & B2"));
            signals.add(new Output("u" + i, "A" + i + " & B3"));
        }

        signals.add(new Output("s0", "r0"));
        signals.add(new Output("s1", "r1"));
        signals.add(new Output("s2", "r2 ^ t0"));
        signals.add(new Output("d3", "r2 & t0"));
        signals.add(new Output("s3", "r3 ^ t1 ^ d3"));
        signals.add(new Output("d4", "(r3 & t1) | (r3 & d3) | (t1 & d3)"));
        signals.add(new Output("s4", "r4 ^ t2 ^ d4"));
        signals.add(new Output("d5", "(r4 & t2) | (r4 & d4) | (t2 & d4)"));
        signals.add(new Output("s5", "r5 ^ t3 ^ d5"));
        signals.add(new Output("d6", "(r5 & t3) | (r5 & d5) | (t3 & d5)"));
        signals.add(new Output("s6", "d6"));

        signals.add(new Output("v0", "s0"));
        signals.add(new Output("v1", "s1"));
        signals.add(new Output("v2", "s2"));
        signals.add(new Output("v3", "s3 ^ u0"));
        signals.add(new Output("e4", "s3 & u0"));
        signals.add(new Output("v4", "s4 ^ u1 ^ e4"));
        signals.add(new Output("e5", "(s4 & u1) | (s4 & e4) | (u1 & e4)"));
        signals.add(new Output("v5", "s5 ^ u2 ^ e5"));
        signals.add(new Output("e6", "(s5 & u2) | (s5 & e5) | (u2 & e5)"));
        signals.add(new Output("v6", "s6 ^ u3 ^ e6"));
        signals.add(new Output("e7", "(s6 & u3) | (s6 & e6) | (u3 & e6)"));
        signals.add(new Output("v7", "e7"));

        List<Output> outputs = new ArrayList<>();
        for (int i = 7; i >= 0; i--) outputs.add(new Output("P" + i, "v" + i));
        JsonObject spec = spec("A", List.of("A3", "A2", "A1", "A0"),
                "B", List.of("B3", "B2", "B1", "B0"),
                "P", List.of("P7", "P6", "P5", "P4", "P3", "P2", "P1", "P0"),
                "A * B", null, null);
        return response(inputs, outputs, signals, spec).toString();
    }

    @Test
    void nineInputFourBitAdderAndFourByFourMultiplierBuildAndVerifyUnderTenSeconds()
            throws Exception {
        long started = System.nanoTime();

        AiCircuitService.Result adder = service(fourBitAdder()).generate("Design a four-bit adder with carry-in.");
        assertEquals(AiCircuitService.Status.VERIFIED, adder.status());
        assertNotNull(adder.layout());
        assertEquals(9, adder.truthTable().inputNames().size());
        assertEquals(512, adder.truthTable().rows().size());
        assertEquals(5, adder.truthTable().outputNames().size());

        AiCircuitService.Result multiplier =
                service(fourByFourMultiplier()).generate("Design a 4 by 4 binary multiplier.");
        assertEquals(AiCircuitService.Status.VERIFIED, multiplier.status());
        assertNotNull(multiplier.layout());
        assertEquals(8, multiplier.truthTable().inputNames().size());
        assertEquals(256, multiplier.truthTable().rows().size());
        assertEquals(8, multiplier.truthTable().outputNames().size());

        long elapsed = System.nanoTime() - started;
        System.out.printf("Large-circuit build, layout and spec verification: %.1f ms%n",
                elapsed / 1_000_000.0);
        assertTrue(elapsed < 10_000_000_000L,
                "4-bit adder and 4x4 multiplier should finish under 10 seconds");
    }
    /** Measures a deliberately deep max-limit chain to expose worst-case column depth. */
    @Test
    void measuresLayoutForTheMaximumTenInputSixteenOutputFourHundredGateNetlist() {
        List<String> inputs = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        GateOperator[] operators = {
                GateOperator.AND, GateOperator.OR, GateOperator.NAND, GateOperator.NOR,
                GateOperator.XOR, GateOperator.XNOR, GateOperator.NOT, GateOperator.BUFFER
        };
        List<Netlist.Gate> gates = new ArrayList<>();
        String previous = "A";
        for (int index = 0; index < AiCircuitLimits.MAX_GATES; index++) {
            GateOperator operator = operators[index % operators.length];
            List<String> gateInputs = operator.inputCount() == 1
                    ? List.of(previous)
                    : List.of(previous, inputs.get(1 + index % (inputs.size() - 1)));
            String id = "g" + index;
            gates.add(new Netlist.Gate(id, operator, gateInputs));
            previous = id;
        }
        List<Netlist.Output> outputs = new ArrayList<>();
        for (int index = 0; index < AiCircuitLimits.MAX_OUTPUTS; index++) {
            outputs.add(new Netlist.Output("O" + index,
                    "g" + (AiCircuitLimits.MAX_GATES - AiCircuitLimits.MAX_OUTPUTS + index)));
        }
        Netlist largestAllowed = new Netlist(inputs, outputs, gates);
        assertEquals(10, largestAllowed.inputs().size());
        assertEquals(16, largestAllowed.outputs().size());
        assertEquals(400, largestAllowed.gates().size());

        long started = System.nanoTime();
        SchematicLayout.LayoutResult placed =
                new SchematicLayout(GateLibrary.load().all()).layout(largestAllowed);
        long elapsed = System.nanoTime() - started;
        assertNotNull(placed);
        System.out.printf("Maximum-limit 10-input/16-output/400-gate layout: %.1f ms%n",
                elapsed / 1_000_000.0);
    }

}
