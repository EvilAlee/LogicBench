package io.github.evilalee.gatelab.synth;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionParserTest {
    @Test
    void precedenceOrdersNotAndThenXorThenOr() {
        Expression parsed = ExpressionParser.parse("A OR B XOR C AND NOT D");
        assertInstanceOf(Expression.Binary.class, parsed);
        Expression.Binary or = (Expression.Binary) parsed;
        assertEquals(Expression.BinaryOperator.OR, or.operator());
        Expression.Binary xor = assertInstanceOf(Expression.Binary.class, or.right());
        assertEquals(Expression.BinaryOperator.XOR, xor.operator());
        Expression.Binary and = assertInstanceOf(Expression.Binary.class, xor.right());
        assertEquals(Expression.BinaryOperator.AND, and.operator());

        Map<String, Boolean> assignment = Map.of(
                "A", false, "B", false, "C", true, "D", false);
        assertTrue(ExpressionEvaluator.evaluate(parsed, assignment));
    }

    @Test
    void parenthesesOverridePrecedenceAndPostfixPrimeBindsToItsOperand() {
        Expression parsed = ExpressionParser.parse("(A | B)' & C");
        Map<String, Boolean> assignment = Map.of("A", false, "B", false, "C", true);
        assertTrue(ExpressionEvaluator.evaluate(parsed, assignment));
        assertEquals(java.util.List.of("A", "B", "C"), parsed.variables());
    }

    @Test
    void symbolsAndCaseInsensitiveWordOperatorsAreAccepted() {
        String[] expressions = {
                "A & B", "A * B", "A AND B",
                "A ^ B", "A XOR B",
                "A | B", "A + B", "A OR B",
                "A NAND B", "A NOR B", "A XNOR B",
                "!A", "~a", "NOT A", "A'"
        };
        for (String source : expressions) {
            assertDoesNotThrow(() -> ExpressionParser.parse(source), source);
        }

        assertFalse(ExpressionEvaluator.evaluate(
                ExpressionParser.parse("A nAnD B"), Map.of("A", true, "B", true)));
        assertTrue(ExpressionEvaluator.evaluate(
                ExpressionParser.parse("a XnOr B"), Map.of("a", true, "B", true)));
    }

    @Test
    void chainsAreLeftAssociative() {
        Expression parsed = ExpressionParser.parse("A NAND B NAND C");
        Expression.Binary outer = assertInstanceOf(Expression.Binary.class, parsed);
        assertEquals(Expression.BinaryOperator.NAND, outer.operator());
        Expression.Binary inner = assertInstanceOf(Expression.Binary.class, outer.left());
        assertEquals(Expression.BinaryOperator.NAND, inner.operator());
        assertInstanceOf(Expression.Variable.class, outer.right());
    }

    @Test
    void constantsAndVariableOrderArePreservedInTheAst() {
        Expression parsed = ExpressionParser.parse("B & (A | B) ^ 1");
        assertEquals(java.util.List.of("B", "A"), parsed.variables());
        assertFalse(ExpressionEvaluator.evaluate(
                parsed, new LinkedHashMap<>(Map.of("A", false, "B", true))));
        assertFalse(ExpressionEvaluator.evaluate(
                ExpressionParser.parse("0 | 0"), Map.of()));
        assertTrue(ExpressionEvaluator.evaluate(
                ExpressionParser.parse("1 AND NOT 0"), Map.of()));
    }

    @Test
    void syntaxErrorsCarryZeroBasedCharacterPositionsAndShortMessages() {
        ParseException missingOperand = assertThrows(
                ParseException.class, () -> ExpressionParser.parse("A &"));
        assertEquals(3, missingOperand.position());
        assertTrue(missingOperand.detail().contains("expected"));

        ParseException unexpectedClose = assertThrows(
                ParseException.class, () -> ExpressionParser.parse("A & )"));
        assertEquals(4, unexpectedClose.position());

        ParseException badCharacter = assertThrows(
                ParseException.class, () -> ExpressionParser.parse("A @ B"));
        assertEquals(2, badCharacter.position());
        assertTrue(badCharacter.getMessage().contains("unexpected character"));
    }

    @Test
    void libraryCallsAndInstanceOutputReferencesHaveDistinctAstNodes() {
        Expression.ComponentCall call = assertInstanceOf(
                Expression.ComponentCall.class, ExpressionParser.parse("FA(A, B, CIN)"));
        assertEquals("FA", call.componentName());
        assertEquals(java.util.List.of("A", "B", "CIN"), call.arguments().stream()
                .flatMap(argument -> argument.variables().stream()).toList());

        Expression.ComponentOutput output = assertInstanceOf(
                Expression.ComponentOutput.class, ExpressionParser.parse("U0.COUT"));
        assertEquals("U0", output.instanceName());
        assertEquals("COUT", output.outputName());
        assertTrue(output.variables().isEmpty());
    }

    @Test
    void missingReferenceAssignmentIsReportedByThePureEvaluator() {
        assertThrows(IllegalArgumentException.class,
                () -> ExpressionEvaluator.evaluate(
                        ExpressionParser.parse("A & B"), Map.of("A", true)));
    }
}
