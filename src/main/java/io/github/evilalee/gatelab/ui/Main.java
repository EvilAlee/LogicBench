package io.github.evilalee.gatelab.ui;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.sim.SignalRecorder;
import io.github.evilalee.gatelab.synth.layout.TidyCircuitService;
import io.github.evilalee.gatelab.synth.CircuitDescriber;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;

import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.JTextComponent;
import javax.swing.table.TableCellEditor;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.filechooser.FileFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Swing application entry point for the LogicBench desktop window. */
public final class Main {
    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 800;

    private Main() {
    }

    public static void main(String[] args) {
        // Set these before Swing/AWT initializes; command-line -D values take precedence.
        if (System.getProperty("swing.volatileImageBufferEnabled") == null) {
            System.setProperty("swing.volatileImageBufferEnabled", "false");
        }
        if (System.getProperty("sun.java2d.pmoffscreen") == null) {
            System.setProperty("sun.java2d.pmoffscreen", "false");
        }

        boolean galleryMode = Arrays.asList(args).contains("--gallery");
        AppIcons.load(); // Decode the small classpath icons before the event-dispatch thread starts.
        javax.swing.SwingUtilities.invokeLater(() -> showWindow(galleryMode));
    }

    private static void showWindow(boolean galleryMode) {
        FlatLightLaf.setup();
        UIManager.put("Component.arc", 0);
        UIManager.put("Button.arc", 0);
        UIManager.put("TextComponent.arc", 0);

        JFrame frame = createFrame(galleryMode);
        frame.setVisible(true);
    }

    static JFrame createFrame() {
        return createFrame(false);
    }

