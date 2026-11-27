package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.ComponentExpressionCompiler;
import io.github.evilalee.gatelab.synth.ExpressionCompiler;
import io.github.evilalee.gatelab.synth.ParseException;
import io.github.evilalee.gatelab.synth.TruthTable;
import io.github.evilalee.gatelab.synth.TruthTableVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicExpressionVerifier;
import io.github.evilalee.gatelab.synth.layout.SchematicLayout;
import io.github.evilalee.gatelab.util.PerfLog;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.SwingWorker;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Live expression editor and engine-backed truth-table preview. */
public final class ExpressionDialog extends JDialog {
    private static final int TABLE_INPUT_LIMIT = 10;
    private static final Pattern LIBRARY_CALL_PATTERN = Pattern.compile(
            "(?i)\\b(?!NOT\\b|AND\\b|NAND\\b|XOR\\b|XNOR\\b|OR\\b|NOR\\b)"
                    + "[A-Za-z][A-Za-z0-9_]*\\s*\\(");
    private static final String FEEDBACK_PREVIEW_NOTE =
            "Feedback circuit: connections verified, behaviour not simulated. Truth table not shown.";
    static final String SEQUENTIAL_STATUS =
            "Sequential circuit: use Tick Once; start with RESET_N = 0 for one cycle.";

    private final CanvasPanel canvas;
    private final JTextArea expressionText = new JTextArea(5, 58);
    private final JLabel statusLine = new JLabel(" ");
    private final JLabel tableNote = new JLabel(" ");
    private final TruthTableModel tableModel = new TruthTableModel();
    private final JTable truthTable = new JTable(tableModel);
    private JScrollPane tableScroll;
    private final JButton generateButton = new JButton("Generate");
    private final PreviewScheduler previewScheduler =
            new PreviewScheduler(300, this::startPreview);
    private SwingWorker<PreviewResult, Void> previewWorker;
    private final JCheckBox optimizeInvertingGates =
            new JCheckBox("Optimize with NAND/NOR/XNOR", false);
    private final JCheckBox expandSequentialParts =
            new JCheckBox("Expand sequential calls as gates (legacy)", false);
    private ComponentExpressionCompiler.Compilation currentCompilation;

    private ExpressionDialog(JFrame owner, CanvasPanel canvas, String initialText) {
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
        expressionText.setText(Objects.requireNonNull(initialText, "initialText"));
        expressionText.setLineWrap(false);
        JScrollPane expressionScroll = new JScrollPane(expressionText);
        expressionScroll.setBorder(BorderFactory.createTitledBorder("Boolean expressions"));

        statusLine.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusLine.setHorizontalAlignment(SwingConstants.LEFT);
        JPanel expressionPanel = new JPanel(new BorderLayout(0, 4));
        expressionPanel.add(expressionScroll, BorderLayout.CENTER);
        JPanel expressionControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        expressionControls.add(optimizeInvertingGates);
        expressionControls.add(expandSequentialParts);
        expressionControls.add(statusLine);
        expressionPanel.add(expressionControls, BorderLayout.SOUTH);
        optimizeInvertingGates.addActionListener(event -> schedulePreview());
        expandSequentialParts.addActionListener(event -> schedulePreview());

        truthTable.setFillsViewportHeight(true);
        truthTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        truthTable.setRowHeight(21);
        truthTable.getTableHeader().setReorderingAllowed(false);
        tableScroll = new JScrollPane(truthTable);
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
                schedulePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                schedulePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                schedulePreview();
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
        schedulePreview();
    }

    public static void showDialog(JFrame owner, CanvasPanel canvas) {
        showDialog(owner, canvas, "");
    }

    /** Opens the expression editor with an existing circuit description prefilled. */
    public static void showDialog(JFrame owner, CanvasPanel canvas, String initialText) {
        ExpressionDialog dialog = new ExpressionDialog(owner, canvas, initialText);
        dialog.setVisible(true);
    }

