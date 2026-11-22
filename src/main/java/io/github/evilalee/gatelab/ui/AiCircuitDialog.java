package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.ai.AiCache;
import io.github.evilalee.gatelab.ai.AiCircuitService;
import io.github.evilalee.gatelab.ai.AiExplainService;
import io.github.evilalee.gatelab.ai.AiConfig;
import io.github.evilalee.gatelab.ai.AiConnectionTester;
import io.github.evilalee.gatelab.ai.GeminiProvider;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.synth.Expression;
import io.github.evilalee.gatelab.synth.NamedExpression;
import io.github.evilalee.gatelab.synth.TruthTable;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
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
    private final JTextArea explanationArea = new JTextArea(12, 42);
    private final JTextArea explainFactsArea = new JTextArea(12, 36);
    private final JLabel keyStatus = new JLabel();
    private final JLabel setupHelp = new JLabel(" ");
    private final JLabel modeHint = new JLabel(
            "Describe the circuit behavior, inputs and requested outputs.");
    private final JLabel summary = new JLabel(" ");
    private final JLabel statusBadge = new JLabel("READY", SwingConstants.CENTER);
    private final JComboBox<String> modeSelector =
            new JComboBox<>(new String[]{"Generate", "Explain"});
    private final TruthTableModel tableModel;
    private final JTable truthTable;
    private final JButton generateButton = new JButton("Generate");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton placeButton = new JButton("Place circuit");
    private final JButton testConnectionButton = new JButton("Test connection");
    private final JButton copyExpressionsButton = new JButton("Copy expressions");
    private final JButton previewExplainButton = new JButton("Show what will be sent");
    private final JCheckBox optimizeInvertingGates =
            new JCheckBox("Optimize with NAND/NOR/XNOR", true);
    private final JCheckBox useTemplates = createUseTemplatesCheckbox();
    private final JTextArea expectedRows = new JTextArea(2, 64);
    private final JScrollPane expectedRowsScroll = new JScrollPane(expectedRows);
    private final JPanel generationOptions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JPanel resultCards = new JPanel(new CardLayout());
    private final JScrollPane descriptionScroll;

    private SwingWorker<?, ?> activeWorker;
    private AiCircuitService.Result currentResult;
    private AiExplainService.PreparedRequest preparedExplainRequest;
    private long preparedExplainRevision = -1;
    private String generatePromptText = "";
    private String explainQuestionText = AiExplainService.DEFAULT_QUESTION;
    private String preparedExplainQuestion = "";
    private String generateButtonText = "Generate";
    private String lastSelectedMode = "Generate";
    private boolean updatingModeSelection;

    private record ExplainWork(
            AiExplainService.PreparedRequest request,
            AiCircuitService.ExplanationResult result) {
    }

    private AiCircuitDialog(JFrame owner, CanvasPanel canvas, boolean cacheOnly) {
        super(owner, "From description (AI)", ModalityType.APPLICATION_MODAL);
        setIconImages(AppIcons.load());
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.cacheOnly = cacheOnly;
        this.config = AiConfig.load();
        this.tableModel = new TruthTableModel(config);
        this.truthTable = new JTable(tableModel);
        this.cache = new AiCache(config.configDirectory());
        this.service = new AiCircuitService(
                new GeminiProvider(config), io.github.evilalee.gatelab.io.GateLibrary.load().all(), cache);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JPanel modeHeader = new JPanel(new BorderLayout(8, 0));
        modeHeader.add(modeHint, BorderLayout.CENTER);
        modeHeader.add(modeSelector, BorderLayout.EAST);
        top.add(modeHeader, BorderLayout.NORTH);
        description.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { descriptionChanged(); }
            @Override public void removeUpdate(DocumentEvent event) { descriptionChanged(); }
            @Override public void changedUpdate(DocumentEvent event) { descriptionChanged(); }
        });
        expectedRows.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { invalidateResult(); }
            @Override public void removeUpdate(DocumentEvent event) { invalidateResult(); }
            @Override public void changedUpdate(DocumentEvent event) { invalidateResult(); }
        });
        descriptionScroll = new JScrollPane(description);
        descriptionScroll.setBorder(BorderFactory.createTitledBorder("Circuit description"));
        top.add(descriptionScroll, BorderLayout.CENTER);

        JPanel keyPanel = new JPanel(new BorderLayout(8, 2));
        keyStatus.setText(keyStatusText());
        setupHelp.setText(setupHelpText());
        setupHelp.setForeground(new Color(145, 88, 0));
        keyPanel.add(keyStatus, BorderLayout.NORTH);
        keyPanel.add(setupHelp, BorderLayout.CENTER);
        generationOptions.add(useTemplates);
        generationOptions.add(optimizeInvertingGates);
        keyPanel.add(generationOptions, BorderLayout.EAST);
        expectedRows.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        expectedRows.setLineWrap(false);
        expectedRowsScroll.setPreferredSize(new Dimension(500, 58));
        expectedRowsScroll.setBorder(
                BorderFactory.createTitledBorder("Expected rows (optional)"));
        JPanel topFooter = new JPanel(new BorderLayout(4, 4));
        topFooter.add(expectedRowsScroll, BorderLayout.CENTER);
        topFooter.add(keyPanel, BorderLayout.SOUTH);
        top.add(topFooter, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Generation log"));

        expressionsArea.setEditable(false);
        expressionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        expressionsArea.setLineWrap(true);
        expressionsArea.setWrapStyleWord(true);
        JScrollPane expressionScroll = new JScrollPane(expressionsArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
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
        JPanel resultActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        copyExpressionsButton.setEnabled(false);
        copyExpressionsButton.addActionListener(event -> copyExpressions());
        resultActions.add(copyExpressionsButton);
        resultHeader.add(statusBadge, BorderLayout.WEST);
        resultHeader.add(summary, BorderLayout.CENTER);
        resultHeader.add(resultActions, BorderLayout.EAST);
        resultPanel.add(resultHeader, BorderLayout.NORTH);
        JSplitPane detailSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, expressionScroll, tableScroll);
        detailSplit.setResizeWeight(0.35);
        resultCards.add(detailSplit, "generate");

        explanationArea.setEditable(false);
        explanationArea.setLineWrap(true);
        explanationArea.setWrapStyleWord(true);
        JScrollPane explanationScroll = new JScrollPane(explanationArea);
        explanationScroll.setBorder(BorderFactory.createTitledBorder(
                "AI explanation, not verified"));

        explainFactsArea.setEditable(false);
        explainFactsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        explainFactsArea.setLineWrap(false);
        JScrollPane factsScroll = new JScrollPane(explainFactsArea);
        factsScroll.setBorder(BorderFactory.createTitledBorder("Engine facts"));

        JSplitPane explainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, explanationScroll, factsScroll);
        explainSplit.setResizeWeight(0.62);
        resultCards.add(explainSplit, "explain");
        resultPanel.add(resultCards, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, logScroll, resultPanel);
        mainSplit.setResizeWeight(0.25);
        mainSplit.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        add(mainSplit, BorderLayout.CENTER);

        placeButton.setEnabled(false);
        cancelButton.addActionListener(event -> cancelOrClose());
        generateButton.addActionListener(event -> {
            if (isExplainMode()) {
                explain();
            } else {
                generate("Regenerate".equals(generateButton.getText()));
            }
        });
        modeSelector.addActionListener(event -> changeMode());
        previewExplainButton.addActionListener(event -> previewExplain());
        placeButton.addActionListener(event -> place());
        testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
        testConnectionButton.addActionListener(event -> testConnection());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(testConnectionButton);
        buttons.add(previewExplainButton);
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
        previewExplainButton.setVisible(false);
        updateModePresentation();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                cancelWorker();
            }
        });

        setMinimumSize(new Dimension(820, 680));
        setSize(1000, 790);
        setLocationRelativeTo(owner);
    }

    static JCheckBox createUseTemplatesCheckbox() {
        return new JCheckBox("Use templates", true);
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

    private boolean isExplainMode() {
        return "Explain".equals(modeSelector.getSelectedItem());
    }

    private void descriptionChanged() {
        if (updatingModeSelection) return;
        if (isExplainMode()) {
            explainQuestionText = description.getText();
            preparedExplainRequest = null;
            preparedExplainRevision = -1;
            explanationArea.setText("");
            explainFactsArea.setText("");
        } else {
            generatePromptText = description.getText();
            invalidateResult();
        }
    }

    private void changeMode() {
        if (activeWorker != null) {
            updatingModeSelection = true;
            modeSelector.setSelectedItem(lastSelectedMode);
            updatingModeSelection = false;
            return;
        }
        String selected = Objects.toString(modeSelector.getSelectedItem(), "Generate");
        if (selected.equals(lastSelectedMode)) return;
        if ("Generate".equals(lastSelectedMode)) {
            generatePromptText = description.getText();
        } else {
            explainQuestionText = description.getText();
        }

        updatingModeSelection = true;
        description.setText("Explain".equals(selected)
                ? (explainQuestionText.isBlank()
                    ? AiExplainService.DEFAULT_QUESTION : explainQuestionText)
                : generatePromptText);
        updatingModeSelection = false;
        lastSelectedMode = selected;
        preparedExplainRequest = null;
        preparedExplainRevision = -1;
        preparedExplainQuestion = "";
        updateModePresentation();
        if (isExplainMode()) {
            explanationArea.setText("");
            explainFactsArea.setText("");
            summary.setText(" ");
            statusBadge.setText("READY");
            statusBadge.setBackground(new Color(235, 235, 235));
            placeButton.setEnabled(false);
        } else if (currentResult != null) {
            showResult(currentResult);
        } else {
            summary.setText(" ");
            statusBadge.setText("READY");
            statusBadge.setBackground(new Color(235, 235, 235));
        }
    }

    private void updateModePresentation() {
        boolean explain = isExplainMode();
        modeHint.setText(explain
                ? "Ask about the circuit currently open in the editor."
                : "Describe the circuit behavior, inputs and requested outputs.");
        descriptionScroll.setBorder(BorderFactory.createTitledBorder(
                explain ? "Question" : "Circuit description"));
        generationOptions.setVisible(!explain);
        expectedRowsScroll.setVisible(!explain);
        placeButton.setVisible(!explain);
        copyExpressionsButton.setVisible(!explain);
        previewExplainButton.setVisible(explain);
        generateButton.setText(explain ? "Explain" : generateButtonText);
        ((CardLayout) resultCards.getLayout()).show(
                resultCards, explain ? "explain" : "generate");
        revalidate();
        repaint();
    }

    private void setExplainBusy(boolean busy) {
        generateButton.setEnabled(!busy);
        previewExplainButton.setEnabled(!busy);
        modeSelector.setEnabled(!busy);
        description.setEditable(!busy);
        testConnectionButton.setEnabled(!busy && config.hasApiKey() && !cacheOnly);
        placeButton.setEnabled(false);
    }

    private void previewExplain() {
        if (!isExplainMode() || activeWorker != null) return;
        String question = description.getText().isBlank()
                ? AiExplainService.DEFAULT_QUESTION : description.getText().trim();
        Schematic snapshot = canvas.schematic().copy();
        long revision = canvas.schematic().modificationCount();
        setExplainBusy(true);
        appendLog("Preparing the exact Explain request from the open circuit.");
        activeWorker = new SwingWorker<AiExplainService.PreparedRequest, Void>() {
            @Override
            protected AiExplainService.PreparedRequest doInBackground() {
                return AiExplainService.prepare(snapshot, question);
            }

            @Override
            protected void done() {
                activeWorker = null;
                setExplainBusy(false);
                try {
                    preparedExplainRequest = get();
                    preparedExplainRevision = revision;
                    preparedExplainQuestion = question;
                    showExplainPreview(preparedExplainRequest);
                } catch (CancellationException exception) {
                    appendLog("Explain preview cancelled.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showExplainPreparationFailure("Explain preview interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showExplainPreparationFailure(
                            cause == null ? "Could not prepare Explain request."
                                    : safeMessage(cause));
                }
            }
        };
        activeWorker.execute();
    }

    private void showExplainPreview(AiExplainService.PreparedRequest request) {
        String exactText = "SYSTEM PROMPT\\n" + request.systemPrompt()
                + "\\n\\nUSER MESSAGE\\n"
                + service.redactForProvider(request.requestText());
        JTextArea preview = new JTextArea(exactText, 24, 88);
        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        preview.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(preview);
        JOptionPane.showMessageDialog(this, scroll,
                "Exact text sent to the AI provider", JOptionPane.INFORMATION_MESSAGE);
    }

    private void explain() {
        if (!isExplainMode() || activeWorker != null) return;
        String question = description.getText().isBlank()
                ? AiExplainService.DEFAULT_QUESTION : description.getText().trim();
        Schematic snapshot = canvas.schematic().copy();
        long revision = canvas.schematic().modificationCount();
        AiExplainService.PreparedRequest reusable =
                preparedExplainRequest != null
                        && preparedExplainRevision == revision
                        && question.equals(preparedExplainQuestion)
                        ? preparedExplainRequest : null;

        explanationArea.setText("");
        explainFactsArea.setText("");
        summary.setText("Explaining the currently open circuit...");
        statusBadge.setText("WORKING");
        appendLog("Preparing circuit export and engine facts.");
        setExplainBusy(true);
        activeWorker = new SwingWorker<ExplainWork, Void>() {
            @Override
            protected ExplainWork doInBackground() throws Exception {
                AiExplainService.PreparedRequest request = reusable == null
                        ? AiExplainService.prepare(snapshot, question) : reusable;
                AiCircuitService.ExplanationResult result;
                if (cacheOnly) {
                    result = new AiCircuitService.ExplanationResult(
                            0, AiCircuitService.ExplanationStatus.UNAVAILABLE,
                            "UNAVAILABLE: Explain mode requires a live provider; generation cache is not used.",
                            List.of(), List.of(), List.of(), "", List.of());
                } else {
                    result = service.explain(request.systemPrompt(), request.requestText());
                }
                return new ExplainWork(request, result);
            }

            @Override
            protected void done() {
                activeWorker = null;
                setExplainBusy(false);
                try {
                    ExplainWork work = get();
                    preparedExplainRequest = work.request();
                    preparedExplainRevision = revision;
                    preparedExplainQuestion = question;
                    for (String line : work.result().attemptLog()) appendLog(line);
                    showExplainResult(work.request(), work.result());
                } catch (CancellationException exception) {
                    appendLog("Explain request cancelled.");
                    statusBadge.setText("CANCELLED");
                    summary.setText("Explain request was cancelled.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showExplainPreparationFailure("Explain request interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showExplainPreparationFailure(
                            cause == null ? "Could not explain this circuit." : safeMessage(cause));
                }
            }
        };
        activeWorker.execute();
    }

    private void showExplainResult(
            AiExplainService.PreparedRequest request,
            AiCircuitService.ExplanationResult result) {
        StringBuilder text = new StringBuilder(result.summary());
        if (!result.steps().isEmpty()) {
            text.append("\n\nSteps\n");
            for (int index = 0; index < result.steps().size(); index++) {
                text.append(index + 1).append(". ").append(result.steps().get(index)).append('\n');
            }
        }
        if (!result.notable().isEmpty()) {
            text.append("\nNotable\n");
            for (String item : result.notable()) text.append("• ").append(item).append('\n');
        }
        explanationArea.setText(config.redact(text.toString().stripTrailing()));
        explainFactsArea.setText(config.redact(request.facts().format()));
        explanationArea.setCaretPosition(0);
        explainFactsArea.setCaretPosition(0);
        summary.setText("AI explanation, not verified");
        statusBadge.setBackground(new Color(235, 235, 235));
        switch (result.status()) {
            case EXPLAINED -> statusBadge.setText("NOT VERIFIED");
            case INVALID_RESPONSE -> statusBadge.setText("INVALID RESPONSE");
            case UNAVAILABLE -> statusBadge.setText("UNAVAILABLE");
        }
        placeButton.setEnabled(false);
    }

    private void showExplainPreparationFailure(String message) {
        message = config.redact(message);
        appendLog(message);
        explanationArea.setText(message);
        explainFactsArea.setText("");
        summary.setText("AI explanation, not verified");
        statusBadge.setText("NOT SENT");
        statusBadge.setBackground(new Color(250, 235, 195));
        placeButton.setEnabled(false);
    }

    private void generate(boolean bypassCache) {
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
        boolean optimize = optimizeInvertingGates.isSelected();
        boolean templates = useTemplates.isSelected();
        String rowText = expectedRows.getText();
        optimizeInvertingGates.setEnabled(false);
        useTemplates.setEnabled(false);
        expectedRows.setEditable(false);
        modeSelector.setEnabled(false);
        activeWorker = new SwingWorker<AiCircuitService.Result, Void>() {
            @Override
            protected AiCircuitService.Result doInBackground() throws Exception {
                return service.generate(prompt, cacheOnly, optimize, rowText, bypassCache, templates);
            }

            @Override
            protected void done() {
                activeWorker = null;
                generateButton.setEnabled(true);
                modeSelector.setEnabled(true);
                description.setEditable(true);
                optimizeInvertingGates.setEnabled(true);
                useTemplates.setEnabled(true);
                expectedRows.setEditable(true);
                testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
                try {
                    currentResult = get();
                    if (!cacheOnly) {
                        generateButtonText = "Regenerate";
                        if (!isExplainMode()) generateButton.setText(generateButtonText);
                    }
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
        generateButtonText = "Generate";
        if (!isExplainMode()) generateButton.setText(generateButtonText);
        placeButton.setEnabled(false);
        expressionsArea.setText("");
        copyExpressionsButton.setEnabled(false);
        tableModel.setTable(null);
        summary.setText(" ");
        statusBadge.setText("READY");
        statusBadge.setBackground(new Color(235, 235, 235));
    }

    private void showResult(AiCircuitService.Result result) {
        String displaySummary = config.redact(result.summary());
        if (result.fromCache()) {
            String unavailableNotice = result.attemptLog().stream()
                    .filter(line -> line.startsWith("AI unavailable ("))
                    .findFirst().orElse("");
            if (!unavailableNotice.isBlank()) {
                displaySummary = config.redact(unavailableNotice) + " — " + displaySummary;
            }
            if (!result.answeredBy().isBlank()) {
                displaySummary += " — FROM CACHE via " + config.redact(result.answeredBy());
            }
        } else if (!result.answeredBy().isBlank()) {
            String status = switch (result.status()) {
                case VERIFIED -> "VERIFIED";
                case BUILT -> "BUILT";
                case UNAVAILABLE -> "UNAVAILABLE";
                case UNVERIFIED -> "UNVERIFIED";
            };
            displaySummary += " — " + status + " via " + config.redact(result.answeredBy());
        }
        summary.setText(config.redact(displaySummary));
        expressionsArea.setText(formatExpressions(result.signals(), result.expressions()));
        copyExpressionsButton.setEnabled(!expressionsArea.getText().isBlank());
        tableModel.setTable(result.truthTable());
        if (result.fromCache()) {
            statusBadge.setText("FROM CACHE");
            statusBadge.setBackground(new Color(220, 235, 250));
        } else if (result.status() == AiCircuitService.Status.VERIFIED) {
            statusBadge.setText("VERIFIED");
            statusBadge.setBackground(new Color(215, 240, 220));
        } else if (result.status() == AiCircuitService.Status.BUILT) {
            statusBadge.setText("BUILT: no independent check, read the truth table");
            statusBadge.setBackground(new Color(235, 235, 235));
        } else if (result.status() == AiCircuitService.Status.UNAVAILABLE) {
            statusBadge.setText("UNAVAILABLE");
            statusBadge.setBackground(new Color(250, 235, 195));
        } else {
            statusBadge.setText("UNVERIFIED");
            statusBadge.setBackground(new Color(250, 225, 205));
        }
        boolean placeable = result.status() != AiCircuitService.Status.UNAVAILABLE
                && result.layout() != null;
        placeButton.setEnabled(placeable);
        if (result.status() == AiCircuitService.Status.BUILT) {
            appendLog("BUILT: no independent check was supplied; read the engine truth table.");
        } else if (result.status() == AiCircuitService.Status.UNVERIFIED) {
            appendLog("UNVERIFIED: one or more validation checks failed.");
        }
    }

    private String formatExpressions(
            List<NamedExpression> signals, List<NamedExpression> outputs) {
        return config.redact(String.join("\n",
                formattedExpressionLines(signals, outputs,
                        Math.max(1, expressionsArea.getColumns()))));
    }

    static List<String> formattedExpressionLines(
            List<NamedExpression> signals, List<NamedExpression> outputs, int width) {
        List<String> lines = new ArrayList<>();
        for (NamedExpression signal : signals) {
            lines.add(signal.name() + " := " + formatExpression(signal.expression()));
        }
        for (NamedExpression output : outputs) {
            lines.add(output.name() + " = " + formatExpression(output.expression()));
        }
        return wrapExpressionLines(String.join("\n", lines), width);
    }

    static List<String> wrapExpressionLines(String source, int width) {
        Objects.requireNonNull(source, "source");
        if (width < 1) throw new IllegalArgumentException("width must be positive");
        List<String> wrapped = new ArrayList<>();
        for (String paragraph : source.split("\\n", -1)) {
            String remaining = paragraph;
            while (remaining.length() > width) {
                int split = remaining.lastIndexOf(' ', width);
                if (split <= 0) split = width;
                wrapped.add(remaining.substring(0, split));
                remaining = remaining.substring(split).stripLeading();
            }
            wrapped.add(remaining);
        }
        return List.copyOf(wrapped);
    }

    private void copyExpressions() {
        if (currentResult == null) return;
        List<String> lines = new ArrayList<>();
        for (NamedExpression signal : currentResult.signals()) {
            lines.add(signal.name() + " := " + formatExpression(signal.expression()));
        }
        for (NamedExpression output : currentResult.expressions()) {
            lines.add(output.name() + " = " + formatExpression(output.expression()));
        }
        String text = config.redact(String.join("\n", lines));
        if (text.isBlank()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            appendLog("Expressions copied to clipboard.");
        } catch (RuntimeException exception) {
            appendLog("Could not access the system clipboard: " + safeMessage(exception));
        }
    }

    private static String formatExpression(Expression expression) {
        return switch (expression) {
            case Expression.Constant constant -> constant.value() ? "1" : "0";
            case Expression.Variable variable -> variable.name();
            case Expression.Unary unary ->
                    "NOT (" + formatExpression(unary.operand()) + ")";
            case Expression.PartCall call -> call.kind() + "("
                    + String.join(", ", call.arguments().stream()
                            .map(AiCircuitDialog::formatExpression).toList()) + ")";
            case Expression.ComponentCall call -> call.componentName() + "("
                    + String.join(", ", call.arguments().stream()
                            .map(AiCircuitDialog::formatExpression).toList()) + ")";
            case Expression.ComponentOutput output ->
                    output.instanceName() + "." + output.outputName();
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
        if (isExplainMode() || currentResult == null || currentResult.layout() == null) return;
        if (currentResult.status() == AiCircuitService.Status.UNVERIFIED) {
            String rows = config.redact(String.join("\n", currentResult.mismatchingRows()));
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "This circuit is UNVERIFIED. These validation checks failed:\n\n"
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
        canvas.setStatusMessage(switch (currentResult.status()) {
            case VERIFIED -> "AI circuit verified against independent checks and placed.";
            case BUILT -> "AI circuit built; no independent check was supplied.";
            case UNVERIFIED -> "Unverified AI circuit placed after confirmation.";
            case UNAVAILABLE -> "AI circuit unavailable.";
        });
        dispose();
    }

    private void testConnection() {
        if (!config.hasApiKey() || activeWorker != null || cacheOnly) return;
        testConnectionButton.setEnabled(false);
        generateButton.setEnabled(false);
        appendLog("Testing Gemini connection and configured fallback models...");
        activeWorker = new SwingWorker<AiConnectionTester.Result, Void>() {
            @Override
            protected AiConnectionTester.Result doInBackground() throws Exception {
                return AiConnectionTester.test(new GeminiProvider(config));
            }

            @Override
            protected void done() {
                activeWorker = null;
                generateButton.setEnabled(true);
                testConnectionButton.setEnabled(config.hasApiKey() && !cacheOnly);
                try {
                    AiConnectionTester.Result result = get();
                    for (String line : result.modelAttempts()) appendLog(line);
                    String details = String.join("\n", result.modelAttempts());
                    if (result.connected()) {
                        appendLog(result.message());
                        JOptionPane.showMessageDialog(AiCircuitDialog.this,
                                config.redact(result.message()) + "\n" + details,
                                "Test connection", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        showFailure(result.message());
                        JOptionPane.showMessageDialog(AiCircuitDialog.this,
                                config.redact(result.message()) + "\n" + details,
                                "Test connection", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (CancellationException exception) {
                    appendLog("Connection test cancelled.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    showFailure("Connection test interrupted.");
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    showFailure(cause == null ? "Connection test failed." : safeMessage(cause));
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
        message = config.redact(message);
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
        logArea.append(config.redact(line));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return config.redact(message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message);
    }

    private static final class TruthTableModel extends AbstractTableModel {
        private final AiConfig config;
        private TruthTable table;

        private TruthTableModel(AiConfig config) {
            this.config = config;
        }

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
            if (column < table.inputNames().size()) {
                return config.redact(table.inputNames().get(column));
            }
            return config.redact(table.outputNames().get(column - table.inputNames().size()));
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
