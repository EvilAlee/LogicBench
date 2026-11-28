package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicJson;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.Schematic;

import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dialog actions for portable component files and the user's component folder. */
final class ComponentLibraryFileActions {
    private ComponentLibraryFileActions() { }

    static void importFiles(JFrame owner, CanvasPanel canvas) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import component file(s)");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("LogicBench component (*.lbcomp)", "lbcomp"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        List<ComponentDefinition> definitions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        File[] selectedFiles = chooser.getSelectedFiles();
        if (selectedFiles.length == 0 && chooser.getSelectedFile() != null) {
            selectedFiles = new File[] { chooser.getSelectedFile() };
        }
        for (File file : selectedFiles) {
            try {
                if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".lbcomp")) {
                    errors.add("Skipped " + file.getName() + ": expected a .lbcomp file");
                    continue;
                }
                definitions.add(ComponentLibrary.readDefinition(
                        file.toPath(), GateLibrary.load().all()));
            } catch (IOException | IllegalArgumentException exception) {
                errors.add(file.getName() + ": " + message(exception));
            }
        }
        importDefinitions(owner, canvas, definitions, errors);
    }

    static void loadLibraryFile(JFrame owner, CanvasPanel canvas) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Load component or circuit file");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Component or circuit (*.lbcomp, *.lbc)", "lbcomp", "lbc"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (filename.endsWith(".lbcomp")) {
                importDefinitions(owner, canvas,
                        List.of(ComponentLibrary.readDefinition(file, GateLibrary.load().all())),
                        new ArrayList<>());
                return;
            }
            if (!filename.endsWith(".lbc")) {
                throw new IOException("Choose a .lbcomp or .lbc file.");
            }
            Schematic circuit = SchematicJson.fromJsonWithNotices(
                    text, GateLibrary.load().all(), canvas.componentLibrary()).schematic();
            List<ComponentDefinition> embedded = circuit.componentDefinitions().values().stream()
                    .sorted(java.util.Comparator.comparing(ComponentDefinition::name)).toList();
            if (embedded.isEmpty()) {
                JOptionPane.showMessageDialog(owner,
                        "This circuit does not contain embedded component definitions.",
                        "Load library file", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JPanel choices = new JPanel(new GridLayout(0, 1, 4, 4));
            List<JCheckBox> checks = new ArrayList<>();
            for (ComponentDefinition definition : embedded) {
                JCheckBox check = new JCheckBox(
                        definition.name() + " — " + definition.description(), true);
                checks.add(check);
                choices.add(check);
            }
            int answer = JOptionPane.showConfirmDialog(owner,
                    new JScrollPane(choices),
                    "Import embedded definitions from " + file.getFileName(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            List<ComponentDefinition> selected = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                if (checks.get(i).isSelected()) selected.add(embedded.get(i));
            }
            importDefinitions(owner, canvas, selected, new ArrayList<>());
        } catch (IOException | IllegalArgumentException exception) {
            showError(owner, "Load library file", exception);
        }
    }

    private static void importDefinitions(
            JFrame owner, CanvasPanel canvas,
            List<ComponentDefinition> definitions, List<String> messages) {
        boolean changed = false;
        for (ComponentDefinition definition : definitions) {
            try {
                ComponentLibrary.ImportResult result = canvas.componentLibrary().importDefinition(
                        definition, (existing, incoming) -> {
                            Object[] options = {"Replace", "Keep both", "Skip"};
                            int selected = JOptionPane.showOptionDialog(
                                    owner,
                                    "A different component named " + incoming.name()
                                            + " already exists.\nExisting hash: "
                                            + existing.hash().substring(0, 12)
                                            + "\nImported hash: " + incoming.hash().substring(0, 12),
                                    "Component name conflict",
                                    JOptionPane.DEFAULT_OPTION,
                                    JOptionPane.WARNING_MESSAGE,
                                    null, options, options[2]);
                            return switch (selected) {
                                case 0 -> ComponentLibrary.ImportChoice.REPLACE;
                                case 1 -> ComponentLibrary.ImportChoice.KEEP_BOTH;
                                default -> ComponentLibrary.ImportChoice.SKIP;
                            };
                        });
                switch (result.action()) {
                    case ADDED -> { messages.add("Imported " + result.definition().name()); changed = true; }
                    case REPLACED -> { messages.add("Replaced " + result.definition().name()); changed = true; }
                    case KEPT_BOTH -> { messages.add("Imported as " + result.definition().name()); changed = true; }
                    case SKIPPED -> messages.add("Skipped " + definition.name()
                            + (canvas.componentLibrary().builtInNames().contains(definition.name())
                            ? ": built-in names cannot be replaced"
                            : ": identical or skipped after conflict"));
                }
            } catch (IOException | IllegalArgumentException exception) {
                messages.add(definition.name() + ": " + message(exception));
            }
        }
        if (changed) canvas.notifyComponentLibraryChanged();
        if (!messages.isEmpty()) {
            canvas.setStatusMessage(messages.getLast());
            JOptionPane.showMessageDialog(owner, String.join("\n", messages),
                    "Component library", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static void exportComponent(JFrame owner, CanvasPanel canvas) {
        List<ComponentDefinition> definitions = canvas.componentLibrary().all();
        if (definitions.isEmpty()) return;
        String[] names = definitions.stream().map(ComponentDefinition::name).toArray(String[]::new);
        Object selected = JOptionPane.showInputDialog(owner, "Choose a component to export:",
                "Export component", JOptionPane.PLAIN_MESSAGE, null, names, names[0]);
        if (selected == null) return;
        ComponentDefinition definition = canvas.componentLibrary().get(selected.toString());
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export " + definition.name());
        chooser.setSelectedFile(new File(definition.name() + ".lbcomp"));
        chooser.setFileFilter(new FileNameExtensionFilter("LogicBench component (*.lbcomp)", "lbcomp"));
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lbcomp")) {
            target = target.resolveSibling(target.getFileName() + ".lbcomp");
        }
        try {
            ComponentLibrary.exportDefinition(definition, target);
            canvas.setStatusMessage("Exported " + definition.name() + " to " + target);
        } catch (IOException exception) {
            showError(owner, "Export component", exception);
        }
    }

    static void revealFolder(JFrame owner, CanvasPanel canvas) {
        try {
            Path folder = canvas.componentLibrary().userDirectory();
            Files.createDirectories(folder);
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("The desktop file manager is not available on this system.");
            }
            Desktop.getDesktop().open(folder.toFile());
            canvas.setStatusMessage("Opened component library folder " + folder);
        } catch (IOException | UnsupportedOperationException exception) {
            showError(owner, "Reveal library folder", exception);
        }
    }

    static void refresh(JFrame owner, CanvasPanel canvas) {
        List<String> notices = canvas.componentLibrary().refresh();
        canvas.notifyComponentLibraryChanged();
        if (notices.isEmpty()) {
            canvas.setStatusMessage("Component library refreshed.");
        } else {
            canvas.setStatusMessage(notices.getLast());
            JOptionPane.showMessageDialog(owner, String.join("\n", notices),
                    "Component library refresh", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    static void deleteComponent(JFrame owner, CanvasPanel canvas, String name) {
        List<String> dependents = canvas.componentLibrary().dependentsOf(name, canvas.schematic());
        StringBuilder prompt = new StringBuilder("Delete ")
                .append(name).append(" from My components?\n\n")
                .append("Only its .lbcomp file is deleted. Open documents keep their embedded copies.");
        if (!dependents.isEmpty()) {
            prompt.append("\n\nCurrently used by:\n");
            dependents.forEach(dependent -> prompt.append("• ").append(dependent).append("\n"));
            prompt.append("\nDeleting the library file will not alter those documents.");
        }
        int answer = JOptionPane.showConfirmDialog(owner, prompt.toString(),
                dependents.isEmpty() ? "Delete component" : "Component is in use",
                JOptionPane.YES_NO_OPTION, dependents.isEmpty()
                        ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) return;
        try {
            canvas.componentLibrary().delete(name);
            canvas.notifyComponentLibraryChanged();
            canvas.setStatusMessage("Deleted component file for " + name);
        } catch (IOException | IllegalArgumentException exception) {
            showError(owner, "Delete component", exception);
        }
    }

    private static void showError(JFrame owner, String title, Exception exception) {
        JOptionPane.showMessageDialog(owner, message(exception), title, JOptionPane.ERROR_MESSAGE);
    }

    private static String message(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
