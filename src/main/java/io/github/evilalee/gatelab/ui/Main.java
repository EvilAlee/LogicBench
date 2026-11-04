package io.github.evilalee.gatelab.ui;

import com.formdev.flatlaf.FlatLightLaf;

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
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;

/** Swing application entry point for the LogicBench desktop window. */
public final class Main {
    private static final int WINDOW_WIDTH = 1200;
    private static final int WINDOW_HEIGHT = 800;

    private Main() {
    }

    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(Main::showWindow);
    }

    private static void showWindow() {
        FlatLightLaf.setup();
        UIManager.put("Component.arc", 0);
        UIManager.put("Button.arc", 0);
        UIManager.put("TextComponent.arc", 0);

        JFrame frame = createFrame();
        frame.setVisible(true);
    }

    static JFrame createFrame() {
        JFrame frame = new JFrame("LogicBench");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(createMenuBar());
        frame.setContentPane(createContent());
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setLocationRelativeTo(null);
        return frame;
    }

    private static Container createContent() {
        javax.swing.JPanel content = new javax.swing.JPanel(new BorderLayout());
        content.add(createToolbar(), BorderLayout.NORTH);

        CanvasPanel canvas = new CanvasPanel();
        JSplitPane leftColumn = createLeftColumn();
        JSplitPane workspace = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, leftColumn, canvas);
        workspace.setContinuousLayout(true);
        workspace.setOneTouchExpandable(false);
        workspace.setResizeWeight(0.0);
        workspace.setDividerLocation(265);
        workspace.setBorder(BorderFactory.createEmptyBorder());
        content.add(workspace, BorderLayout.CENTER);
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

    private static JToolBar createToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setRollover(false);
        toolbar.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, new Color(205, 209, 214)));

        JToggleButton edit = new JToggleButton("Edit");
        JToggleButton poke = new JToggleButton("Poke");
        edit.setMargin(new Insets(4, 12, 4, 12));
        poke.setMargin(new Insets(4, 12, 4, 12));
        edit.setFocusable(false);
        poke.setFocusable(false);

        ButtonGroup tools = new ButtonGroup();
        tools.add(edit);
        tools.add(poke);
        edit.setSelected(true);

        toolbar.add(edit);
        toolbar.add(poke);
        return toolbar;
    }

    private static JSplitPane createLeftColumn() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Components");
        DefaultMutableTreeNode gates = new DefaultMutableTreeNode("Gates");
        for (String gate : new String[]{
                "AND", "OR", "NOT", "NAND", "NOR", "XOR", "XNOR", "Buffer"}) {
            gates.add(new DefaultMutableTreeNode(gate));
        }
        DefaultMutableTreeNode pins = new DefaultMutableTreeNode("Pins");
        pins.add(new DefaultMutableTreeNode("Input"));
        pins.add(new DefaultMutableTreeNode("Output"));
        root.add(gates);
        root.add(pins);

        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.expandPath(new TreePath(root.getPath()));
        tree.expandPath(new TreePath(gates.getPath()));
        tree.expandPath(new TreePath(pins.getPath()));

        JScrollPane explorer = new JScrollPane(tree);
        explorer.setBorder(BorderFactory.createTitledBorder("Component Explorer"));

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
}
