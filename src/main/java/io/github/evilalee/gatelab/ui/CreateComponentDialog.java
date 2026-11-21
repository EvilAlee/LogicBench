package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.ComponentDefinitionBuilder;
import io.github.evilalee.gatelab.model.ComponentDefinitionBuilder.PortSelection;
import io.github.evilalee.gatelab.model.PlacedComponent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Modal UI for turning named schematic I/O components into a reusable definition. */
final class CreateComponentDialog {
    private CreateComponentDialog() {
    }

    static void showDialog(JFrame owner, CanvasPanel canvas) {
        List<PortRow> rows = collectPortRows(canvas);
        boolean hasInput = rows.stream().anyMatch(row ->
                row.directionLabel.equals("Input") || row.directionLabel.equals("Clock"));
        boolean hasOutput = rows.stream().anyMatch(row ->
                row.directionLabel.equals("Output"));
        if (!hasInput || !hasOutput) {
            String missing = !hasInput && !hasOutput ? "an input and an output"
                    : !hasInput ? "an input or clock" : "an output";
            JOptionPane.showMessageDialog(owner,
                    "The current circuit must contain " + missing
                            + " before it can become a component.",
                    "Cannot create component", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog dialog = new JDialog(owner, "Create component from circuit", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField nameField = new JTextField(24);
        JTextField descriptionField = new JTextField("User-defined component", 24);
        JPanel details = new JPanel(new GridLayout(2, 2, 8, 6));
        details.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        details.add(new JLabel("Component name:"));
        details.add(nameField);
        details.add(new JLabel("Description:"));
        details.add(descriptionField);

        PortTableModel tableModel = new PortTableModel(rows);
        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(65);
        table.getColumnModel().getColumn(1).setPreferredWidth(190);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(4).setPreferredWidth(55);

        JLabel instructions = new JLabel(
                "Named ports are included by default. Unnamed ports start excluded; "
                        + "include one and enter a name to expose it.",
                SwingConstants.LEADING);
        instructions.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        JButton moveUp = new JButton("Move up");
        JButton moveDown = new JButton("Move down");
        moveUp.addActionListener(event -> moveSelected(table, tableModel, -1));
        moveDown.addActionListener(event -> moveSelected(table, tableModel, 1));
        JPanel reorder = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        reorder.add(moveUp);
        reorder.add(moveDown);

        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tablePanel.setBorder(BorderFactory.createTitledBorder("Component ports (top to bottom = public port order)"));
        tablePanel.add(instructions, BorderLayout.NORTH);
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);
        tablePanel.add(reorder, BorderLayout.SOUTH);

        JButton create = new JButton("Create component");
        JButton cancel = new JButton("Cancel");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        actions.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        actions.add(cancel);
        actions.add(create);
        cancel.addActionListener(event -> dialog.dispose());
        create.addActionListener(event -> saveDefinition(
                dialog, canvas, table, tableModel, nameField, descriptionField));

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.add(details, BorderLayout.NORTH);
        content.add(tablePanel, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(720, 470));
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static List<PortRow> collectPortRows(CanvasPanel canvas) {
        List<PortRow> rows = new ArrayList<>();
        for (PlacedComponent component : canvas.schematic().components()) {
            String direction = switch (component.typeId()) {
                case "input" -> "Input";
                case "clock" -> "Clock";
                case "output" -> "Output";
                default -> null;
            };
            if (direction == null) {
                continue;
            }
            String componentText = direction + " (" + component.id() + ")";
            rows.add(new PortRow(
                    component.id(), componentText, component.label(), direction,
                    !component.label().isBlank()));
        }
        return rows;
    }

    private static void moveSelected(JTable table, PortTableModel model, int delta) {
        int row = table.getSelectedRow();
        int target = row + delta;
        if (row < 0 || target < 0 || target >= model.getRowCount()) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        model.move(row, target);
        table.setRowSelectionInterval(target, target);
    }

    private static void saveDefinition(
            JDialog dialog,
            CanvasPanel canvas,
            JTable table,
            PortTableModel model,
            JTextField nameField,
            JTextField descriptionField) {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            return;
        }
        try {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("component name must not be empty");
            }
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "component name must start with a letter and contain only letters, digits and underscores");
            }
            if (canvas.componentLibrary().builtInNames().contains(name)) {
                throw new IllegalArgumentException(
                        "component name is reserved by a built-in definition: " + name);
            }
            if (canvas.componentLibrary().find(name).isPresent()) {
                throw new IllegalArgumentException(
                        "a component named " + name + " already exists in the library");
            }

            List<PortSelection> selections = model.portSelections();
            var definition = ComponentDefinitionBuilder.create(
                    name,
                    descriptionField.getText().isBlank()
                            ? "User-defined component" : descriptionField.getText().trim(),
                    "Custom",
                    selections,
                    canvas.schematic());
            canvas.componentLibrary().save(definition);
            canvas.notifyComponentLibraryChanged();
            canvas.setStatusMessage("Saved component definition " + definition.name());
            dialog.dispose();
        } catch (IOException | IllegalArgumentException exception) {
            String message = exception.getMessage() == null
                    ? "The component definition could not be saved." : exception.getMessage();
            JOptionPane.showMessageDialog(
                    dialog, message, "Cannot create component", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static final class PortRow {
        private final String componentId;
        private final String componentText;
        private final String directionLabel;
        private boolean included;
        private String name;

        private PortRow(
                String componentId, String componentText, String name,
                String directionLabel, boolean included) {
            this.componentId = componentId;
            this.componentText = componentText;
            this.name = name;
            this.directionLabel = directionLabel;
            this.included = included;
        }
    }

    private static final class PortTableModel extends AbstractTableModel {
        private final List<PortRow> rows;

        private PortTableModel(List<PortRow> rows) {
            this.rows = new ArrayList<>(rows);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Include";
                case 1 -> "Source component";
                case 2 -> "Port name";
                case 3 -> "Direction";
                case 4 -> "Order";
                default -> throw new IndexOutOfBoundsException("column " + column);
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return switch (column) {
                case 0 -> Boolean.class;
                case 4 -> Integer.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PortRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.included;
                case 1 -> row.componentText;
                case 2 -> row.name;
                case 3 -> row.directionLabel;
                case 4 -> rowIndex + 1;
                default -> throw new IndexOutOfBoundsException("column " + columnIndex);
            };
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0
                    || (columnIndex == 2 && rows.get(rowIndex).included);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            PortRow row = rows.get(rowIndex);
            if (columnIndex == 0) {
                row.included = Boolean.TRUE.equals(value);
                fireTableRowsUpdated(rowIndex, rowIndex);
            } else if (columnIndex == 2) {
                row.name = value == null ? "" : value.toString().trim();
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }

        private void move(int from, int to) {
            PortRow item = rows.remove(from);
            rows.add(to, item);
            fireTableDataChanged();
        }

        private List<PortSelection> portSelections() {
            return rows.stream().map(row ->
                    new PortSelection(row.componentId, row.included, row.name)).toList();
        }
    }
}