    static JFrame createFrame(boolean galleryMode) {
        JFrame frame = new JFrame("LogicBench - Untitled");
        frame.setIconImages(AppIcons.load());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        CanvasPanel canvas = new CanvasPanel(galleryMode);
        canvas.setComponentDefinitionViewListener(definition ->
                ComponentDefinitionViewer.showDialog(frame, definition));
        DocumentState document = new DocumentState();
        SignalRecorder signalRecorder = new SignalRecorder();
        WaveformPanel waveformPanel = new WaveformPanel(signalRecorder);
        canvas.setSignalRecorderCallbacks(signalRecorder::clear, () -> {
            signalRecorder.record(WaveformSampler.sample(
                    canvas.schematic(), canvas.simulationResult()));
            waveformPanel.refresh();
        });

        canvas.setSchematicListener(schematic ->
                observeSchematic(frame, canvas, document, schematic));
        EditorContent editorContent = createContent(canvas, waveformPanel);
        frame.setJMenuBar(createMenuBar(
                frame, canvas, document, editorContent.waveformVisibility()));
        frame.setContentPane(editorContent.component());
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose(frame, canvas, document);
            }
        });
        List<String> startupLibraryNotices = canvas.componentLibrary().notices();
        if (!startupLibraryNotices.isEmpty()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    frame, String.join("\\n", startupLibraryNotices),
                    "Component library", JOptionPane.INFORMATION_MESSAGE));
        }
        return frame;
    }

    private static EditorContent createContent(
            CanvasPanel canvas, WaveformPanel waveformPanel) {
        javax.swing.JPanel content = new javax.swing.JPanel(new BorderLayout());
        JToggleButton edit = new JToggleButton("Edit");
        JToggleButton poke = new JToggleButton("Poke");
        content.add(createToolbar(canvas, edit, poke), BorderLayout.NORTH);

        AttributeTableModel attributesModel = new AttributeTableModel(canvas);
        JTable attributes = new JTable(attributesModel) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                if (column == 1 && row >= 0 && row < attributesModel.getRowCount()) {
                    return attributesModel.row(row).key().equals("name")
                            ? new AttributeTextEditor()
                            : new AttributeComboEditor(attributesModel);
                }
                return super.getCellEditor(row, column);
            }
        };
        attributes.setFillsViewportHeight(true);
        attributes.setRowHeight(22);
        attributes.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        attributes.getTableHeader().setReorderingAllowed(false);
        canvas.setAttributeStateListener(
                () -> attributesModel.setState(canvas.attributePanelState()));
        canvas.setNameEditorFocusListener(() -> SwingUtilities.invokeLater(() -> {
            int nameRow = attributesModel.rowForKey("name");
            if (nameRow < 0) {
                return;
            }
            attributes.changeSelection(nameRow, 1, false, false);
            if (attributes.editCellAt(nameRow, 1)) {
                java.awt.Component editor = attributes.getEditorComponent();
                if (editor != null) {
                    editor.requestFocusInWindow();
                }
            }
        }));

        JSplitPane leftColumn = createLeftColumn(canvas, attributes);
        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftColumn, canvas);
        workspace.setContinuousLayout(true);
        workspace.setOneTouchExpandable(false);
        workspace.setResizeWeight(0.0);
        workspace.setDividerLocation(265);
        workspace.setBorder(BorderFactory.createEmptyBorder());

        javax.swing.JPanel waveformContainer = new javax.swing.JPanel(new BorderLayout());
        waveformContainer.setBorder(BorderFactory.createTitledBorder("Waveforms"));
        waveformContainer.add(waveformPanel, BorderLayout.CENTER);
        JSplitPane editorSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, workspace, null);
        editorSplit.setContinuousLayout(true);
        editorSplit.setOneTouchExpandable(false);
        editorSplit.setResizeWeight(1.0);
        editorSplit.setBorder(BorderFactory.createEmptyBorder());
        content.add(editorSplit, BorderLayout.CENTER);

        JLabel statusMessage = new JLabel(" ");
        statusMessage.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        canvas.setStatusMessageListener(statusMessage::setText);
        canvas.setStatusMessage(canvas.simulationStatusText());
        content.add(statusMessage, BorderLayout.SOUTH);

        canvas.setToolModeListener(tool -> {
            edit.setSelected(tool != CanvasPanel.Tool.POKE);
            poke.setSelected(tool == CanvasPanel.Tool.POKE);
        });

        Consumer<Boolean> waveformVisibility = visible -> {
            if (visible) {
                editorSplit.setBottomComponent(waveformContainer);
                editorSplit.setResizeWeight(0.75);
                editorSplit.setDividerLocation(0.75);
            } else {
                editorSplit.setBottomComponent(null);
                editorSplit.setResizeWeight(1.0);
            }
            editorSplit.revalidate();
            editorSplit.repaint();
        };
        return new EditorContent(content, waveformVisibility);
    }

    static JMenuBar createMenuBar(CanvasPanel canvas) {
        return createMenuBar(null, canvas, new DocumentState(), ignored -> { });
    }

    static JMenuBar createMenuBar(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        return createMenuBar(frame, canvas, document, ignored -> { });
    }

    private static JMenuBar createMenuBar(
            JFrame frame, CanvasPanel canvas, DocumentState document,
            Consumer<Boolean> waveformVisibility) {
        JMenuBar menuBar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(fileItem("New", KeyEvent.VK_N, 0,
                () -> createNew(frame, canvas, document)));
        file.add(fileItem("Open...", KeyEvent.VK_O, 0,
                () -> openDocument(frame, canvas, document)));
        file.add(fileItem("Save", KeyEvent.VK_S, 0,
                () -> saveDocument(frame, canvas, document)));
        file.add(fileItem("Save As...", KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK,
                () -> saveAs(frame, canvas, document)));
        file.addSeparator();
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> requestClose(frame, canvas, document));
        file.add(exit);
        menuBar.add(file);

        JMenu circuit = new JMenu("Circuit");
        JMenuItem fromExpression = new JMenuItem("From expression...");
        fromExpression.addActionListener(event -> ExpressionDialog.showDialog(frame, canvas));
        circuit.add(fromExpression);
        circuit.addSeparator();

        JMenuItem fromDescription = new JMenuItem("From description (AI)...");
        JCheckBoxMenuItem cacheOnly = new JCheckBoxMenuItem("AI: use cache only (offline)");
        fromDescription.addActionListener(event ->
                AiCircuitDialog.showDialog(frame, canvas, cacheOnly.isSelected()));
        circuit.add(fromDescription);
        circuit.add(cacheOnly);
        circuit.addSeparator();

        JMenuItem tidy = new JMenuItem("Tidy circuit");
        tidy.setEnabled(hasTidyableGate(canvas.schematic()));
        tidy.addActionListener(event -> tidyCircuit(frame, canvas));
        Schematic[] observedSchematic = {null};
        Runnable updateTidyEnabled = () ->
                tidy.setEnabled(hasTidyableGate(canvas.schematic()));
        canvas.addSchematicListener(schematic -> {
            if (observedSchematic[0] != null) {
                observedSchematic[0].removeChangeListener(updateTidyEnabled);
            }
            observedSchematic[0] = schematic;
            schematic.addChangeListener(updateTidyEnabled);
            updateTidyEnabled.run();
        });
        circuit.add(tidy);
        circuit.addSeparator();
        JMenuItem createComponent = new JMenuItem("Create component from circuit...");
        createComponent.addActionListener(event ->
                CreateComponentDialog.showDialog(frame, canvas));
        circuit.add(createComponent);

        JMenu componentsMenu = new JMenu("Components");
        JMenuItem importComponents = new JMenuItem("Import component file(s)...");
        importComponents.addActionListener(event ->
                ComponentLibraryFileActions.importFiles(frame, canvas));
        JMenuItem exportComponent = new JMenuItem("Export component...");
        exportComponent.addActionListener(event ->
                ComponentLibraryFileActions.exportComponent(frame, canvas));
        JMenuItem loadLibrary = new JMenuItem("Load library file...");
        loadLibrary.addActionListener(event ->
                ComponentLibraryFileActions.loadLibraryFile(frame, canvas));
        JMenuItem revealLibrary = new JMenuItem("Reveal library folder");
        revealLibrary.addActionListener(event ->
                ComponentLibraryFileActions.revealFolder(frame, canvas));
        JMenuItem refreshLibrary = new JMenuItem("Refresh");
        refreshLibrary.addActionListener(event ->
                ComponentLibraryFileActions.refresh(frame, canvas));
        componentsMenu.add(importComponents);
        componentsMenu.add(exportComponent);
        componentsMenu.add(loadLibrary);
        componentsMenu.addSeparator();
        componentsMenu.add(revealLibrary);
        componentsMenu.add(refreshLibrary);
        circuit.add(componentsMenu);

        JMenuItem editExpressions = new JMenuItem("Edit as expressions...");
        editExpressions.addActionListener(event -> {
            try {
                CircuitDescriber.Description description =
                        CircuitDescriber.describe(canvas.schematic());
                ExpressionDialog.showDialog(frame, canvas, description.text());
            } catch (IllegalArgumentException | IllegalStateException exception) {
                String message = exception.getMessage() == null
                        ? "The current circuit cannot be exported as expressions."
                        : exception.getMessage();
                canvas.setStatusMessage("Circuit export failed: " + message);
                if (!GraphicsEnvironment.isHeadless()) {
                    JOptionPane.showMessageDialog(
                            frame, message, "Edit as expressions", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        circuit.add(editExpressions);
        menuBar.add(circuit);

        JMenu edit = new JMenu("Edit");
        JMenuItem undo = new JMenuItem("Undo");
        undo.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        undo.addActionListener(event -> canvas.undo());
        JMenuItem redo = new JMenuItem("Redo");
        redo.setAccelerator(KeyStroke.getKeyStroke(
                KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        redo.addActionListener(event -> canvas.redo());
        edit.add(undo);
        edit.add(redo);
        edit.addSeparator();

        JMenuItem cut = new JMenuItem("Cut");
        cut.addActionListener(event -> canvas.cutSelectionToClipboard());
        JMenuItem copy = new JMenuItem("Copy");
        copy.addActionListener(event -> canvas.copySelectionToClipboard());
        JMenuItem paste = new JMenuItem("Paste");
        paste.addActionListener(event -> canvas.pasteFromClipboard());
        JMenuItem duplicate = new JMenuItem("Duplicate");
        duplicate.addActionListener(event -> canvas.duplicateSelection());
        edit.add(cut);
        edit.add(copy);
        edit.add(paste);
        edit.add(duplicate);

        Runnable updateEditActions = () -> {
            boolean hasSelection = canvas.hasSelection();
            cut.setEnabled(hasSelection);
            copy.setEnabled(hasSelection);
            duplicate.setEnabled(hasSelection);
            paste.setEnabled(canvas.canPasteFromClipboard());
        };
        canvas.setEditActionStateListener(updateEditActions);
        edit.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent event) {
                updateEditActions.run();
            }

            @Override
            public void menuDeselected(MenuEvent event) {
            }

            @Override
            public void menuCanceled(MenuEvent event) {
            }
        });
        canvas.setHistoryStateListener(() -> {
            undo.setEnabled(canvas.canUndo());
            redo.setEnabled(canvas.canRedo());
        });
        installUndoRedoBindings(frame, canvas);
        installClipboardBindings(frame, canvas);
        menuBar.add(edit);
        menuBar.add(createViewMenu(canvas, waveformVisibility));
        menuBar.add(menuWithDisabledItems("Project", "Project Options..."));
        menuBar.add(createSimulateMenu(canvas, document));
        menuBar.add(menuWithDisabledItems("Window", "Reset Window Layout"));
        menuBar.add(menuWithDisabledItems("Help", "User Guide", "About LogicBench"));
        return menuBar;
    }

    private static boolean hasTidyableGate(Schematic schematic) {
        boolean hasGate = schematic.components().stream().anyMatch(component ->
                !component.typeId().equals("input")
                        && !component.typeId().equals("output")
                        && !component.typeId().equals("clock")
                        && !component.typeId().equals("constant")
                        && !component.typeId().equals("vcc")
                        && !component.typeId().equals("gnd"));
        boolean hasClock = schematic.components().stream()
                .anyMatch(component -> component.typeId().equals("clock"));
        return hasGate && (!hasClock || FeedbackCircuitExtractor.hasFeedback(schematic));
    }

    private static void tidyCircuit(JFrame frame, CanvasPanel canvas) {
        try {
            TidyCircuitService.TidyResult result = TidyCircuitService.tidy(
                    canvas.schematic(), GateLibrary.load().all());
            canvas.replaceWithTidiedCircuit(result.schematic());
            String message = result.feedbackCircuit()
                    ? result.verificationMessage()
                    : "Tidy complete. " + result.verificationMessage()
                            + " Gate facings reset to EAST.";
            canvas.setStatusMessage(message);
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                        frame, message, "Tidy circuit", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IllegalArgumentException | IllegalStateException exception) {
            String message = exception.getMessage() == null
                    ? "The current circuit cannot be tidied." : exception.getMessage();
            canvas.setStatusMessage("Tidy circuit failed: " + message);
            JOptionPane.showMessageDialog(
                    frame, message, "Cannot tidy circuit", JOptionPane.ERROR_MESSAGE);
        }
    }

    static JMenu createViewMenu(CanvasPanel canvas) {
        return createViewMenu(canvas, ignored -> { });
    }

    static JMenu createViewMenu(
            CanvasPanel canvas, Consumer<Boolean> waveformVisibility) {
        JMenu view = new JMenu("View");
        JCheckBoxMenuItem showGateNames =
                new JCheckBoxMenuItem("Show gate names", GateNamePreferences.load());
        canvas.setShowGateNames(showGateNames.isSelected());
        showGateNames.addActionListener(event -> {
            boolean show = showGateNames.isSelected();
            canvas.setShowGateNames(show);
            GateNamePreferences.save(show);
        });
        view.add(showGateNames);

        JCheckBoxMenuItem showPinDots = new JCheckBoxMenuItem("Show pin dots", true);
        canvas.setShowPinDots(showPinDots.isSelected());
        showPinDots.addActionListener(event ->
                canvas.setShowPinDots(showPinDots.isSelected()));
        view.add(showPinDots);

        JCheckBoxMenuItem showWaveforms = new JCheckBoxMenuItem("Show waveforms");
        showWaveforms.addActionListener(event ->
                waveformVisibility.accept(showWaveforms.isSelected()));
        view.add(showWaveforms);
        return view;
    }

    static JMenu createSimulateMenu(CanvasPanel canvas) {
        return createSimulateMenu(canvas, new DocumentState());
    }

    private static JMenu createSimulateMenu(CanvasPanel canvas, DocumentState document) {
        JMenu simulate = new JMenu("Simulate");
        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(event -> canvas.resetSimulation());
        simulate.add(reset);
        simulate.addSeparator();

        JMenuItem tickOnce = new JMenuItem("Tick Once");
        tickOnce.addActionListener(event -> canvas.stepClock());
        JCheckBoxMenuItem ticksEnabled = new JCheckBoxMenuItem("Ticks Enabled");
        Timer timer = new Timer(500, event -> canvas.stepClock());
        timer.setCoalesce(true);
        ticksEnabled.addActionListener(event -> {
            if (ticksEnabled.isSelected()) {
                timer.start();
            } else {
                timer.stop();
            }
        });
        document.clockTimer = timer;
        document.ticksEnabledItem = ticksEnabled;
        simulate.add(tickOnce);
        simulate.add(ticksEnabled);

        JRadioButtonMenuItem ignore =
                new JRadioButtonMenuItem("Ignore unconnected inputs");
        JRadioButtonMenuItem error =
                new JRadioButtonMenuItem("Error for undefined inputs");
        ButtonGroup modes = new ButtonGroup();
        modes.add(ignore);
        modes.add(error);
        ignore.addActionListener(event -> canvas.schematic().setUndefinedInputMode(
                UndefinedInputMode.IGNORE));
        error.addActionListener(event -> canvas.schematic().setUndefinedInputMode(
                UndefinedInputMode.ERROR));
        syncUndefinedInputModeSelection(canvas, ignore, error);
        simulate.addSeparator();
        simulate.add(ignore);
        simulate.add(error);
        simulate.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent event) {
                syncUndefinedInputModeSelection(canvas, ignore, error);
            }

            @Override
            public void menuDeselected(MenuEvent event) {
            }

            @Override
            public void menuCanceled(MenuEvent event) {
            }
        });
        return simulate;
    }

    private static void syncUndefinedInputModeSelection(
            CanvasPanel canvas, JRadioButtonMenuItem ignore, JRadioButtonMenuItem error) {
        boolean ignoreMode = canvas.schematic().undefinedInputMode() == UndefinedInputMode.IGNORE;
        ignore.setSelected(ignoreMode);
        error.setSelected(!ignoreMode);
    }

    private static void installUndoRedoBindings(JFrame frame, CanvasPanel canvas) {
        if (frame == null) {
            return;
        }
        javax.swing.InputMap input = frame.getRootPane().getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap actions = frame.getRootPane().getActionMap();
        input.put(KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "logicbench-undo");
        input.put(KeyStroke.getKeyStroke(
                KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "logicbench-redo");
        input.put(KeyStroke.getKeyStroke(
                KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "logicbench-redo");
        actions.put("logicbench-undo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                canvas.undo();
            }
        });
        actions.put("logicbench-redo", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                canvas.redo();
            }
        });
    }

    private static void installClipboardBindings(JFrame frame, CanvasPanel canvas) {
        if (frame == null) {
            return;
        }
        javax.swing.InputMap input = frame.getRootPane().getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap actions = frame.getRootPane().getActionMap();
        bindClipboardShortcut(input, actions, KeyEvent.VK_X,
                "logicbench-cut", frame, canvas, canvas::cutSelectionToClipboard);
        bindClipboardShortcut(input, actions, KeyEvent.VK_C,
                "logicbench-copy", frame, canvas, canvas::copySelectionToClipboard);
        bindClipboardShortcut(input, actions, KeyEvent.VK_V,
                "logicbench-paste", frame, canvas, canvas::pasteFromClipboard);
        bindClipboardShortcut(input, actions, KeyEvent.VK_D,
                "logicbench-duplicate", frame, canvas, canvas::duplicateSelection);
    }

    private static void bindClipboardShortcut(
            javax.swing.InputMap input,
            javax.swing.ActionMap actions,
            int keyCode,
            String actionName,
            JFrame frame,
            CanvasPanel canvas,
            Runnable action) {
        input.put(KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK), actionName);
        actions.put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!isTextEntryFocused(frame)) {
                    action.run();
                }
            }
        });
    }

    private static boolean isTextEntryFocused(JFrame frame) {
        java.awt.Component focus = java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextComponent) {
            return true;
        }
        if (focus instanceof javax.swing.JComboBox<?> combo && combo.isEditable()) {
            return true;
        }
        if (focus instanceof JTable table && table.isEditing()) {
            return true;
        }
        java.awt.Container tableAncestor = focus == null ? null
                : javax.swing.SwingUtilities.getAncestorOfClass(JTable.class, focus);
        return tableAncestor instanceof JTable table && table.isEditing();
    }

    private static JMenuItem fileItem(
            String label, int keyCode, int modifiers, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(KeyStroke.getKeyStroke(keyCode,
                InputEvent.CTRL_DOWN_MASK | modifiers));
        item.addActionListener(event -> action.run());
        return item;
    }

    private static void createNew(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        stopClockTicks(document);
        if (!confirmUnsavedChanges(frame, canvas, document)) {
            return;
        }
        Schematic fresh = new Schematic(GateLibrary.load().all());
        document.file = null;
        document.savedModificationCount = fresh.modificationCount();
        canvas.clearSignalHistory();
        canvas.replaceSchematic(fresh);
    }

    private static void openDocument(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        stopClockTicks(document);
        JFileChooser chooser = schematicChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path selected = chooser.getSelectedFile().toPath();
        final Schematic loaded;
        final List<String> definitionNotices;
        try {
            String json = Files.readString(selected);
            SchematicJson.LoadResult result = SchematicJson.fromJsonWithNotices(
                    json, GateLibrary.load().all(), canvas.componentLibrary());
            loaded = result.schematic();
            definitionNotices = result.notices();
        } catch (IOException | IllegalArgumentException exception) {
            showFileError(frame, "Could not open schematic", exception);
            return;
        }

        if (!confirmUnsavedChanges(frame, canvas, document)) {
            return;
        }
        document.file = selected;
        document.savedModificationCount = loaded.modificationCount();
        canvas.clearSignalHistory();
        canvas.replaceSchematic(loaded);
        if (!definitionNotices.isEmpty()) {
            JOptionPane.showMessageDialog(
                    frame,
                    String.join(System.lineSeparator(), definitionNotices),
                    "Component definition versions",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private static boolean saveDocument(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        if (document.file == null) {
            return saveAs(frame, canvas, document);
        }
        try {
            Files.writeString(document.file, SchematicJson.toJson(canvas.schematic()));
            document.savedModificationCount = canvas.schematic().modificationCount();
            updateTitle(frame, canvas, document);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            showFileError(frame, "Could not save schematic", exception);
            return false;
        }
    }

    private static boolean saveAs(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        JFileChooser chooser = schematicChooser();
        if (document.file != null) {
            chooser.setSelectedFile(document.file.toFile());
        }
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return false;
        }

        Path selected = withExtension(chooser.getSelectedFile().toPath());
        if (Files.exists(selected)
                && !selected.equals(document.file)
                && JOptionPane.showConfirmDialog(frame,
                        "Replace the existing file?", "Confirm Save As",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                        != JOptionPane.YES_OPTION) {
            return false;
        }
        try {
            Files.writeString(selected, SchematicJson.toJson(canvas.schematic()));
            document.file = selected;
            document.savedModificationCount = canvas.schematic().modificationCount();
            updateTitle(frame, canvas, document);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            showFileError(frame, "Could not save schematic", exception);
            return false;
        }
    }

    private static Path withExtension(Path path) {
        String name = path.getFileName().toString();
        if (!hasSchematicExtension(name)) {
            return path.resolveSibling(name + SchematicJson.FILE_EXTENSION);
        }
        return path;
    }

    private static JFileChooser schematicChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(schematicFileFilter());
        return chooser;
    }

    static FileFilter schematicFileFilter() {
        return new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isDirectory()
                        || hasSchematicExtension(file.getName(), SchematicJson.FILE_EXTENSION)
                        || hasSchematicExtension(file.getName(), SchematicJson.LEGACY_FILE_EXTENSION);
            }

            @Override
            public String getDescription() {
                return "LogicBench schematic (*" + SchematicJson.FILE_EXTENSION + ")";
            }
        };
    }

    static boolean hasSchematicExtension(String fileName) {
        return hasSchematicExtension(fileName, SchematicJson.FILE_EXTENSION)
                || hasSchematicExtension(fileName, SchematicJson.LEGACY_FILE_EXTENSION);
    }

    private static boolean hasSchematicExtension(String fileName, String extension) {
        return fileName != null
                && fileName.length() >= extension.length()
                && fileName.regionMatches(
                        true, fileName.length() - extension.length(),
                        extension, 0, extension.length());
    }

    private static boolean confirmUnsavedChanges(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        if (!isDirty(canvas, document)) {
            return true;
        }
        Object[] choices = {"Save", "Don't Save", "Cancel"};
        int choice = JOptionPane.showOptionDialog(frame,
                "Save changes to the current schematic?",
                "Unsaved Changes",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null, choices, choices[0]);
        return switch (choice) {
            case 0 -> saveDocument(frame, canvas, document);
            case 1 -> true;
            default -> false;
        };
    }

    private static void requestClose(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        if (confirmUnsavedChanges(frame, canvas, document)) {
            stopClockTicks(document);
            frame.dispose();
        }
    }

    private static void stopClockTicks(DocumentState document) {
        if (document.clockTimer != null) {
            document.clockTimer.stop();
        }
        if (document.ticksEnabledItem != null) {
            document.ticksEnabledItem.setSelected(false);
        }
    }

    private static boolean isDirty(CanvasPanel canvas, DocumentState document) {
        return canvas.schematic().modificationCount() != document.savedModificationCount;
    }

    private static void observeSchematic(
            JFrame frame, CanvasPanel canvas, DocumentState document, Schematic schematic) {
        if (document.observedSchematic != null && document.titleListener != null) {
            document.observedSchematic.removeChangeListener(document.titleListener);
        }
        document.observedSchematic = schematic;
        document.titleListener = () -> updateTitle(frame, canvas, document);
        schematic.addChangeListener(document.titleListener);
        updateTitle(frame, canvas, document);
    }

    private static void updateTitle(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        String fileName = document.file == null
                ? "Untitled" : document.file.getFileName().toString();
        frame.setTitle("LogicBench - " + fileName + (isDirty(canvas, document) ? "*" : ""));
    }

    private static void showFileError(JFrame frame, String title, Exception exception) {
        JOptionPane.showMessageDialog(frame, exception.getMessage(),
                title, JOptionPane.ERROR_MESSAGE);
    }

    private record EditorContent(
            Container component, Consumer<Boolean> waveformVisibility) {
    }

    private static final class DocumentState {
        private Path file;
        private long savedModificationCount;
        private Schematic observedSchematic;
        private Runnable titleListener;
        private Timer clockTimer;
        private JCheckBoxMenuItem ticksEnabledItem;
    }

    private static JMenu menuWithDisabledItems(String title, String... items) {
        JMenu menu = new JMenu(title);
        for (String label : items) {
            JMenuItem item = new JMenuItem(label);
            item.setEnabled(false);
            menu.add(item);
        }
        return menu;
    }

    private static JToolBar createToolbar(
            CanvasPanel canvas, JToggleButton edit, JToggleButton poke) {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setRollover(false);
        toolbar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, new Color(205, 209, 214)));

        edit.setMargin(new Insets(4, 12, 4, 12));
        poke.setMargin(new Insets(4, 12, 4, 12));
        edit.setFocusable(false);
        poke.setFocusable(false);

        ButtonGroup tools = new ButtonGroup();
        tools.add(edit);
        tools.add(poke);
        edit.setSelected(true);
        edit.addActionListener(event -> canvas.setEditMode());
        poke.addActionListener(event -> canvas.setPokeMode());

        toolbar.add(edit);
        toolbar.add(poke);
        return toolbar;
    }

    private static JSplitPane createLeftColumn(CanvasPanel canvas, JTable attributes) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Components");
        DefaultMutableTreeNode gates = new DefaultMutableTreeNode("Gates");
        DefaultMutableTreeNode pins = new DefaultMutableTreeNode("Pins");
        DefaultMutableTreeNode builtIn = new DefaultMutableTreeNode("Built-in");
        DefaultMutableTreeNode myComponents = new DefaultMutableTreeNode("My components");

        for (String family : GateLibrary.load().families()) {
            ComponentTreeItem item = new ComponentTreeItem(family, treeLabel(family));
            if (family.equals("input") || family.equals("output") || family.equals("clock")
                    || family.equals("constant") || family.equals("vcc") || family.equals("gnd")) {
                pins.add(new DefaultMutableTreeNode(item));
            } else {
                gates.add(new DefaultMutableTreeNode(item));
            }
        }
        canvas.componentLibrary().builtIns().forEach(definition ->
                builtIn.add(new DefaultMutableTreeNode(new ComponentTreeItem(
                        "component:" + definition.name(), definition.name()))));
        canvas.componentLibrary().userDefinitions().forEach(definition ->
                myComponents.add(new DefaultMutableTreeNode(new ComponentTreeItem(
                        "component:" + definition.name(), definition.name()))));
        root.add(gates);
        root.add(pins);
        root.add(builtIn);
        root.add(myComponents);

        JTree tree = new JTree(root) {
            @Override
            public String getToolTipText(MouseEvent event) {
                TreePath hoverPath = getPathForLocation(event.getX(), event.getY());
                if (hoverPath == null) return null;
                Object node = hoverPath.getLastPathComponent();
                if (node instanceof DefaultMutableTreeNode treeNode
                        && treeNode.getUserObject() instanceof ComponentTreeItem item
                        && item.typeId().startsWith("component:")) {
                    String name = item.typeId().substring("component:".length());
                    return canvas.componentLibrary().find(name)
                            .map(definition -> definition.description()).orElse(null);
                }
                return null;
            }
        };
        tree.setToolTipText("");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setDragEnabled(true);
        tree.expandPath(new TreePath(root.getPath()));
        tree.expandPath(new TreePath(gates.getPath()));
        tree.expandPath(new TreePath(pins.getPath()));
        tree.expandPath(new TreePath(builtIn.getPath()));
        tree.expandPath(new TreePath(myComponents.getPath()));
        javax.swing.tree.DefaultTreeModel componentTreeModel =
                (javax.swing.tree.DefaultTreeModel) tree.getModel();
        canvas.setComponentLibraryChangedListener(() -> {
            myComponents.removeAllChildren();
            canvas.componentLibrary().userDefinitions().forEach(definition ->
                    myComponents.add(new DefaultMutableTreeNode(new ComponentTreeItem(
                            "component:" + definition.name(), definition.name()))));
            componentTreeModel.reload(myComponents);
            tree.expandPath(new TreePath(myComponents.getPath()));
        });
        tree.addTreeSelectionListener(event ->
                enterAddModeForPath(canvas, tree.getSelectionPath()));
        tree.addMouseListener(new MouseAdapter() {
            private void showDeleteMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) return;
                Object node = path.getLastPathComponent();
                if (!(node instanceof DefaultMutableTreeNode treeNode)
                        || !(treeNode.getUserObject() instanceof ComponentTreeItem item)
                        || !item.typeId().startsWith("component:")) return;
                String name = item.typeId().substring("component:".length());
                if (canvas.componentLibrary().builtInNames().contains(name)) return;
                JPopupMenu popup = new JPopupMenu();
                JMenuItem delete = new JMenuItem("Delete component from My components");
                delete.addActionListener(action ->
                        ComponentLibraryFileActions.deleteComponent(
                                (JFrame) SwingUtilities.getWindowAncestor(tree), canvas, name));
                popup.add(delete);
                popup.show(tree, event.getX(), event.getY());
            }

            @Override public void mousePressed(MouseEvent event) { showDeleteMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showDeleteMenu(event); }
            @Override public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    enterAddModeForPath(canvas, tree.getPathForLocation(event.getX(), event.getY()));
                }
            }
        });
        tree.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(javax.swing.JComponent component) { return COPY; }
            @Override protected Transferable createTransferable(javax.swing.JComponent component) {
                TreePath path = tree.getSelectionPath();
                if (path == null) return null;
                Object node = path.getLastPathComponent();
                if (node instanceof DefaultMutableTreeNode treeNode
                        && treeNode.getUserObject() instanceof ComponentTreeItem item) {
                    return new StringSelection(item.typeId());
                }
                return null;
            }
        });

        JScrollPane explorer = new JScrollPane(tree);
        explorer.setBorder(BorderFactory.createTitledBorder("Component Explorer"));
        JScrollPane attributeTable = new JScrollPane(attributes);
        attributeTable.setBorder(BorderFactory.createTitledBorder("Attributes"));
        attributeTable.setPreferredSize(new Dimension(240, 180));
        JSplitPane splitPane = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, explorer, attributeTable);
        splitPane.setContinuousLayout(true);
        splitPane.setOneTouchExpandable(false);
        splitPane.setResizeWeight(0.68);
        splitPane.setDividerLocation(480);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setMinimumSize(new Dimension(230, 200));
        return splitPane;
    }

    private static void enterAddModeForPath(CanvasPanel canvas, TreePath path) {
        if (path == null) {
            return;
        }
        Object node = path.getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof ComponentTreeItem item) {
            canvas.enterAddMode(item.typeId());
        }
    }

    private static String treeLabel(String id) {
        return switch (id) {
            case "and", "or", "not", "nand", "nor", "xor", "xnor" -> id.toUpperCase();
            case "buffer" -> "Buffer";
            case "input" -> "Input";
            case "output" -> "Output";
            case "clock" -> "Clock";
            case "constant" -> "Constant";
            case "vcc" -> "VCC";
            case "gnd" -> "GND";
            default -> id;
        };
    }

    private static final class AttributeTableModel extends AbstractTableModel {
        private final CanvasPanel canvas;
        private List<CanvasPanel.AttributeRow> rows = List.of();

        private AttributeTableModel(CanvasPanel canvas) {
            this.canvas = canvas;
        }

        void setState(CanvasPanel.AttributePanelState state) {
            rows = state.rows();
            fireTableDataChanged();
        }

        CanvasPanel.AttributeRow row(int index) {
            return rows.get(index);
        }

        int rowForKey(String key) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).key().equals(key)) {
                    return index;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Attribute" : "Value";
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CanvasPanel.AttributeRow row = rows.get(rowIndex);
            if (columnIndex == 0) {
                return row.label();
            }
            return row.key().equals("name") ? row.selected().value() : row.selected();
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            CanvasPanel.AttributeRow row = rows.get(rowIndex);
            return columnIndex == 1
                    && (row.key().equals("name") || !row.options().isEmpty());
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 1) {
                return;
            }
            CanvasPanel.AttributeRow row = rows.get(rowIndex);
            if (row.key().equals("name") && value instanceof String name) {
                canvas.setSelectedAttribute(row.key(), name);
            } else if (value instanceof CanvasPanel.AttributeOption option) {
                canvas.setSelectedAttribute(row.key(), option.value());
            } else {
                return;
            }
            SwingUtilities.invokeLater(canvas::requestFocusInWindow);
        }
    }

    private static final class AttributeTextEditor extends AbstractCellEditor
            implements TableCellEditor {
        private JTextField field = new JTextField();

        @Override
        public java.awt.Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            field = new JTextField(value == null ? "" : value.toString());
            field.addActionListener(event -> stopCellEditing());
            field.getInputMap(JComponent.WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-name-edit");
            field.getActionMap().put("cancel-name-edit", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    cancelCellEditing();
                }
            });
            return field;
        }

        @Override
        public Object getCellEditorValue() {
            return field.getText();
        }
    }

    private static final class AttributeComboEditor extends AbstractCellEditor
            implements TableCellEditor {
        private final AttributeTableModel model;
        private JComboBox<CanvasPanel.AttributeOption> combo =
                new JComboBox<>();

        private AttributeComboEditor(AttributeTableModel model) {
            this.model = model;
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            CanvasPanel.AttributeRow attribute = model.row(row);
            combo = new JComboBox<>(attribute.options().toArray(
                    CanvasPanel.AttributeOption[]::new));
            combo.setSelectedItem(attribute.selected());
            combo.getInputMap(JComponent.WHEN_FOCUSED).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    "cancel-attribute-edit");
            combo.getActionMap().put("cancel-attribute-edit", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    cancelCellEditing();
                }
            });
            combo.addActionListener(event -> fireEditingStopped());
            return combo;
        }

        @Override
        public Object getCellEditorValue() {
            return combo.getSelectedItem();
        }
    }

    private record ComponentTreeItem(String typeId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
