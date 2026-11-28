package io.github.evilalee.gatelab.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GateTypeTest {
    @Test
    void everyBinaryGateHasTheExpectedThreeValuedTruthTable() {
        Value[][] rows = {
                {Value.ZERO, Value.ZERO},
                {Value.ZERO, Value.ONE},
                {Value.ZERO, Value.X},
                {Value.ONE, Value.ZERO},
                {Value.ONE, Value.ONE},
                {Value.ONE, Value.X},
                {Value.X, Value.ZERO},
                {Value.X, Value.ONE},
                {Value.X, Value.X}
        };

        Value[] and = {
                Value.ZERO, Value.ZERO, Value.ZERO,
                Value.ZERO, Value.ONE, Value.X,
                Value.ZERO, Value.X, Value.X
        };
        Value[] or = {
                Value.ZERO, Value.ONE, Value.X,
                Value.ONE, Value.ONE, Value.ONE,
                Value.X, Value.ONE, Value.X
        };
        Value[] xor = {
                Value.ZERO, Value.ONE, Value.X,
                Value.ONE, Value.ZERO, Value.X,
                Value.X, Value.X, Value.X
        };

        for (int row = 0; row < rows.length; row++) {
            assertEquals(and[row], GateType.AND.evaluate(rows[row]), "AND row " + row);
            assertEquals(or[row], GateType.OR.evaluate(rows[row]), "OR row " + row);
            assertEquals(and[row].inverted(), GateType.NAND.evaluate(rows[row]), "NAND row " + row);
            assertEquals(or[row].inverted(), GateType.NOR.evaluate(rows[row]), "NOR row " + row);
            assertEquals(xor[row], GateType.XOR.evaluate(rows[row]), "XOR row " + row);
            assertEquals(xor[row].inverted(), GateType.XNOR.evaluate(rows[row]), "XNOR row " + row);
        }
    }

    @Test
    void notAndBufferPreserveUnknown() {
        assertEquals(Value.ONE, GateType.NOT.evaluate(Value.ZERO));
        assertEquals(Value.ZERO, GateType.NOT.evaluate(Value.ONE));
        assertEquals(Value.X, GateType.NOT.evaluate(Value.X));

        assertEquals(Value.ZERO, GateType.BUFFER.evaluate(Value.ZERO));
        assertEquals(Value.ONE, GateType.BUFFER.evaluate(Value.ONE));
        assertEquals(Value.X, GateType.BUFFER.evaluate(Value.X));
    }

    @Test
    void variableInputGatesSupportThreeFourAndEightInputs() {
        for (int count : new int[]{3, 4, 5, 8}) {
            Value[] allOnes = new Value[count];
            Arrays.fill(allOnes, Value.ONE);
            assertEquals(Value.ONE, GateType.AND.evaluate(allOnes));
            assertEquals(Value.ONE, GateType.OR.evaluate(allOnes));
            assertEquals(count % 2 == 0 ? Value.ZERO : Value.ONE, GateType.XOR.evaluate(allOnes));
            assertEquals(count % 2 == 0 ? Value.ONE : Value.ZERO, GateType.XNOR.evaluate(allOnes));

            allOnes[0] = Value.X;
            assertEquals(Value.X, GateType.AND.evaluate(allOnes));
            assertEquals(Value.ONE, GateType.OR.evaluate(allOnes));
            assertEquals(Value.X, GateType.XOR.evaluate(allOnes));
            assertEquals(Value.X, GateType.XNOR.evaluate(allOnes));

            allOnes[0] = Value.ZERO;
            assertEquals(Value.ZERO, GateType.AND.evaluate(allOnes));
            assertEquals(Value.ONE, GateType.NAND.evaluate(allOnes));

            Value[] allZeros = new Value[count];
            Arrays.fill(allZeros, Value.ZERO);
            assertEquals(Value.ZERO, GateType.OR.evaluate(allZeros));
            allZeros[count - 1] = Value.ONE;
            assertEquals(Value.ONE, GateType.OR.evaluate(allZeros));
            assertEquals(Value.ZERO, GateType.NOR.evaluate(allZeros));
        }
    }

    @Test
    void everyThreeValuedTruthTableRowIsCorrectForThreeFourAndFiveInputs() {
        Value[] alphabet = {Value.ZERO, Value.ONE, Value.X};
        for (int count : new int[]{3, 4, 5}) {
            int rows = (int) Math.pow(alphabet.length, count);
            for (int row = 0; row < rows; row++) {
                int encoded = row;
                Value[] inputs = new Value[count];
                boolean parity = false;
                boolean hasZero = false;
                boolean hasOne = false;
                boolean hasUnknown = false;
                for (int index = 0; index < count; index++) {
                    Value value = alphabet[encoded % alphabet.length];
                    encoded /= alphabet.length;
                    inputs[index] = value;
                    hasZero |= value == Value.ZERO;
                    hasOne |= value == Value.ONE;
                    hasUnknown |= value == Value.X;
                    parity ^= value == Value.ONE;
                }
                Value and = hasZero ? Value.ZERO : hasUnknown ? Value.X : Value.ONE;
                Value or = hasOne ? Value.ONE : hasUnknown ? Value.X : Value.ZERO;
                Value xor = hasUnknown ? Value.X : parity ? Value.ONE : Value.ZERO;
                assertEquals(and, GateType.AND.evaluate(inputs), "AND count=" + count + " row=" + row);
                assertEquals(or, GateType.OR.evaluate(inputs), "OR count=" + count + " row=" + row);
                assertEquals(and.inverted(), GateType.NAND.evaluate(inputs),
                        "NAND count=" + count + " row=" + row);
                assertEquals(or.inverted(), GateType.NOR.evaluate(inputs),
                        "NOR count=" + count + " row=" + row);
                assertEquals(xor, GateType.XOR.evaluate(inputs),
                        "XOR count=" + count + " row=" + row);
                assertEquals(xor.inverted(), GateType.XNOR.evaluate(inputs),
                        "XNOR count=" + count + " row=" + row);
            }
        }
    }

    @Test
    void fiveInputUnknownAndControllingValuesFollowThreeValuedRules() {
        assertEquals(Value.ZERO,
                GateType.AND.evaluate(Value.ZERO, Value.X, Value.ONE, Value.ONE, Value.ONE));
        assertEquals(Value.ONE,
                GateType.OR.evaluate(Value.ONE, Value.X, Value.ZERO, Value.ZERO, Value.ZERO));
        assertEquals(Value.ONE,
                GateType.NAND.evaluate(Value.ZERO, Value.X, Value.ONE, Value.ONE, Value.ONE));
        assertEquals(Value.ZERO,
                GateType.NOR.evaluate(Value.ONE, Value.X, Value.ZERO, Value.ZERO, Value.ZERO));
        assertEquals(Value.X,
                GateType.XOR.evaluate(Value.ONE, Value.ZERO, Value.X, Value.ONE, Value.ZERO));
        assertEquals(Value.X,
                GateType.XNOR.evaluate(Value.ONE, Value.ZERO, Value.X, Value.ONE, Value.ZERO));
    }

    @Test
    void invalidInputCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Gate("bad", GateType.AND, 1));
        assertThrows(IllegalArgumentException.class, () -> new Gate("bad", GateType.NOT, 2));
        assertThrows(IllegalArgumentException.class, () -> GateType.XOR.evaluate(Value.ONE));
        assertThrows(IllegalArgumentException.class, () -> GateType.AND.evaluate(
                Value.ONE, Value.ONE, Value.ONE, Value.ONE, Value.ONE, Value.ONE));
        assertThrows(IllegalArgumentException.class, () -> GateType.BUFFER.evaluate(Value.ZERO, Value.ONE));
    }
}
