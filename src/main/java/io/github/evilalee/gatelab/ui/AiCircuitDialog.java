package io.github.evilalee.gatelab.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.evilalee.gatelab.ai.AiCache;
import io.github.evilalee.gatelab.ai.AiCircuitService;
import io.github.evilalee.gatelab.ai.AiConfig;
import io.github.evilalee.gatelab.ai.GeminiProvider;
import io.github.evilalee.gatelab.ai.Turn;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.Expression;
import io.github.evilalee.gatelab.synth.NamedExpression;
import io.github.evilalee.gatelab.synth.TruthTable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Swing-only dialog for plain-language AI circuit generation. */
public final class AiCircuitDialog extends JDialog {
    private final CanvasPanel canvas;
    private final AiConfig config;
    private final AiCache cache;
    private final AiCircuitService service;
    private final boolean cacheOnly;

    private final JTextArea description = new JTextArea(5, 64);
    private final JTextArea logArea = new JTextArea(7, 64);
    private final JTextArea expressionsArea = new JTextArea(6, 64);
    private final JLabel keyStatus = new JLabel();
    private final JLabel setupHelp = new JLabel(" ");
    private final JLabel summary = new JLabel(" ");
    private final JLabel statusBadge = new JLabel("READY", SwingConstants.CENTER);
    private final TruthTableModel tableModel = new TruthTableModel();
    private final JTable truthTable = new JTable(tableModel);
    private final JButton generateButton = new JButton("Generate");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton placeButton = new JButton("Place circuit");
    private final JButton testConnectionButton = new JButton("Test connection");

    private SwingWorker<?, ?> activeWorker;
    private AiCircuitService.Result currentResult;

