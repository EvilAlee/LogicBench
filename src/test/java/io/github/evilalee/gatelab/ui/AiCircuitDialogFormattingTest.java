package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.synth.ExpressionParser;
import io.github.evilalee.gatelab.synth.NamedExpression;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiCircuitDialogFormattingTest {
    @Test
    void useTemplatesCheckboxDefaultsToSelected() {
        JCheckBox checkbox = AiCircuitDialog.createUseTemplatesCheckbox();
        assertTrue(checkbox.isSelected());
        assertEquals("Use templates", checkbox.getText());
    }

    @Test
    void displaysSignalsBeforeOutputsAndUsesInternalAssignmentSyntax() {
        List<NamedExpression> signals = List.of(
                new NamedExpression("C1", ExpressionParser.parse("A & B")));
        List<NamedExpression> outputs = List.of(
                new NamedExpression("S0", ExpressionParser.parse("A ^ C1")));

        List<String> lines = AiCircuitDialog.formattedExpressionLines(signals, outputs, 80);

        assertTrue(lines.getFirst().startsWith("C1 := "));
        assertTrue(lines.getLast().startsWith("S0 = "));
    }

    @Test
    void wrapsExpressionLinesAtTheRequestedWidth() {
        List<String> lines = AiCircuitDialog.wrapExpressionLines(
                "CARRY := A & B & C & D\nSUM = A ^ B ^ C ^ D", 12);

        assertTrue(lines.stream().allMatch(line -> line.length() <= 12));
        assertTrue(lines.stream().anyMatch(line -> line.contains("CARRY")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("SUM =")));
    }

    @Test
    void rejectsNonPositiveWrapWidths() {
        assertThrows(IllegalArgumentException.class,
                () -> AiCircuitDialog.wrapExpressionLines("A = B", 0));
    }
}
