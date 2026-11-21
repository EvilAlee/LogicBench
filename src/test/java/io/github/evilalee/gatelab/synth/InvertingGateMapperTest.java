package io.github.evilalee.gatelab.synth;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InvertingGateMapperTest {
    @Test
    void foldsNotAroundAndOrXorAndTheirInvertingForms() {
        assertMapped("F = NOT (A & B)", GateOperator.NAND);
        assertMapped("F = NOT (A | B)", GateOperator.NOR);
        assertMapped("F = NOT (A ^ B)", GateOperator.XNOR);
        assertMapped("F = NOT (A NAND B)", GateOperator.AND);
        assertMapped("F = NOT (A NOR B)", GateOperator.OR);
        assertMapped("F = NOT (A XNOR B)", GateOperator.XOR);
    }

    @Test
    void doubleNotCollapsesToItsOriginalSignal() {
        Netlist source = new Netlist(java.util.List.of("A"),
                java.util.List.of(new Netlist.Output("F", "n2")),
                java.util.List.of(new Netlist.Gate("n1", GateOperator.NOT, java.util.List.of("A")),
                        new Netlist.Gate("n2", GateOperator.NOT, java.util.List.of("n1"))));
        Netlist mapped = InvertingGateMapper.optimize(source);
        assertEquals("A", mapped.outputs().getFirst().source());
        assertTrue(mapped.gates().isEmpty());
    }

    @Test
    void deMorganAndXorInverterPatternsAreFused() {
        assertMapped("F = (NOT A) & (NOT B)", GateOperator.NOR);
        assertMapped("F = (NOT A) | (NOT B)", GateOperator.NAND);
        assertMapped("F = (NOT A) ^ B", GateOperator.XNOR);
        assertMapped("F = A ^ (NOT B)", GateOperator.XNOR);
    }

    @Test
    void innerGateWithFanOutIsNotFused() {
        Netlist original = ExpressionCompiler.compile(
                "F = NOT (A & B)\nG = A & B", false).netlist();
        Netlist mapped = InvertingGateMapper.optimize(original);
        assertEquals(original.gates().size(), mapped.gates().size());
        assertTrue(mapped.gates().stream().anyMatch(g -> g.type() == GateOperator.AND));
        assertTrue(mapped.gates().stream().anyMatch(g -> g.type() == GateOperator.NOT));
    }

    @Test
    void twoHundredFixedSeedExpressionsRemainEquivalentAndNeverGainGates() {
        Random random = new Random(0x5EEDC0DEL);
        String[] names = {"A", "B", "C", "D"};
        String[] operators = {"&", "|", "^", "NAND", "NOR", "XNOR"};
        for (int index = 0; index < 200; index++) {
            String expression = randomExpression(random, names, operators, 1 + random.nextInt(4));
            Netlist original = ExpressionCompiler.compile("F = " + expression, false).netlist();
            Netlist mapped = InvertingGateMapper.optimize(original);
            assertTrue(mapped.gates().size() <= original.gates().size(),
                    "seeded expression " + index + ": " + expression);
            assertEquals(TruthTableGenerator.generate(original), TruthTableGenerator.generate(mapped),
                    "seeded expression " + index + ": " + expression);
        }
    }

    @Test
    void fullAdderStillMatchesItsArithmeticSpecification() {
        String source = "S = A ^ B ^ CIN\n"
                + "COUT = (A & B) | (A & CIN) | (B & CIN)";
        Netlist original = ExpressionCompiler.compile(source, false).netlist();
        Netlist mapped = ExpressionCompiler.compile(source, false, true).netlist();
        TruthTable table = TruthTableGenerator.generate(mapped);
        assertEquals(TruthTableGenerator.generate(original), table);
        assertEquals(8, table.rows().size());
        for (TruthTable.Row row : table.rows()) {
            int a = row.inputValue(table.inputNames().indexOf("A")) ? 1 : 0;
            int b = row.inputValue(table.inputNames().indexOf("B")) ? 1 : 0;
            int cin = row.inputValue(table.inputNames().indexOf("CIN")) ? 1 : 0;
            int sum = a + b + cin;
            assertEquals(sum & 1, bit(table, row.index(), "S"));
            assertEquals((sum >>> 1) & 1, bit(table, row.index(), "COUT"));
        }
    }

    @Test
    void disablingMappingPreservesTheExactOriginalNetlist() {
        String source = "F = NOT (A & B) | (NOT C ^ D)";
        assertEquals(ExpressionCompiler.compile(source, false).netlist(),
                ExpressionCompiler.compile(source, false, false).netlist());
    }

    private static int bit(TruthTable table, int row, String output) {
        return table.rows().get(row).outputValues().get(table.outputNames().indexOf(output))
                .name().equals("ONE") ? 1 : 0;
    }

    private static void assertMapped(String source, GateOperator expected) {
        Netlist original = ExpressionCompiler.compile(source, false).netlist();
        Netlist mapped = ExpressionCompiler.compile(source, false, true).netlist();
        assertEquals(1, mapped.gates().size(), source);
        assertEquals(expected, mapped.gates().getFirst().type(), source);
        assertTrue(mapped.gates().size() < original.gates().size(), source);
        assertEquals(TruthTableGenerator.generate(original), TruthTableGenerator.generate(mapped), source);
    }

    private static String randomExpression(
            Random random, String[] names, String[] operators, int depth) {
        if (depth <= 0 || random.nextInt(4) == 0) return names[random.nextInt(names.length)];
        if (random.nextInt(5) == 0) {
            return "NOT (" + randomExpression(random, names, operators, depth - 1) + ")";
        }
        return "(" + randomExpression(random, names, operators, depth - 1) + " "
                + operators[random.nextInt(operators.length)] + " "
                + randomExpression(random, names, operators, depth - 1) + ")";
    }
}
