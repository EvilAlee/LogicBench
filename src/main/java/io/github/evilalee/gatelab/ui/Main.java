package io.github.evilalee.gatelab.ui;

import com.formdev.flatlaf.FlatLightLaf;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.PlacedComponent;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.Arrays;

/** Swing application entry point for the LogicBench desktop window. */
public final class Main {
    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 800;

    private Main() {
    }

    public static void main(String[] args) {
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
        JFrame frame = new JFrame("LogicBench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(createMenuBar());
        frame.setContentPane(createContent(galleryMode));
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setLocationRelativeTo(null);
        return frame;
    }

    private static Container createContent(boolean galleryMode) {
        javax.swing.JPanel content = new javax.swing.JPanel(new BorderLayout());

        CanvasPanel canvas = new CanvasPanel(galleryMode);
        JToggleButton edit = new JToggleButton("Edit");
        JToggleButton poke = new JToggleButton("Poke");
        content.add(createToolbar(canvas, edit, poke), BorderLayout.NORTH);

        DefaultTableModel attributesModel = new DefaultTableModel(
                new Object[]{"Attribute", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable attributes = new JTable(attributesModel);
        attributes.setFillsViewportHeight(true);
        attributes.setRowHeight(22);
        attributes.getTableHeader().setReorderingAllowed(false);
        canvas.setSelectionListener(selection -> {
            attributesModel.setRowCount(0);
            if (selection.size() == 1) {
                PlacedComponent component = selection.getFirst();
                attributesModel.addRow(new Object[]{"Type", component.typeId()});
                attributesModel.addRow(new Object[]{"Facing", component.facing().name()});
            }
        });

        JSplitPane leftColumn = createLeftColumn(canvas, attributes);
        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftColumn, canvas);
        workspace.setContinuousLayout(true);
        workspace.setOneTouchExpandable(false);
        workspace.setResizeWeight(0.0);
        workspace.setDividerLocation(265);
        workspace.setBorder(BorderFactory.createEmptyBorder());
        content.add(workspace, BorderLayout.CENTER);

        canvas.setToolModeListener(tool -> {
            edit.setSelected(tool != CanvasPanel.Tool.POKE);
            poke.setSelected(tool == CanvasPanel.Tool.POKE);
        });
        return content;
    }

    private static JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(menuWithDisabledItems("File", "New", "Open...", "Save", "Save As..."));
        JMenu fileMenu = menuBar.getMenu(0);
        fileMenu.addSeparator();
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(event -> System.exit(0));
        fileMenu.add(exit);

        menuBar.add(menuWithDisabledItems("Edit", "Undo", "Redo", "Cut", "Copy", "Paste"));
        menuBar.add(menuWithDisabledItems("Project", "Project Options..."));
        menuBar.add(menuWithDisabledItems("Simulate", "Tick Once", "Ticks Enabled"));
        menuBar.add(menuWithDisabledItems("Window", "Reset Window Layout"));
        menuBar.add(menuWithDisabledItems("Help", "User Guide", "About LogicBench"));
        return menuBar;
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

        for (var definition : GateLibrary.load().all()) {
            ComponentTreeItem item = new ComponentTreeItem(
                    definition.id(), treeLabel(definition.id()));
            if (definition.id().equals("input") || definition.id().equals("output")) {
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
            if (path == null) {
                return;
            }
            Object node = path.getLastPathComponent();
            if (node instanceof DefaultMutableTreeNode treeNode
                    && treeNode.getUserObject() instanceof ComponentTreeItem item) {
                canvas.enterAddMode(item.typeId());
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

    private static String treeLabel(String id) {
        return switch (id) {
            case "and", "or", "not", "nand", "nor", "xor", "xnor" -> id.toUpperCase();
            case "buffer" -> "Buffer";
            case "input" -> "Input";
            case "output" -> "Output";
            default -> id;
        };
    }

    private record ComponentTreeItem(String typeId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