    private AiCircuitDialog(JFrame owner, CanvasPanel canvas, boolean cacheOnly) {
        super(owner, "From description (AI)", ModalityType.APPLICATION_MODAL);
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.cacheOnly = cacheOnly;
        this.config = AiConfig.load();
        this.cache = new AiCache(config.configDirectory());
        this.service = new AiCircuitService(
                new GeminiProvider(config), io.github.evilalee.gatelab.io.GateLibrary.load().all(), cache);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JLabel hint = new JLabel("Describe the circuit behavior, inputs and requested outputs.");
        top.add(hint, BorderLayout.NORTH);
        description.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidateResult(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidateResult(); }
            @Override public void changedUpdate(DocumentEvent event) { invalidateResult(); }
        });
        JScrollPane descriptionScroll = new JScrollPane(description);
        descriptionScroll.setBorder(BorderFactory.createTitledBorder("Circuit description"));
        top.add(descriptionScroll, BorderLayout.CENTER);

        JPanel keyPanel = new JPanel(new BorderLayout(8, 2));
        keyStatus.setText(keyStatusText());
        setupHelp.setText(setupHelpText());
        setupHelp.setForeground(new Color(145, 88, 0));
        keyPanel.add(keyStatus, BorderLayout.NORTH);
        keyPanel.add(setupHelp, BorderLayout.CENTER);
        top.add(keyPanel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Generation log"));

        expressionsArea.setEditable(false);
        expressionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        expressionsArea.setLineWrap(false);
        JScrollPane expressionScroll = new JScrollPane(expressionsArea);
        expressionScroll.setBorder(BorderFactory.createTitledBorder("Expressions"));

        truthTable.setFillsViewportHeight(true);
        truthTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        truthTable.setRowHeight(21);
        truthTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane tableScroll = new JScrollPane(truthTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Engine truth table"));

        JPanel resultPanel = new JPanel(new BorderLayout(4, 4));
        JPanel resultHeader = new JPanel(new BorderLayout(8, 0));
        statusBadge.setOpaque(true);
        statusBadge.setBackground(new Color(235, 235, 235));
        statusBadge.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        resultHeader.add(statusBadge, BorderLayout.WEST);
        resultHeader.add(summary, BorderLayout.CENTER);
        resultPanel.add(resultHeader, BorderLayout.NORTH);
        JSplitPane detailSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, expressionScroll, tableScroll);
        detailSplit.setResizeWeight(0.35);
        resultPanel.add(detailSplit, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, logScroll, resultPanel);
        mainSplit.setResizeWeight(0.25);
        mainSplit.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        add(mainSplit, BorderLayout.CENTER);

        placeButton.setEnabled(false);
        cancelButton.addActionListener(event -> cancelOrClose());
        generateButton.addActionListener(event -> generate());
        placeButton.addActionListener(event -> place());
        testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
        testConnectionButton.addActionListener(event -> testConnection());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(testConnectionButton);
        buttons.add(placeButton);
        buttons.add(cancelButton);
        buttons.add(generateButton);
        add(buttons, BorderLayout.SOUTH);

        if (cacheOnly) {
            appendLog("AI cache-only mode is enabled; network calls are disabled.");
        }
        if (!config.hasApiKey()) {
            appendLog("No Gemini API key configured. Bundled and user caches remain available.");
        }
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                cancelWorker();
            }
        });

        setMinimumSize(new Dimension(820, 680));
        setSize(1000, 790);
        setLocationRelativeTo(owner);
    }

    public static void showDialog(JFrame owner, CanvasPanel canvas, boolean cacheOnly) {
        AiCircuitDialog dialog = new AiCircuitDialog(owner, canvas, cacheOnly);
        dialog.setVisible(true);
    }

    private String keyStatusText() {
        return switch (config.keySource()) {
            case ENV -> "Gemini key found (source: env)";
            case FILE -> "Gemini key found (source: file)";
            case NONE -> "No Gemini API key configured";
        };
    }

    private String setupHelpText() {
        if (config.hasApiKey()) return cacheOnly ? "Offline cache mode." : " ";
        return "Add {\"apiKey\":\"YOUR_KEY\",\"model\":\"gemini-3.8-flash\"} to "
                + config.configFile() + " or set GEMINI_API_KEY.";
    }

    private void generate() {
        String prompt = description.getText().trim();
        if (prompt.isEmpty() || activeWorker != null) return;
        currentResult = null;
        placeButton.setEnabled(false);
        generateButton.setEnabled(false);
        testConnectionButton.setEnabled(false);
        description.setEditable(false);
        statusBadge.setText("WORKING");
        summary.setText("Generating and verifying...");
        appendLog("Starting generation.");
        activeWorker = new SwingWorker<AiCircuitService.Result, Void>() {
            @Override
            protected AiCircuitService.Result doInBackground() throws Exception {
                return service.generate(prompt, cacheOnly);
            }

            @Override
            protected void done() {
                activeWorker = null;
                generateButton.setEnabled(true);
                description.setEditable(true);
                testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
                try {
                    currentResult = get();
                    for (String line : currentResult.attemptLog()) appendLog(line);
                    showResult(currentResult);
                } catch (CancellationException exception) {
                    appendLog("Generation cancelled.");
                    statusBadge.setText("CANCELLED");
                    summary.setText("Generation was cancelled.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFailure("Generation interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showFailure(cause == null ? "Generation failed." : safeMessage(cause));
                }
            }
        };
        activeWorker.execute();
    }

    private void invalidateResult() {
        if (activeWorker != null) return;
        currentResult = null;
        placeButton.setEnabled(false);
        expressionsArea.setText("");
        tableModel.setTable(null);
        summary.setText(" ");
        statusBadge.setText("READY");
        statusBadge.setBackground(new Color(235, 235, 235));
    }

    private void showResult(AiCircuitService.Result result) {
        summary.setText(result.summary());
        expressionsArea.setText(formatExpressions(result.expressions()));
        tableModel.setTable(result.truthTable());
        if (result.fromCache()) {
            statusBadge.setText("FROM CACHE");
            statusBadge.setBackground(new Color(220, 235, 250));
        } else if (result.status() == AiCircuitService.Status.VERIFIED) {
            statusBadge.setText("VERIFIED");
            statusBadge.setBackground(new Color(215, 240, 220));
        } else if (result.status() == AiCircuitService.Status.UNAVAILABLE) {
            statusBadge.setText("UNAVAILABLE");
            statusBadge.setBackground(new Color(250, 235, 195));
        } else {
            statusBadge.setText("UNVERIFIED");
            statusBadge.setBackground(new Color(250, 225, 205));
        }
        boolean placeable = result.layout() != null
                && (result.status() == AiCircuitService.Status.VERIFIED
                    || !result.mismatchingRows().isEmpty());
        placeButton.setEnabled(placeable);
        if (result.status() == AiCircuitService.Status.UNVERIFIED) {
            appendLog("UNVERIFIED: the engine did not confirm the model's claim.");
        }
    }

    private static String formatExpressions(List<NamedExpression> expressions) {
        StringBuilder text = new StringBuilder();
        for (NamedExpression expression : expressions) {
            if (!text.isEmpty()) text.append('\n');
            text.append(expression.name()).append(" = ")
                    .append(formatExpression(expression.expression()));
        }
        return text.toString();
    }

    private static String formatExpression(Expression expression) {
        return switch (expression) {
            case Expression.Constant constant -> constant.value() ? "1" : "0";
            case Expression.Variable variable -> variable.name();
            case Expression.Unary unary ->
                    "NOT (" + formatExpression(unary.operand()) + ")";
            case Expression.Binary binary -> {
                String operator = switch (binary.operator()) {
                    case AND -> "&";
                    case NAND -> "NAND";
                    case XOR -> "^";
                    case XNOR -> "XNOR";
                    case OR -> "|";
                    case NOR -> "NOR";
                };
                yield "(" + formatExpression(binary.left()) + " " + operator + " "
                        + formatExpression(binary.right()) + ")";
            }
        };
    }

    private void place() {
        if (currentResult == null || currentResult.layout() == null) return;
        if (currentResult.status() == AiCircuitService.Status.UNVERIFIED) {
            String rows = String.join("\n", currentResult.mismatchingRows());
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "This circuit is UNVERIFIED. The engine disagreed on these rows:\n\n"
                            + rows + "\n\nPlace it anyway?",
                    "Place unverified circuit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }

        boolean hasContent = !canvas.schematic().components().isEmpty()
                || !canvas.schematic().wires().isEmpty();
        if (hasContent) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "Replace the current circuit with the generated circuit?",
                    "Replace circuit",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
        }

        canvas.replaceWithGeneratedCircuit(currentResult.layout().schematic());
        canvas.setStatusMessage(currentResult.status() == AiCircuitService.Status.VERIFIED
                ? "AI circuit verified and placed."
                : "Unverified AI circuit placed after confirmation.");
        dispose();
    }

    private void testConnection() {
        if (!config.hasApiKey() || activeWorker != null || cacheOnly) return;
        testConnectionButton.setEnabled(false);
        generateButton.setEnabled(false);
        appendLog("Testing Gemini connection...");
        activeWorker = new SwingWorker<String, Void>() {
            private long elapsedMillis;

            @Override
            protected String doInBackground() throws Exception {
                long start = System.nanoTime();
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "OBJECT");
                JsonObject properties = new JsonObject();
                JsonObject ok = new JsonObject();
                ok.addProperty("type", "BOOLEAN");
                properties.add("ok", ok);
                schema.add("properties", properties);
                JsonArray required = new JsonArray();
                required.add("ok");
                schema.add("required", required);
                String response = new GeminiProvider(config).complete(
                        "Return a tiny JSON object with ok set to true.",
                        List.of(new Turn(Turn.Role.USER, "Connection test. Return {\"ok\":true}.")),
                        schema);
                elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                return response;
            }

            @Override
            protected void done() {
                activeWorker = null;
                generateButton.setEnabled(true);
                testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
                try {
                    get();
                    appendLog("Connection OK (" + elapsedMillis + " ms).");
                    JOptionPane.showMessageDialog(AiCircuitDialog.this,
                            "Gemini connection OK (" + elapsedMillis + " ms).",
                            "Test connection", JOptionPane.INFORMATION_MESSAGE);
                } catch (CancellationException exception) {
                    appendLog("Connection test cancelled.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFailure("Connection test interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showFailure(cause == null ? "Connection test failed." : connectionFailureMessage(cause));
                }
            }
        };
        activeWorker.execute();
    }

    private void cancelOrClose() {
        if (activeWorker != null) {
            cancelWorker();
            appendLog("Cancellation requested.");
            statusBadge.setText("CANCELLING");
        } else {
            dispose();
        }
    }

    private void cancelWorker() {
        if (activeWorker != null) {
            activeWorker.cancel(true);
            activeWorker = null;
        }
    }

    private String connectionFailureMessage(Throwable cause) {
        String message = safeMessage(cause);
        if (message.contains("HTTP 429") || message.contains("HTTP 503")) {
            return message + " Model in use: " + config.model();
        }
        return message;
    }

    private void showFailure(String message) {
        appendLog(message);
        summary.setText(message);
        statusBadge.setText("ERROR");
        statusBadge.setBackground(new Color(250, 220, 220));
        generateButton.setEnabled(true);
        testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
        placeButton.setEnabled(false);
    }

    private void appendLog(String line) {
        if (!logArea.getText().isEmpty()) logArea.append("\n");
        logArea.append(line);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static final class TruthTableModel extends AbstractTableModel {
        private TruthTable table;

        private void setTable(TruthTable next) {
            table = next;
            fireTableStructureChanged();
        }

        @Override public int getRowCount() { return table == null ? 0 : table.rows().size(); }

        @Override
        public int getColumnCount() {
            return table == null ? 0 : table.inputNames().size() + table.outputNames().size();
        }

        @Override
        public String getColumnName(int column) {
            if (table == null) return "";
            if (column < table.inputNames().size()) return table.inputNames().get(column);
            return table.outputNames().get(column - table.inputNames().size());
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (table == null) return "";
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

        @Override public boolean isCellEditable(int row, int column) { return false; }
    }
}
