package io.github.evilalee.gatelab.ui;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.UndefinedInputMode;
import io.github.evilalee.gatelab.synth.layout.TidyCircuitService;

import javax.swing.AbstractAction;
import javax.swing.AbstractCellEditor;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
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
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
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
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        CanvasPanel canvas = new CanvasPanel(galleryMode);
        DocumentState document = new DocumentState();

        canvas.setSchematicListener(schematic ->
                observeSchematic(frame, canvas, document, schematic));
        frame.setJMenuBar(createMenuBar(frame, canvas, document));
        frame.setContentPane(createContent(canvas));
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose(frame, canvas, document);
            }
        });
        return frame;
    }

    private static Container createContent(CanvasPanel canvas) {
        javax.swing.JPanel content = new javax.swing.JPanel(new BorderLayout());
        JToggleButton edit = new JToggleButton("Edit");
        JToggleButton poke = new JToggleButton("Poke");
        content.add(createToolbar(canvas, edit, poke), BorderLayout.NORTH);

        AttributeTableModel attributesModel = new AttributeTableModel(canvas);
        JTable attributes = new JTable(attributesModel) {
            @Override
            public TableCellEditor getCellEditor(int row, int column) {
                if (column == 1 && row >= 0 && row < attributesModel.getRowCount()) {
                    return new AttributeComboEditor(attributesModel);
                }
                return super.getCellEditor(row, column);
            }
        };
        attributes.setFillsViewportHeight(true);
        attributes.setRowHeight(22);
        attributes.getTableHeader().setReorderingAllowed(false);
        canvas.setAttributeStateListener(
                () -> attributesModel.setState(canvas.attributePanelState()));

        JSplitPane leftColumn = createLeftColumn(canvas, attributes);
        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftColumn, canvas);
        workspace.setContinuousLayout(true);
        workspace.setOneTouchExpandable(false);
        workspace.setResizeWeight(0.0);
        workspace.setDividerLocation(265);
        workspace.setBorder(BorderFactory.createEmptyBorder());
        content.add(workspace, BorderLayout.CENTER);
        JLabel statusMessage = new JLabel(" ");
        statusMessage.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        canvas.setStatusMessageListener(statusMessage::setText);
        content.add(statusMessage, BorderLayout.SOUTH);

        canvas.setToolModeListener(tool -> {
            edit.setSelected(tool != CanvasPanel.Tool.POKE);
            poke.setSelected(tool == CanvasPanel.Tool.POKE);
        });
        return content;
    }

    static JMenuBar createMenuBar(CanvasPanel canvas) {
        return createMenuBar(null, canvas, new DocumentState());
    }

    static JMenuBar createMenuBar(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
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
        for (String label : new String[]{"Cut", "Copy", "Paste"}) {
            JMenuItem placeholder = new JMenuItem(label);
            placeholder.setEnabled(false);
            edit.add(placeholder);
        }
        canvas.setHistoryStateListener(() -> {
            undo.setEnabled(canvas.canUndo());
            redo.setEnabled(canvas.canRedo());
        });
        installUndoRedoBindings(frame, canvas);
        menuBar.add(edit);
        menuBar.add(menuWithDisabledItems("Project", "Project Options..."));
        menuBar.add(createSimulateMenu(canvas));
        menuBar.add(menuWithDisabledItems("Window", "Reset Window Layout"));
        menuBar.add(menuWithDisabledItems("Help", "User Guide", "About LogicBench"));
        return menuBar;
    }

    private static boolean hasTidyableGate(Schematic schematic) {
        return schematic.components().stream().anyMatch(component ->
                !component.typeId().equals("input") && !component.typeId().equals("output"));
    }

    private static void tidyCircuit(JFrame frame, CanvasPanel canvas) {
        try {
            TidyCircuitService.TidyResult result = TidyCircuitService.tidy(
                    canvas.schematic(), GateLibrary.load().all());
            canvas.replaceWithTidiedCircuit(result.schematic());
            String message = "Tidy complete. " + result.verificationMessage()
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

    static JMenu createSimulateMenu(CanvasPanel canvas) {
        JMenu simulate = menuWithDisabledItems("Simulate", "Tick Once", "Ticks Enabled");
        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(event -> canvas.resetInputs());
        simulate.insert(reset, 0);
        simulate.insertSeparator(1);

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
        if (!confirmUnsavedChanges(frame, canvas, document)) {
            return;
        }
        Schematic fresh = new Schematic(GateLibrary.load().all());
        document.file = null;
        document.savedModificationCount = fresh.modificationCount();
        canvas.replaceSchematic(fresh);
    }

    private static void openDocument(
            JFrame frame, CanvasPanel canvas, DocumentState document) {
        JFileChooser chooser = schematicChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path selected = chooser.getSelectedFile().toPath();
        final Schematic loaded;
        try {
            String json = Files.readString(selected);
            loaded = SchematicJson.fromJson(json, GateLibrary.load().all());
        } catch (IOException | IllegalArgumentException exception) {
            showFileError(frame, "Could not open schematic", exception);
            return;
        }

        if (!confirmUnsavedChanges(frame, canvas, document)) {
            return;
        }
        document.file = selected;
        document.savedModificationCount = loaded.modificationCount();
        canvas.replaceSchematic(loaded);
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
            frame.dispose();
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

    private static final class DocumentState {
        private Path file;
        private long savedModificationCount;
        private Schematic observedSchematic;
        private Runnable titleListener;
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

        for (String family : GateLibrary.load().families()) {
            ComponentTreeItem item = new ComponentTreeItem(
                    family, treeLabel(family));
            if (family.equals("input") || family.equals("output")) {
                pins.add(new DefaultMutableTreeNode(item));
            } else {
                gates.add(new DefaultMutableTreeNode(item));
            }
        }
        root.add(gates);
        root.add(pins);

        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setDragEnabled(true);
        tree.expandPath(new TreePath(root.getPath()));
        tree.expandPath(new TreePath(gates.getPath()));
        tree.expandPath(new TreePath(pins.getPath()));
        tree.addTreeSelectionListener(event -> {
            TreePath path = tree.getSelectionPath();
            enterAddModeForPath(canvas, path);
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                enterAddModeForPath(canvas, tree.getPathForLocation(event.getX(), event.getY()));
            }
        });
        tree.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(javax.swing.JComponent component) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(javax.swing.JComponent component) {
                TreePath path = tree.getSelectionPath();
                if (path == null) {
                    return null;
                }
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
            return columnIndex == 0 ? row.label() : row.selected();
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1 && !rows.get(rowIndex).options().isEmpty();
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 1 || !(value instanceof CanvasPanel.AttributeOption option)) {
                return;
            }
            CanvasPanel.AttributeRow row = rows.get(rowIndex);
            canvas.setSelectedAttribute(row.key(), option.value());
            SwingUtilities.invokeLater(canvas::requestFocusInWindow);
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
