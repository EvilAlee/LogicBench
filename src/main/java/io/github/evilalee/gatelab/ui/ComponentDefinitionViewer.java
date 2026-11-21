package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.ComponentDefinition;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Dialog;

/** Read-only schematic window for a reusable component definition. */
final class ComponentDefinitionViewer {
    private ComponentDefinitionViewer() {
    }

    static void showDialog(JFrame owner, ComponentDefinition definition) {
        JDialog dialog = new JDialog(
                owner, "Definition: " + definition.name(), Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel title = new JLabel(
                definition.name() + "  —  Read-only definition",
                SwingConstants.CENTER);
        title.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));

        String description = definition.description();
        JLabel subtitle = new JLabel(description, SwingConstants.CENTER);
        subtitle.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);

        CanvasPanel canvas = new CanvasPanel();
        canvas.setReadOnly(true);
        canvas.setComponentDefinitionViewListener(
                nested -> showDialog(owner, nested));
        canvas.replaceSchematic(definition.internalCircuit());

        JPanel content = new JPanel(new BorderLayout());
        content.add(header, BorderLayout.NORTH);
        content.add(canvas, BorderLayout.CENTER);
        JLabel footer = new JLabel(
                "This definition is read-only. Double-click a nested component to view it.",
                SwingConstants.CENTER);
        footer.setBorder(BorderFactory.createEmptyBorder(7, 8, 7, 8));
        content.add(footer, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.setMinimumSize(new Dimension(720, 500));
        dialog.setSize(960, 680);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        SwingUtilities.invokeLater(canvas::fitToView);
    }
}