    private void schedulePreview() {
        previewScheduler.schedule();
        currentCompilation = null;
        generateButton.setEnabled(false);
        tableModel.setTable(null);
        tableNote.setText("...");
        statusLine.setForeground(labelForeground());
        statusLine.setText("...");
        truthTable.setEnabled(true);
        tableScroll.setVisible(true);

        boolean containsLibraryCall =
                containsLibraryComponentCall(expressionText.getText());
        optimizeInvertingGates.setEnabled(!containsLibraryCall);
        if (previewWorker != null && !previewWorker.isDone()) {
            previewWorker.cancel(true);
        }
    }

    private void startPreview() {
        long generation = previewScheduler.generation();
        String source = expressionText.getText();
        boolean useLegacyCompiler = expandSequentialParts.isSelected()
                || !containsLibraryComponentCall(source);
        boolean optimize = optimizeInvertingGates.isSelected();
        ComponentExpressionCompiler.CompileOption option = useLegacyCompiler
                ? ComponentExpressionCompiler.CompileOption.LEGACY_SEQUENTIAL_GATE_EXPANSION
                : ComponentExpressionCompiler.CompileOption.BOXED_INSTANCES;

        SwingWorker<PreviewResult, Void> worker = new SwingWorker<>() {
            @Override
            protected PreviewResult doInBackground() {
                long started = PerfLog.start();
                try {
                    return computePreview(source, option, optimize);
                } finally {
                    PerfLog.record("expression.recompute.total", started);
                }
            }

            @Override
            protected void done() {
                if (!previewScheduler.isCurrent(generation) || isCancelled()) {
                    return;
                }
                try {
                    applyPreview(get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showError("Preview interrupted");
                } catch (java.util.concurrent.ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showError(cause == null ? exception.getMessage() : cause.getMessage());
                }
            }
        };
        previewWorker = worker;
        worker.execute();
    }

    private static PreviewResult computePreview(
            String source,
            ComponentExpressionCompiler.CompileOption option,
            boolean optimize) {
        try {
            ComponentExpressionCompiler.Compilation compilation =
                    ComponentExpressionCompiler.compile(
                            source,
                            GateLibrary.load().all(),
                            ComponentLibrary.load().all(),
                            option,
                            true,
                            optimize);

            if (compilation.hasSequentialInstances()) {
                SchematicCompiler.SimulationResult initial =
                        SchematicCompiler.compileAndSettle(compilation.schematic());
                if (initial.oscillating()) {
                    throw new IllegalStateException("Circuit did not settle");
                }
                return new PreviewResult(compilation, null, true, false,
                        List.of(), null);
            }

            if (compilation.hasFeedback()) {
                SchematicCompiler.SimulationResult initial =
                        SchematicCompiler.compileAndSettle(compilation.schematic());
                if (initial.oscillating()) {
                    throw new IllegalStateException("Circuit did not settle");
                }
                return new PreviewResult(compilation, null, false, true,
                        List.of(), null);
            }

            List<String> inputs = compilation.inputComponentIds().keySet().stream()
                    .sorted().toList();
            List<String> outputs = compilation.outputComponentIds().keySet().stream()
                    .sorted().toList();
            if (inputs.size() > TABLE_INPUT_LIMIT) {
                return new PreviewResult(compilation, null, false, false,
                        inputs, null);
            }

            long tableStarted = PerfLog.start();
            TruthTable preview = buildEnginePreview(compilation, inputs, outputs);
            PerfLog.record("expression.truthTable", tableStarted);
            return new PreviewResult(compilation, preview, false, false, inputs, null);
        } catch (ParseException exception) {
            return new PreviewResult(null, null, false, false, List.of(),
                    "Parse error at position " + exception.position()
                            + ": " + exception.detail());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return new PreviewResult(null, null, false, false, List.of(),
                    exception.getMessage() == null
                            ? "Invalid expression" : exception.getMessage());
        }
    }

    private void applyPreview(PreviewResult result) {
        if (result.error() != null) {
            showError(result.error());
            return;
        }

        currentCompilation = result.compilation();
        if (result.sequential()) {
            statusLine.setForeground(labelForeground());
            statusLine.setText(SEQUENTIAL_STATUS);
            tableScroll.setVisible(false);
            tableNote.setText(" ");
        } else if (result.feedback()) {
            statusLine.setForeground(labelForeground());
            statusLine.setText("Feedback circuit");
            tableScroll.setVisible(false);
            tableNote.setText(FEEDBACK_PREVIEW_NOTE);
            truthTable.setEnabled(false);
        } else {
            statusLine.setForeground(labelForeground());
            statusLine.setText(result.inputs().isEmpty()
                    ? "Variables: none"
                    : "Variables: " + String.join(", ", result.inputs()));

            if (result.inputs().size() > TABLE_INPUT_LIMIT) {
                tableNote.setText("Truth table preview is limited to 10 inputs; "
                        + result.inputs().size() + " variables are present.");
                tableModel.setTable(null);
            } else {
                tableModel.setTable(result.table());
                tableNote.setText(result.table().rows().size() + " rows (engine preview)");
            }
        }
        generateButton.setEnabled(true);
    }

    private record PreviewResult(
            ComponentExpressionCompiler.Compilation compilation,
            TruthTable table,
            boolean sequential,
            boolean feedback,
            List<String> inputs,
            String error) {
        private PreviewResult {
            inputs = List.copyOf(inputs);
        }
    }

    @Override
    public void dispose() {
        previewScheduler.invalidate();
        if (previewWorker != null) {
            previewWorker.cancel(true);
        }
        super.dispose();
    }

    static TruthTable buildEnginePreview(
            ComponentExpressionCompiler.Compilation compilation,
            List<String> inputNames,
            List<String> outputNames) {
        Schematic schematic = compilation.schematic();
        List<TruthTable.Row> rows = new ArrayList<>();
        int rowCount = 1 << inputNames.size();
        for (int row = 0; row < rowCount; row++) {
            StringBuilder bits = new StringBuilder(inputNames.size());
            for (int index = 0; index < inputNames.size(); index++) {
                boolean high = ((row >>> (inputNames.size() - index - 1)) & 1) != 0;
                bits.append(high ? '1' : '0');
                schematic.setInputValue(
                        compilation.inputComponentIds().get(inputNames.get(index)), high);
            }
            SchematicCompiler.SimulationResult result =
                    SchematicCompiler.compileAndSettle(schematic);
            List<Value> values = new ArrayList<>(outputNames.size());
            for (String output : outputNames) {
                Value value = result.outputValues().get(
                        compilation.outputComponentIds().get(output));
                values.add(value == null ? Value.X : value);
            }
            rows.add(new TruthTable.Row(row, bits.toString(), values, result.oscillating()));
        }
        for (String input : inputNames) {
            schematic.setInputValue(compilation.inputComponentIds().get(input), false);
        }
        return new TruthTable(inputNames, outputNames, rows);
    }

    private void showError(String message) {
        currentCompilation = null;
        generateButton.setEnabled(false);
        truthTable.setEnabled(true);
        tableScroll.setVisible(true);
        statusLine.setForeground(new Color(190, 35, 35));
        statusLine.setText(message == null ? "Invalid expression" : message);
        tableNote.setText(" ");
        tableModel.setTable(null);
    }

    private void generate() {
        if (currentCompilation == null) {
            return;
        }
        try {
            boolean sequential = currentCompilation.hasSequentialInstances();
            boolean feedback = currentCompilation.hasFeedback();
            Schematic generated = currentCompilation.schematic();

            boolean hasContent = !canvas.schematic().components().isEmpty()
                    || !canvas.schematic().componentInstances().isEmpty()
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

            if (sequential) {
                canvas.replaceWithSequentialCircuit(generated);
                canvas.setStatusMessage(SEQUENTIAL_STATUS);
            } else {
                canvas.replaceWithGeneratedCircuit(generated);
                canvas.setStatusMessage(feedback
                        ? FEEDBACK_PREVIEW_NOTE
                        : currentCompilation.legacyCompilation().isPresent()
                                ? "Generated using the gate-level expression compiler"
                                : "Generated with boxed component calls");
            }
            dispose();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
        }
    }

    static boolean containsLibraryComponentCall(String source) {
        return LIBRARY_CALL_PATTERN.matcher(Objects.requireNonNull(source, "source")).find();
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
