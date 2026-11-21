package io.github.evilalee.gatelab.ai;

import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.TruthTable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiCircuitStructureTest {
    @Test
    void engineUnknownOutputFailsStructuralCheckE() {
        TruthTable table = new TruthTable(
                List.of("A"),
                List.of("F"),
                List.of(
                        new TruthTable.Row(0, "0", List.of(Value.X), false),
                        new TruthTable.Row(1, "1", List.of(Value.ONE), false)));

        List<String> errors = AiCircuitService.structuralErrors(table);

        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("Engine output F is X"));
    }

    @Test
    void engineOscillationFailsStructuralCheckE() {
        TruthTable table = new TruthTable(
                List.of("A"),
                List.of("F"),
                List.of(new TruthTable.Row(0, "0", List.of(Value.ZERO), true)));

        assertTrue(AiCircuitService.structuralErrors(table).getFirst()
                .contains("Engine oscillation"));
    }
}
