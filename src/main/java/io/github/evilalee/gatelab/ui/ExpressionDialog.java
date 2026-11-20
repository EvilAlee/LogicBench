package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.ParseException;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicExpressionVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Objects;

/** Live expression editor and engine-backed truth-table preview. */
public final class ExpressionDialog extends JDialog {
    private static final int TABLE_INPUT_LIMIT = 10;
    private static final String FEEDBACK_PREVIEW_NOTE =
            "Feedback circuit: connections verified, behaviour not simulated. Truth table not shown.";

    private final CanvasPanel canvas;
    private final JTextArea expressionText = new JTextArea(5, 58);
    private final JLabel statusLine = new JLabel(" ");
    private final JLabel tableNote = new JLabel(" ");
    private final TruthTableModel tableModel = new TruthTableModel();
    private final JTable truthTable = new JTable(tableModel);
    private final JButton generateButton = new JButton("Generate");
    private ExpressionCompiler.Compilation currentCompilation;
    private SchematicLayout.LayoutResult currentLayout;

    private ExpressionDialog(JFrame owner, CanvasPanel canvas) {
        super(owner, "From expression", ModalityType.APPLICATION_MODAL);
        setIconImages(AppIcons.load());
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JLabel hint = new JLabel(
                "One output per line or semicolon. Examples: F = A & B ^ C; Sum = A ^ B ^ Cin");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        add(hint, BorderLayout.NORTH);

        expressionText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        expressionText.setLineWrap(false);
        JScrollPane expressionScroll = new JScrollPane(expressionText);
        expressionScroll.setBorder(BorderFactory.createTitledBorder("Boolean expressions"));

        statusLine.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusLine.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel expressionPanel = new JPanel(new BorderLayout(0, 4));
        expressionPanel.add(expressionScroll, BorderLayout.CENTER);
        expressionPanel.add(statusLine, BorderLayout.SOUTH);

        truthTable.setFillsViewportHeight(true);
        truthTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        truthTable.setRowHeight(21);
        truthTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane tableScroll = new JScrollPane(truthTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Truth table"));
        tableNote.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        center.add(expressionPanel, BorderLayout.NORTH);
        center.add(tableScroll, BorderLayout.CENTER);
        center.add(tableNote, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(event -> dispose());
        generateButton.setEnabled(false);
        generateButton.addActionListener(event -> generate());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(cancelButton);
        buttons.add(generateButton);
        add(buttons, BorderLayout.SOUTH);

        expressionText.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updatePreview();
            }
        });

        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-expression");
        getRootPane().getActionMap().put("cancel-expression", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                dispose();
            }
        });

        setMinimumSize(new Dimension(760, 530));
        setSize(900, 650);
        setLocationRelativeTo(owner);
        updatePreview();
    }

    public static void showDialog(JFrame owner, CanvasPanel canvas) {
        ExpressionDialog dialog = new ExpressionDialog(owner, canvas);
        dialog.setVisible(true);
    }

    private void updatePreview() {
        currentCompilation = null;
        currentLayout = null;
        generateButton.setEnabled(false);
        tableModel.setTable(null);
        tableNote.setText(" ");
        truthTable.setEnabled(true);
        try {
            ExpressionCompiler.Compilation compilation =
                    ExpressionCompiler.compile(expressionText.getText());
            currentCompilation = compilation;
            List<String> variables = compilation.netlist().inputs();
            String reuseHint = outputReuseHint(compilation);
            if (reuseHint != null) {
                statusLine.setForeground(hintForeground());
                statusLine.setText(reuseHint);
            } else {
                statusLine.setForeground(labelForeground());
                statusLine.setText(variables.isEmpty()
                        ? "Variables: none"
                        : "Variables: " + String.join(", ", variables));
            }

            if (compilation.hasFeedback()) {
                SchematicLayout layoutEngine = new SchematicLayout(GateLibrary.load().all());
                currentLayout = layoutEngine.layoutFeedback(
                        compilation.feedbackExtraction().orElseThrow());
                SchematicCompiler.SimulationResult initial =
                        SchematicCompiler.compileAndSettle(currentLayout.schematic());
                if (initial.oscillating()) {
                    throw new IllegalStateException("Circuit did not settle");
                }
                truthTable.setEnabled(false);
                tableNote.setText(FEEDBACK_PREVIEW_NOTE);
            } else if (variables.size() > TABLE_INPUT_LIMIT) {
                tableNote.setText("Truth table preview is limited to 10 inputs; "
                        + variables.size() + " variables are present.");
            } else {
                TruthTableVerifier.VerificationResult preview =
                        TruthTableVerifier.verify(compilation);
                if (!preview.verified()) {
                    throw new IllegalStateException(
                            "The synthesis engine did not verify this expression: "
                                    + preview.mismatches());
                }
                tableModel.setTable(preview.truthTable());
                tableNote.setText(preview.truthTable().rows().size() + " rows");
            }
            generateButton.setEnabled(true);
        } catch (ParseException exception) {
            showError("Parse error at position " + exception.position()
                    + ": " + exception.detail());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
        }
    }

    private void showError(String message) {
        currentCompilation = null;
        currentLayout = null;
        generateButton.setEnabled(false);
        truthTable.setEnabled(true);
        statusLine.setForeground(new Color(190, 35, 35));
        statusLine.setText(message == null ? "Invalid expression" : message);
        tableNote.setText(" ");
    }

    private void generate() {
        if (currentCompilation == null) {
            return;
        }
        try {
            SchematicLayout.LayoutResult layout;
            SchematicExpressionVerifier.VerificationResult placed = null;
            boolean feedback = currentCompilation.hasFeedback();
            if (feedback) {
                layout = currentLayout;
                if (layout == null) {
                    SchematicLayout layoutEngine =
                            new SchematicLayout(GateLibrary.load().all());
                    layout = layoutEngine.layoutFeedback(
                            currentCompilation.feedbackExtraction().orElseThrow());
                }
            } else {
                // The preview core verifies the expression netlist; this second pass
                // verifies the actual placed geometry through SchematicCompiler.
                SchematicLayout layoutEngine =
                        new SchematicLayout(GateLibrary.load().all());
                layout = layoutEngine.layout(currentCompilation.netlist());
                placed = SchematicExpressionVerifier.verify(currentCompilation, layout);
                if (!placed.verified()) {
                    showError("Placed circuit verification failed: " + placed.mismatches());
                    return;
                }
            }

            boolean hasContent = !canvas.schematic().components().isEmpty()
                    || !canvas.schematic().wires().isEmpty();
            if (hasContent) {
                int answer = JOptionPane.showConfirmDialog(
                        this,
                        "Replace the current circuit with the generated expression circuit?",
                        "Replace circuit",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            canvas.replaceWithGeneratedCircuit(layout.schematic());
            canvas.setStatusMessage(feedback
                    ? FEEDBACK_PREVIEW_NOTE
                    : "Verified: " + placed.rowCount() + " rows match");
            dispose();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
        }
    }

    static String outputReuseHint(ExpressionCompiler.Compilation compilation) {
        Objects.requireNonNull(compilation, "compilation");
        if (compilation.reusedOutputNames().isEmpty()) {
            return null;
        }
        return compilation.reusedOutputNames().getFirst()
                + " is both an output and used by another line. "
                + "Use := for an internal signal with no pin.";
    }

    private static Color labelForeground() {
        Color color = UIManager.getColor("Label.foreground");
        return color == null ? Color.DARK_GRAY : color;
    }

    private static Color hintForeground() {
        Color color = UIManager.getColor("Actions.Yellow");
        if (color != null) {
            return color;
        }
        color = UIManager.getColor("Component.warning.focusColor");
        if (color != null) {
            return color;
        }
        Color background = UIManager.getColor("Panel.background");
        boolean darkBackground = background != null
                && (background.getRed() * 299 + background.getGreen() * 587
                        + background.getBlue() * 114) / 1000 < 128;
        return darkBackground ? new Color(255, 196, 92) : new Color(145, 88, 0);
    }

    private static final class TruthTableModel extends AbstractTableModel {
        private TruthTable table;

        private void setTable(TruthTable next) {
            table = next;
            fireTableStructureChanged();
        }

        @Override
        public int getRowCount() {
            return table == null ? 0 : table.rows().size();
        }

        @Override
        public int getColumnCount() {
            return table == null ? 0 : table.inputNames().size() + table.outputNames().size();
        }

        @Override
        public String getColumnName(int column) {
            if (table == null) {
                return "";
            }
            if (column < table.inputNames().size()) {
                return table.inputNames().get(column);
            }
            return table.outputNames().get(column - table.inputNames().size());
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (table == null) {
                return "";
            }
            TruthTable.Row row = table.rows().get(rowIndex);
            if (columnIndex < table.inputNames().size()) {
                return Character.toString(row.inputBits().charAt(columnIndex));
            }
            Value value = row.outputValues().get(columnIndex - table.inputNames().size());
            return switch (value) {
                case ZERO -> "0";
                case ONE -> "1";
                case X -> "X";
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
