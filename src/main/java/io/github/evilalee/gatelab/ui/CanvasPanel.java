package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.ComponentAttributeRegistry;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicHistory;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.WireSegment;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.ClockStepper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.TransferHandler;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Grid canvas for the M4 schematic editor, with an optional M3 SVG gallery. */
public final class CanvasPanel extends JPanel {
    private static final int WORLD_GRID_SPACING = PlacedComponent.GRID_SPACING;
    private static final double MIN_SCREEN_GRID_SPACING = 4.0;
    private static final double ZOOM_PER_WHEEL_NOTCH = 1.12;
    private static final double DRAG_THRESHOLD_SQUARED = 9.0;

    private static final int GALLERY_FIRST_ORIGIN_X = 90;
    private static final int GALLERY_HEADER_BOTTOM = 40;
    private static final int GALLERY_ROW_MARGIN = 20;
    private static final int GALLERY_COLUMN_SPACING = 165;
    private static final int DIRTY_REGION_MARGIN = 6;
    private static final long PAINT_STATS_INTERVAL_NANOS = 1_000_000_000L;
    private static final boolean PAINT_STATS_ENABLED =
            Boolean.getBoolean("logicbench.paintStats");

    public enum Tool {
        EDIT,
        POKE,
        ADD
    }

    public record AttributeOption(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public record AttributeRow(
            String key,
            String label,
            AttributeOption selected,
            List<AttributeOption> options) {
        public AttributeRow {
            options = List.copyOf(options);
        }
    }

    public record AttributePanelState(String title, List<AttributeRow> rows) {
        public AttributePanelState {
            rows = List.copyOf(rows);
        }

        public static AttributePanelState empty() {
            return new AttributePanelState("", List.of());
        }
    }

    private enum Gesture {
        NONE,
        ADD_CLICK,
        PENDING_WIRE_START,
        DRAW_WIRE,
        MOVE_SELECTION,
        SELECT_RECTANGLE
    }

    private final ViewTransform transform = new ViewTransform(1.0, 24.0, 24.0);
    private final boolean galleryMode;
    private final GateLibrary gateLibrary;
    private final GateRenderer gateRenderer;
    private Schematic schematic;
    private final SchematicHistory history = new SchematicHistory();
    private Runnable historyStateListener = () -> { };
    private final Runnable schematicSimulationListener = this::schematicChanged;
    private Consumer<Schematic> schematicReplacementListener = ignored -> { };
    private final List<Consumer<Schematic>> schematicListeners = new ArrayList<>();
    private Runnable signalHistoryClearListener = () -> { };
    private Runnable signalSampleListener = () -> { };
    private SimulationResult simulationResult;
    private final ClockStepper clockStepper = new ClockStepper();
    private long simulationRecomputeCount;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private final Set<WireSegment> selectedWires = new LinkedHashSet<>();

    private final int galleryFirstOriginY;
    private final int galleryRowPitch;
    private BufferedImage gridImage;
    private int gridCacheWidth = -1;
    private int gridCacheHeight = -1;
    private long gridCacheScaleBits = Long.MIN_VALUE;
    private long gridCacheTranslateXBits = Long.MIN_VALUE;
    private long gridCacheTranslateYBits = Long.MIN_VALUE;
    private long gridCacheRebuildCount;
    private long paintStatsWindowStartNanos;
    private long paintStatsPaintCount;
    private long paintStatsTotalNanos;
    private long paintStatsMinNanos = Long.MAX_VALUE;
    private long paintStatsMaxNanos;
    private boolean showPinMarkers = true;
    private boolean spaceDown;
    private boolean panning;
    private Point lastPanPoint;

    private Tool activeTool = Tool.EDIT;
    private boolean showGateNames = true;
    private String previewTypeId;
    private Facing previewFacing = Facing.EAST;
    private int previewX;
    private int previewY;
    private Consumer<List<PlacedComponent>> selectionListener = ignored -> { };
    private Consumer<Tool> toolModeListener = ignored -> { };
    private Consumer<String> statusMessageListener = ignored -> { };
    private Runnable attributeStateListener = () -> { };
    private Runnable nameEditorFocusListener = () -> { };
    private final Map<String, Map<String, String>> toolAttributesByFamily = new HashMap<>();
    private final Map<String, Facing> toolFacingByFamily = new HashMap<>();

    private Gesture gesture = Gesture.NONE;
    private Point gestureStartScreen;
    private double gestureStartWorldX;
    private double gestureStartWorldY;
    private double pointerWorldX;
    private double pointerWorldY;
    private boolean gestureMoved;
    private boolean additiveMarquee;
    private Map<String, PlacedComponent> moveStartComponents = Map.of();
    private Set<WireSegment> moveStartWires = Set.of();
    private Connectivity.Point wireStartPoint;
    private WireSegment pendingWireHit;
    private boolean wireAxisChosen;
    private boolean wireHorizontalFirst;

    public CanvasPanel() {
        this(false);
    }

    public CanvasPanel(boolean galleryMode) {
        this.galleryMode = galleryMode;
        this.gateLibrary = GateLibrary.load();
        this.gateRenderer = new GateRenderer(gateLibrary);
        this.schematic = new Schematic(gateLibrary.all());
        this.schematic.addChangeListener(schematicSimulationListener);
        recomputeSimulation();
        this.galleryFirstOriginY = galleryMode ? galleryFirstOriginY(gateLibrary.all()) : 0;
        this.galleryRowPitch = galleryMode ? galleryRowPitch(gateLibrary.all()) : 0;

        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        setPreferredSize(new Dimension(900, 700));
        setToolTipText(galleryMode
                ? "Middle-drag or Space + drag to pan; Ctrl + wheel to zoom; G toggles pin markers"
                : "Click a component in the explorer to place; arrows set facing; Esc returns to Edit; "
                        + "Middle-drag or Space + drag to pan; Ctrl + wheel to zoom");

        installSpaceBinding();
        installGalleryKeyBinding();
        installInteractionBindings();
        installMouseControls();
        installDropHandler();
    }

    public ViewTransform viewTransform() {
        return transform;
    }

    public boolean isGalleryMode() {
        return galleryMode;
    }

    public boolean arePinMarkersVisible() {
        return showPinMarkers;
    }

    public Schematic schematic() {
        return schematic;
    }

    /** Receives the current and every subsequently installed schematic. */
    public void setSchematicListener(Consumer<Schematic> listener) {
        schematicReplacementListener = listener == null ? ignored -> { } : listener;
        schematicReplacementListener.accept(schematic);
    }

    /** Registers an additional observer for the current and subsequently installed documents. */
    public void addSchematicListener(Consumer<Schematic> listener) {
        Consumer<Schematic> registered = java.util.Objects.requireNonNull(listener, "listener");
        if (!schematicListeners.contains(registered)) {
            schematicListeners.add(registered);
        }
        registered.accept(schematic);
    }

    private void notifySchematicReplaced() {
        schematicReplacementListener.accept(schematic);
        for (Consumer<Schematic> listener : List.copyOf(schematicListeners)) {
            listener.accept(schematic);
        }
    }

    /** Installs a fully validated document and resets all transient view/editor state. */
    public void replaceSchematic(Schematic replacement) {
        if (galleryMode) {
            throw new IllegalStateException("the gallery does not own an editable schematic");
        }
        Schematic next = java.util.Objects.requireNonNull(replacement, "replacement");
        schematic.removeChangeListener(schematicSimulationListener);
        history.clear();
        schematic = next;
        schematic.addChangeListener(schematicSimulationListener);

        selectedIds.clear();
        selectedWires.clear();
        previewTypeId = null;
        activeTool = Tool.EDIT;
        panning = false;
        lastPanPoint = null;
        resetGesture();
        transform.resetToDefault();
        simulationResult = null;
        clockStepper.resetStepCount();
        notifySelectionChanged();
        toolModeListener.accept(activeTool);
        notifySchematicReplaced();
        notifyHistoryStateChanged();
        recomputeSimulation();
        statusMessageListener.accept(simulationStatusText());
        recordSignalSample();
    }

    public SimulationResult simulationResult() { return simulationResult; }

    /** Installs editor-owned callbacks for clearing and appending waveform history. */
    public void setSignalRecorderCallbacks(Runnable clearHistory, Runnable recordSample) {
        signalHistoryClearListener = clearHistory == null ? () -> { } : clearHistory;
        signalSampleListener = recordSample == null ? () -> { } : recordSample;
    }

    /** Clears transient waveform history without changing the schematic or simulation. */
    public void clearSignalHistory() {
        signalHistoryClearListener.run();
    }

    private void recordSignalSample() {
        signalSampleListener.run();
    }

    /** Advances every Clock once while preserving the previously settled gate outputs. */
    public void stepClock() {
        if (galleryMode) {
            return;
        }
        schematic.removeChangeListener(schematicSimulationListener);
        try {
            simulationResult = clockStepper.step(schematic, simulationResult);
        } finally {
            schematic.addChangeListener(schematicSimulationListener);
        }
        simulationRecomputeCount++;
        repaint();
        statusMessageListener.accept(simulationStatusText());
        recordSignalSample();
    }

    /** Resets input and Clock sources, the step counter, and all prior gate state. */
    public void resetSimulation() {
        if (galleryMode) {
            return;
        }
        clearSignalHistory();
        schematic.removeChangeListener(schematicSimulationListener);
        try {
            simulationResult = clockStepper.reset(schematic);
        } finally {
            schematic.addChangeListener(schematicSimulationListener);
        }
        simulationRecomputeCount++;
        repaint();
        statusMessageListener.accept(simulationStatusText());
        recordSignalSample();
    }

    public String simulationStatusText() {
        long steps = clockStepper.stepCount();
        double cycles = steps / 2.0;
        String cycleText = cycles == Math.rint(cycles)
                ? Long.toString((long) cycles) : Double.toString(cycles);
        return "Step " + steps + ", cycle " + cycleText;
    }

    public void setShowGateNames(boolean show) {
        if (showGateNames != show) {
            showGateNames = show;
            repaint();
        }
    }

    public boolean showGateNames() {
        return showGateNames;
    }

    /**
     * Replaces the current topology as one history gesture, retaining the active
     * Schematic object so document listeners and the current save path stay valid.
     */
    public void replaceWithGeneratedCircuit(Schematic generated) {
        replaceWithCircuit(generated, false);
    }

    /** Replaces topology as one undo step while retaining the tidy result's switch values. */
    public void replaceWithTidiedCircuit(Schematic tidied) {
        replaceWithCircuit(tidied, true);
    }

    private void replaceWithCircuit(Schematic replacement, boolean preserveInputValues) {
        if (galleryMode) {
            throw new IllegalStateException("the gallery does not own an editable schematic");
        }
        Schematic next = java.util.Objects.requireNonNull(replacement, "replacement");
        history.begin(schematic);
        schematic.restoreSnapshot(SchematicSnapshot.capture(next));
        for (PlacedComponent component : next.components()) {
            if ("input".equals(component.typeId())) {
                boolean high = preserveInputValues
                        ? next.inputValue(component.id())
                        : component.label().equals("1");
                schematic.setInputValue(component.id(), high);
            } else if (preserveInputValues && "clock".equals(component.typeId())) {
                schematic.setClockValue(component.id(), next.clockValue(component.id()));
            }
        }
        if (!history.commit(schematic)) {
            history.cancelPending();
        }

        selectedIds.clear();
        selectedWires.clear();
        previewTypeId = null;
        activeTool = Tool.EDIT;
        panning = false;
        lastPanPoint = null;
        resetGesture();
        toolModeListener.accept(activeTool);
        notifySelectionChanged();
        notifyAttributeStateChanged();
        notifyHistoryStateChanged();
        fitToView();
        recomputeSimulation();
        requestFocusInWindow();
    }

    /** Fits every placed component and wire endpoint into the visible canvas. */
    public void fitToView() {
        if (galleryMode || getWidth() <= 0 || getHeight() <= 0) {
            transform.resetToDefault();
            repaint();
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds bounds = GateGeometry.bounds(
                    schematic.definitionFor(component), component.facing());
            minX = Math.min(minX, component.x() + bounds.x());
            minY = Math.min(minY, component.y() + bounds.y());
            maxX = Math.max(maxX, component.x() + bounds.x() + bounds.width());
            maxY = Math.max(maxY, component.y() + bounds.y() + bounds.height());
        }
        for (WireSegment wire : schematic.wires()) {
            minX = Math.min(minX, Math.min(wire.x1(), wire.x2()));
            minY = Math.min(minY, Math.min(wire.y1(), wire.y2()));
            maxX = Math.max(maxX, Math.max(wire.x1(), wire.x2()));
            maxY = Math.max(maxY, Math.max(wire.y1(), wire.y2()));
        }
        if (!Double.isFinite(minX)) {
            transform.resetToDefault();
        } else {
            transform.fitToBounds(
                    minX, minY, maxX, maxY, getWidth(), getHeight(), 36.0);
        }
        gridImage = null;
        repaint();
    }

    public boolean canUndo() {
        return history.canUndo();
    }

    public boolean canRedo() {
        return history.canRedo();
    }

    public void setHistoryStateListener(Runnable listener) {
        historyStateListener = listener == null ? () -> { } : listener;
        notifyHistoryStateChanged();
    }

    public boolean undo() {
        if (!history.undo(schematic)) {
            notifyHistoryStateChanged();
            return false;
        }
        clearSelectionAfterHistoryChange();
        return true;
    }

    public boolean redo() {
        if (!history.redo(schematic)) {
            notifyHistoryStateChanged();
            return false;
        }
        clearSelectionAfterHistoryChange();
        return true;
    }

    private void clearSelectionAfterHistoryChange() {
        selectedIds.clear();
        selectedWires.clear();
        resetGesture();
        notifySelectionChanged();
        notifyHistoryStateChanged();
        // restoreSnapshot notifies the simulation listener; repaint also clears stale selection.
        repaint();
    }

    private void beginHistoryGesture() {
        history.begin(schematic);
    }

    private void commitHistoryGesture() {
        if (history.commit(schematic)) {
            notifyHistoryStateChanged();
        }
    }

    private void notifyHistoryStateChanged() {
        historyStateListener.run();
    }

    long simulationRecomputeCount() { return simulationRecomputeCount; }

    private void recomputeSimulation() {
        simulationResult = SchematicCompiler.compileAndSettle(schematic, simulationResult);
        simulationRecomputeCount++;
        repaint();
    }

    public void resetInputs() {
        if (!schematic.resetInputs()) recomputeSimulation();
    }

    public List<WireSegment> selectedWires() {
        return List.copyOf(selectedWires);
    }

    public void setSelectionListener(Consumer<List<PlacedComponent>> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
        notifySelectionChanged();
    }

    public void setToolModeListener(Consumer<Tool> listener) {
        toolModeListener = listener == null ? ignored -> { } : listener;
        toolModeListener.accept(activeTool);
    }

    public void setStatusMessageListener(Consumer<String> listener) {
        statusMessageListener = listener == null ? ignored -> { } : listener;
    }

    /** Posts a one-off result or diagnostic to the application's status bar. */
    public void setStatusMessage(String message) {
        statusMessageListener.accept(message == null ? "" : message);
    }
    public void setAttributeStateListener(Runnable listener) {
        attributeStateListener = listener == null ? () -> { } : listener;
        notifyAttributeStateChanged();
    }

    /** Receives a request to focus the Name cell after a double-click on an I/O source or probe. */
    public void setNameEditorFocusListener(Runnable listener) {
        nameEditorFocusListener = listener == null ? () -> { } : listener;
    }

    public AttributePanelState attributePanelState() {
        if (activeTool == Tool.ADD && previewTypeId != null) {
            return attributeStateForTool(previewTypeId);
        }
        if (selectedIds.size() != 1) {
            return AttributePanelState.empty();
        }
        String selectedId = selectedIds.iterator().next();
        PlacedComponent selected = schematic.components().stream()
                .filter(component -> component.id().equals(selectedId))
                .findFirst().orElse(null);
        return selected == null ? AttributePanelState.empty() : attributeStateForComponent(selected);
    }

    /** Applies an attribute edit from the attribute table or the Add-mode tool preview. */
    public boolean setSelectedAttribute(String key, String value) {
        if (activeTool == Tool.ADD && previewTypeId != null) {
            return setToolAttribute(previewTypeId, key, value);
        }
        if (selectedIds.size() != 1) {
            return false;
        }
        String id = selectedIds.iterator().next();
        try {
            beginHistoryGesture();
            if (key.equals("name")) {
                PlacedComponent selected = selectedComponents().getFirst();
                if (!isNameEditableType(selected.typeId())) {
                    history.cancelPending();
                    return false;
                }
                schematic.setLabel(id, value);
                commitHistoryGesture();
                String normalized = schematic.components().stream()
                        .filter(component -> component.id().equals(id))
                        .map(PlacedComponent::label)
                        .findFirst().orElse("");
                boolean duplicate = !normalized.isEmpty()
                        && schematic.components().stream().anyMatch(component ->
                                !component.id().equals(id)
                                        && isNameEditableType(component.typeId())
                                        && normalized.equals(component.label()));
                statusMessageListener.accept(
                        duplicate ? "Duplicate name: " + normalized : "");
                notifySelectionChanged();
                repaint();
                return true;
            }
            boolean linksFollowed = schematic.setAttribute(id, key, value);
            commitHistoryGesture();
            statusMessageListener.accept(linksFollowed ? "" : "Some wires could not follow");
            notifySelectionChanged();
            repaint();
            return true;
        } catch (IllegalArgumentException exception) {
            history.cancelPending();
            statusMessageListener.accept("Not available");
            notifyAttributeStateChanged();
            return false;
        }
    }

    private boolean setToolAttribute(String family, String key, String value) {
        try {
            String normalized = ComponentAttributeRegistry.normalizeValue(family, key, value);
            if (key.equals("facing")) {
                Facing selectedFacing = Facing.valueOf(normalized);
                toolFacingByFamily.put(family, selectedFacing);
                if (activeTool == Tool.ADD && family.equals(previewTypeId)) {
                    previewFacing = selectedFacing;
                }
            } else {
                Map<String, String> changed = new LinkedHashMap<>(
                        toolAttributesByFamily.getOrDefault(family, Map.of()));
                ComponentAttributeRegistry.AttributeSpec spec =
                        ComponentAttributeRegistry.require(family, key);
                if (normalized.equals(spec.defaultValue())) {
                    changed.remove(key);
                } else {
                    changed.put(key, normalized);
                }
                resolveToolDefinition(family, changed);
                toolAttributesByFamily.put(family, Map.copyOf(changed));
            }
            statusMessageListener.accept("");
            notifyAttributeStateChanged();
            repaint();
            return true;
        } catch (IllegalArgumentException exception) {
            statusMessageListener.accept("Not available");
            notifyAttributeStateChanged();
            return false;
        }
    }

    private GateDef resolveToolDefinition(String family, Map<String, String> attributes) {
        int inputCount = ComponentAttributeRegistry.supports(family, "inputs")
                ? Integer.parseInt(attributes.getOrDefault("inputs", "2"))
                : gateLibrary.defaultDefinition(family).inputCount();
        return gateLibrary.resolve(
                family, attributes.getOrDefault("size", "narrow"), inputCount);
    }

    private AttributePanelState attributeStateForTool(String family) {
        Facing facing = toolFacingByFamily.getOrDefault(family, Facing.EAST);
        Map<String, String> attributes = toolAttributesByFamily.getOrDefault(family, Map.of());
        return buildAttributeState(family, facing, attributes, "", false);
    }

    private AttributePanelState attributeStateForComponent(PlacedComponent component) {
        return buildAttributeState(
                component.typeId(), component.facing(), component.attributes(), component.label(), true);
    }

    private AttributePanelState buildAttributeState(
            String family, Facing facing, Map<String, String> attributes,
            String label, boolean includeName) {
        List<AttributeRow> rows = new ArrayList<>();
        for (ComponentAttributeRegistry.AttributeSpec spec
                : ComponentAttributeRegistry.forFamily(family)) {
            List<AttributeOption> options = schematic.attributeOptions(family, spec.key()).stream()
                    .map(value -> new AttributeOption(
                            value, ComponentAttributeRegistry.displayValue(spec.key(), value)))
                    .toList();
            String selectedValue = spec.key().equals("facing")
                    ? facing.name() : attributes.getOrDefault(spec.key(), spec.defaultValue());
            AttributeOption selected = options.stream()
                    .filter(option -> option.value().equals(selectedValue))
                    .findFirst()
                    .orElse(new AttributeOption(selectedValue,
                            ComponentAttributeRegistry.displayValue(spec.key(), selectedValue)));
            rows.add(new AttributeRow(spec.key(), spec.label(), selected, options));
        }
        if (includeName && isNameEditableType(family)) {
            rows.add(new AttributeRow(
                    "name", "Name", new AttributeOption(label, label), List.of()));
        }
        String title = family.isEmpty() ? "" : family.toUpperCase(Locale.ROOT);
        return new AttributePanelState(title, rows);
    }

    private static boolean isNameEditableType(String typeId) {
        return typeId.equals("input") || typeId.equals("output") || typeId.equals("clock");
    }

    private void notifyAttributeStateChanged() {
        attributeStateListener.run();
    }

    private void schematicChanged() {
        recomputeSimulation();
        notifyAttributeStateChanged();
    }


    /** Enters repeated-click placement mode for a catalogue type. */
    public void enterAddMode(String typeId) {
        if (galleryMode) {
            return;
        }
        String family = gateLibrary.get(typeId).family();
        if (activeTool == Tool.ADD && family.equals(previewTypeId)) {
            requestFocusInWindow();
            return;
        }
        previewTypeId = family;
        previewFacing = toolFacingByFamily.getOrDefault(family, Facing.EAST);
        toolAttributesByFamily.putIfAbsent(family, Map.of());
        selectedIds.clear();
        selectedWires.clear();
        activeTool = Tool.ADD;
        notifySelectionChanged();
        Point2D.Double center = transform.screenToWorld(
                Math.max(0, getWidth()) / 2.0, Math.max(0, getHeight()) / 2.0);
        updatePreviewPosition(center.x, center.y);
        resetGesture();
        toolModeListener.accept(activeTool);
        requestFocusInWindow();
        repaint();
    }

    /** Returns the canvas to the selection tool. */
    public void setEditMode() {
        Rectangle oldTransientBounds = union(
                previewScreenBounds(), gestureVisualScreenBounds());
        previewTypeId = null;
        activeTool = Tool.EDIT;
        resetGesture();
        toolModeListener.accept(activeTool);
        notifyAttributeStateChanged();
        requestFocusInWindow();
        repaintIfPresent(oldTransientBounds);
    }

    /** Selects the input-poking tool. */
    public void setPokeMode() {
        Rectangle oldTransientBounds = union(
                previewScreenBounds(), gestureVisualScreenBounds());
        previewTypeId = null;
        activeTool = Tool.POKE;
        resetGesture();
        toolModeListener.accept(activeTool);
        notifyAttributeStateChanged();
        requestFocusInWindow();
        repaintIfPresent(oldTransientBounds);
    }

    /** One-shot tree drag/drop placement, sharing the same grid-snapped origin rule. */
    public void placeFromDrop(String typeId, Point screenPoint) {
        String family = gateLibrary.get(typeId).family();
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        Facing facing = toolFacingByFamily.getOrDefault(family, Facing.EAST);
        beginHistoryGesture();
        PlacedComponent placed = placeAtWorld(
                family, world.x, world.y, facing);
        commitHistoryGesture();
        selectedIds.clear();
        selectedWires.clear();
        selectedIds.add(placed.id());
        setEditMode();
        notifySelectionChanged();
        repaint();
    }

    private void installSpaceBinding() {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false), "pan-space-down");
        getActionMap().put("pan-space-down", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                spaceDown = true;
            }
        });

        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true), "pan-space-up");
        getActionMap().put("pan-space-up", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                spaceDown = false;
            }
        });
    }

    private void installGalleryKeyBinding() {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "toggle-pin-markers");
        getActionMap().put("toggle-pin-markers", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (galleryMode) {
                    showPinMarkers = !showPinMarkers;
                    repaint();
                }
            }
        });
    }

    private void installInteractionBindings() {
        bindKey(KeyEvent.VK_ESCAPE, "return-to-edit", this::setEditMode);
        bindKey(KeyEvent.VK_DELETE, "delete-selection", this::deleteSelection);
        bindKey(KeyEvent.VK_BACK_SPACE, "backspace-delete-selection", this::deleteSelection);
        bindKeyWhenFocused(KeyEvent.VK_RIGHT, "face-east", () -> setFacing(Facing.EAST));
        bindKeyWhenFocused(KeyEvent.VK_DOWN, "face-south", () -> setFacing(Facing.SOUTH));
        bindKeyWhenFocused(KeyEvent.VK_LEFT, "face-west", () -> setFacing(Facing.WEST));
        bindKeyWhenFocused(KeyEvent.VK_UP, "face-north", () -> setFacing(Facing.NORTH));
    }

    private void bindKey(int keyCode, String actionName, Runnable action) {
        bindKey(keyCode, actionName, action, JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void bindKeyWhenFocused(int keyCode, String actionName, Runnable action) {
        bindKey(keyCode, actionName, action, JComponent.WHEN_FOCUSED);
    }

    private void bindKey(
            int keyCode, String actionName, Runnable action, int inputMapCondition) {
        getInputMap(inputMapCondition).put(
                KeyStroke.getKeyStroke(keyCode, 0), actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!galleryMode) {
                    action.run();
                }
            }
        });
    }

    private void installDropHandler() {
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return !galleryMode && support.isDrop()
                        && support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    String typeId = (String) support.getTransferable()
                            .getTransferData(DataFlavor.stringFlavor);
                    placeFromDrop(typeId, support.getDropLocation().getDropPoint());
                    return true;
                } catch (UnsupportedFlavorException | IOException | IllegalArgumentException exception) {
                    return false;
                }
            }
        });
    }

    private void installMouseControls() {
        MouseAdapter mouseControls = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)
                        && !galleryMode && activeTool == Tool.EDIT) {
                    Point2D.Double world = transform.screenToWorld(event.getX(), event.getY());
                    PlacedComponent hit = schematic.componentAt(
                            (int) Math.floor(world.x), (int) Math.floor(world.y)).orElse(null);
                    if (hit != null && isNameEditableType(hit.typeId())) {
                        nameEditorFocusListener.run();
                    } else {
                        editGateLabelAt(event.getPoint());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                boolean middleDrag = SwingUtilities.isMiddleMouseButton(event);
                boolean spaceLeftDrag = SwingUtilities.isLeftMouseButton(event) && spaceDown;
                if (middleDrag || spaceLeftDrag) {
                    panning = true;
                    lastPanPoint = event.getPoint();
                    resetGesture();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (galleryMode || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }

                if (activeTool == Tool.ADD) {
                    beginHistoryGesture();
                    gesture = Gesture.ADD_CLICK;
                    gestureStartScreen = event.getPoint();
                    Rectangle oldPreviewBounds = previewScreenBounds();
                    pointerFromScreen(event.getPoint());
                    repaintUnion(oldPreviewBounds, previewScreenBounds());
                } else if (activeTool == Tool.EDIT) {
                    beginEditGesture(event);
                } else if (activeTool == Tool.POKE) {
                    pokeAt(event.getPoint());
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (panning && lastPanPoint != null) {
                    transform.panBy(
                            event.getX() - lastPanPoint.x,
                            event.getY() - lastPanPoint.y);
                    lastPanPoint = event.getPoint();
                    repaint();
                    return;
                }

                if (activeTool == Tool.ADD) {
                    Rectangle oldPreviewBounds = previewScreenBounds();
                    pointerFromScreen(event.getPoint());
                    updateGestureMovement(event.getPoint());
                    repaintUnion(oldPreviewBounds, previewScreenBounds());
                    return;
                }
                if (activeTool == Tool.EDIT) {
                    if (gesture == Gesture.PENDING_WIRE_START) {
                        Rectangle oldPreviewBounds = wirePreviewScreenBounds();
                        updateWireGestureMovement(event.getPoint());
                        if (gestureMoved) {
                            gesture = Gesture.DRAW_WIRE;
                            chooseWireAxis(event.getPoint());
                            pointerFromScreen(event.getPoint());
                        }
                        repaintUnion(oldPreviewBounds, wirePreviewScreenBounds());
                        return;
                    }
                    if (gesture == Gesture.DRAW_WIRE) {
                        Rectangle oldPreviewBounds = wirePreviewScreenBounds();
                        updateWireGestureMovement(event.getPoint());
                        chooseWireAxis(event.getPoint());
                        pointerFromScreen(event.getPoint());
                        repaintUnion(oldPreviewBounds, wirePreviewScreenBounds());
                        return;
                    }
                    Rectangle oldGestureBounds = gestureVisualScreenBounds();
                    pointerFromScreen(event.getPoint());
                    updateGestureMovement(event.getPoint());
                    repaintUnion(oldGestureBounds, gestureVisualScreenBounds());
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                if (activeTool == Tool.ADD) {
                    Rectangle oldPreviewBounds = previewScreenBounds();
                    pointerFromScreen(event.getPoint());
                    repaintUnion(oldPreviewBounds, previewScreenBounds());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (panning) {
                    panning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }

                if (activeTool == Tool.ADD && gesture == Gesture.ADD_CLICK) {
                    updateGestureMovement(event.getPoint());
                    if (!gestureMoved && previewTypeId != null) {
                        Rectangle previewBounds = previewScreenBounds();
                        placeAtWorld(previewTypeId, previewX, previewY, previewFacing);
                        commitHistoryGesture();
                        repaintIfPresent(previewBounds);
                    }
                    resetGesture();
                    return;
                }
                if (activeTool == Tool.EDIT) {
                    finishEditGesture(event);
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                if (!event.isControlDown()) {
                    return;
                }
                double factor = Math.pow(ZOOM_PER_WHEEL_NOTCH,
                        -event.getPreciseWheelRotation());
                transform.zoomAt(event.getX(), event.getY(), factor);
                if (activeTool == Tool.ADD) {
                    pointerFromScreen(event.getPoint());
                }
                repaint();
                event.consume();
            }
        };

        addMouseListener(mouseControls);
        addMouseMotionListener(mouseControls);
        addMouseWheelListener(mouseControls);
    }

    private void pokeAt(Point screenPoint) {
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        PlacedComponent hit = schematic.componentAt(
                (int) Math.floor(world.x), (int) Math.floor(world.y)).orElse(null);
        if (hit != null && "input".equals(hit.typeId())) {
            schematic.toggleInput(hit.id());
            recordSignalSample();
        }
    }

    private void beginEditGesture(MouseEvent event) {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        Point2D.Double world = transform.screenToWorld(event.getX(), event.getY());
        gestureStartWorldX = world.x;
        gestureStartWorldY = world.y;
        pointerWorldX = world.x;
        pointerWorldY = world.y;
        gestureStartScreen = event.getPoint();
        gestureMoved = false;

        boolean shift = event.isShiftDown();
        Connectivity.PinPosition pin = pinNear(world.x, world.y);
        double wireTolerance = 6.0 / transform.scale();
        WireSegment wireHit = schematic.wireAt(world.x, world.y, wireTolerance).orElse(null);

        if (wireHit != null && selectedWires.contains(wireHit) && !shift) {
            gesture = Gesture.MOVE_SELECTION;
            Map<String, PlacedComponent> starting = new LinkedHashMap<>();
            for (PlacedComponent component : selectedComponents()) {
                starting.put(component.id(), component);
            }
            moveStartComponents = Map.copyOf(starting);
            moveStartWires = Set.copyOf(selectedWires);
            beginHistoryGesture();
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }
        if (wireHit != null && shift) {
            if (!selectedWires.add(wireHit)) {
                selectedWires.remove(wireHit);
            }
            notifySelectionChanged();
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }

        if (pin != null) {
            startWireGesture(pin.x(), pin.y());
            if (wireHit != null) {
                // A click selects the line; a drag from this pin starts a branch.
                pendingWireHit = wireHit;
                gesture = Gesture.PENDING_WIRE_START;
            }
            repaintUnion(oldSelectionBounds, wirePreviewScreenBounds());
            return;
        }

        if (wireHit != null) {
            Connectivity.Point start = wireGridPoint(wireHit, world.x, world.y);
            startWireGesture(start.x(), start.y());
            pendingWireHit = wireHit;
            gesture = Gesture.PENDING_WIRE_START;
            return;
        }

        int hitX = (int) Math.floor(world.x);
        int hitY = (int) Math.floor(world.y);
        PlacedComponent hit = schematic.componentAt(hitX, hitY).orElse(null);

        if (hit != null && shift) {
            if (!selectedIds.add(hit.id())) {
                selectedIds.remove(hit.id());
            }
            notifySelectionChanged();
            gesture = Gesture.NONE;
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }

        if (hit == null) {
            if (!shift) {
                selectedIds.clear();
                selectedWires.clear();
                notifySelectionChanged();
            }
            additiveMarquee = shift;
            gesture = Gesture.SELECT_RECTANGLE;
            moveStartComponents = Map.of();
            moveStartWires = Set.of();
            repaintUnion(oldSelectionBounds, marqueeScreenBounds());
            return;
        }

        if (!selectedIds.contains(hit.id())) {
            selectedIds.clear();
            selectedWires.clear();
            selectedIds.add(hit.id());
            notifySelectionChanged();
        }
        gesture = Gesture.MOVE_SELECTION;
        Map<String, PlacedComponent> starting = new LinkedHashMap<>();
        for (PlacedComponent component : selectedComponents()) {
            starting.put(component.id(), component);
        }
        moveStartComponents = Map.copyOf(starting);
        moveStartWires = Set.copyOf(selectedWires);
        beginHistoryGesture();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private Connectivity.PinPosition pinNear(double worldX, double worldY) {
        Connectivity.PinPosition nearest = null;
        double nearestDistance = PlacedComponent.GRID_SPACING / 2.0;
        for (Connectivity.PinPosition pin : schematic.pinPositions()) {
            double distance = Math.hypot(worldX - pin.x(), worldY - pin.y());
            if (distance <= nearestDistance) {
                nearest = pin;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void startWireGesture(int x, int y) {
        beginHistoryGesture();
        wireStartPoint = new Connectivity.Point(x, y);
        wireAxisChosen = false;
        wireHorizontalFirst = true;
        pendingWireHit = null;
        gesture = Gesture.DRAW_WIRE;
    }

    private Connectivity.Point wireGridPoint(
            WireSegment wire, double worldX, double worldY) {
        if (wire.isHorizontal()) {
            int x = Math.max(wire.x1(), Math.min(wire.x2(), snapToGrid(worldX)));
            return new Connectivity.Point(x, wire.y1());
        }
        int y = Math.max(wire.y1(), Math.min(wire.y2(), snapToGrid(worldY)));
        return new Connectivity.Point(wire.x1(), y);
    }

    private void updateWireGestureMovement(Point screenPoint) {
        if (gestureStartScreen == null || screenPoint == null) {
            return;
        }
        double dx = screenPoint.x - gestureStartScreen.x;
        double dy = screenPoint.y - gestureStartScreen.y;
        // A grid step is only 2.5 screen pixels at minimum zoom.
        if (dx * dx + dy * dy >= 4.0) {
            gestureMoved = true;
        }
    }

    private void chooseWireAxis(Point screenPoint) {
        if (wireAxisChosen || gestureStartScreen == null) {
            return;
        }
        int dx = screenPoint.x - gestureStartScreen.x;
        int dy = screenPoint.y - gestureStartScreen.y;
        if (dx != 0 || dy != 0) {
            wireHorizontalFirst = Math.abs(dx) >= Math.abs(dy);
            wireAxisChosen = true;
        }
    }

    private List<WireSegment> wirePreviewSegments() {
        if (wireStartPoint == null) {
            return List.of();
        }
        int targetX = snapToGrid(pointerWorldX);
        int targetY = snapToGrid(pointerWorldY);
        int cornerX = wireHorizontalFirst ? targetX : wireStartPoint.x();
        int cornerY = wireHorizontalFirst ? wireStartPoint.y() : targetY;
        List<WireSegment> preview = new ArrayList<>(2);
        if (wireStartPoint.x() != cornerX || wireStartPoint.y() != cornerY) {
            preview.add(new WireSegment(
                    wireStartPoint.x(), wireStartPoint.y(), cornerX, cornerY));
        }
        if (cornerX != targetX || cornerY != targetY) {
            preview.add(new WireSegment(cornerX, cornerY, targetX, targetY));
        }
        return List.copyOf(preview);
    }

    private void selectWireClick(WireSegment wire) {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        selectedIds.clear();
        selectedWires.clear();
        selectedWires.add(wire);
        notifySelectionChanged();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private void updateGestureMovement(Point currentScreen) {
        if (gestureStartScreen == null || currentScreen == null) {
            return;
        }
        double dx = currentScreen.x - gestureStartScreen.x;
        double dy = currentScreen.y - gestureStartScreen.y;
        if (dx * dx + dy * dy >= DRAG_THRESHOLD_SQUARED) {
            gestureMoved = true;
        }
    }

    private void finishEditGesture(MouseEvent event) {
        Rectangle oldGestureBounds = gestureVisualScreenBounds();
        pointerFromScreen(event.getPoint());
        if (gesture == Gesture.PENDING_WIRE_START || gesture == Gesture.DRAW_WIRE) {
            updateWireGestureMovement(event.getPoint());
        } else {
            updateGestureMovement(event.getPoint());
        }

        if (gesture == Gesture.PENDING_WIRE_START && !gestureMoved) {
            if (pendingWireHit != null) {
                selectWireClick(pendingWireHit);
            }
            resetGesture();
            repaintUnion(oldGestureBounds, selectionScreenBounds());
            return;
        }
        if (gesture == Gesture.PENDING_WIRE_START) {
            gesture = Gesture.DRAW_WIRE;
        }

        if (gesture == Gesture.DRAW_WIRE) {
            Rectangle finalWireBounds = wirePreviewScreenBounds();
            if (gestureMoved) {
                chooseWireAxis(event.getPoint());
                for (WireSegment segment : wirePreviewSegments()) {
                    schematic.addWire(segment);
                    finalWireBounds = union(finalWireBounds,
                            wireSegmentScreenBounds(segment, DIRTY_REGION_MARGIN + 3));
                }
            }
            commitHistoryGesture();
            resetGesture();
            repaintUnion(oldGestureBounds, finalWireBounds);
            return;
        }

        if (gesture == Gesture.MOVE_SELECTION && gestureMoved) {
            int deltaX = snapToGrid(pointerWorldX - gestureStartWorldX);
            int deltaY = snapToGrid(pointerWorldY - gestureStartWorldY);
            Schematic.MoveResult result = schematic.moveSelection(
                    moveStartComponents.keySet(), moveStartWires, deltaX, deltaY);
            selectedWires.clear();
            selectedWires.addAll(result.movedWires());
            statusMessageListener.accept(
                    result.linksFollowed() ? "" : "Some wires could not follow");
            notifySelectionChanged();
            commitHistoryGesture();
        } else if (gesture == Gesture.SELECT_RECTANGLE && gestureMoved) {
            GateGeometry.Bounds rectangle = selectionRectangle(
                    gestureStartWorldX, gestureStartWorldY,
                    pointerWorldX, pointerWorldY);
            List<PlacedComponent> hits = schematic.componentsIntersecting(rectangle);
            List<WireSegment> wireHits = schematic.wiresIntersecting(rectangle);
            if (!additiveMarquee) {
                selectedIds.clear();
                selectedWires.clear();
            }
            for (PlacedComponent component : hits) {
                selectedIds.add(component.id());
            }
            selectedWires.addAll(wireHits);
            notifySelectionChanged();
        }

        resetGesture();
        repaintUnion(oldGestureBounds, selectionScreenBounds());
    }

    private static GateGeometry.Bounds selectionRectangle(
            double startX, double startY, double endX, double endY) {
        int left = (int) Math.floor(Math.min(startX, endX));
        int top = (int) Math.floor(Math.min(startY, endY));
        int right = (int) Math.ceil(Math.max(startX, endX));
        int bottom = (int) Math.ceil(Math.max(startY, endY));
        return new GateGeometry.Bounds(left, top, right - left, bottom - top);
    }

    private PlacedComponent placeAtWorld(
            String typeId, double worldX, double worldY, Facing facing) {
        return schematic.add(
                typeId, snapToGrid(worldX), snapToGrid(worldY), facing,
                toolAttributesByFamily.getOrDefault(typeId, Map.of()));
    }

    private void pointerFromScreen(Point screenPoint) {
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        pointerWorldX = world.x;
        pointerWorldY = world.y;
        updatePreviewPosition(world.x, world.y);
    }

    private void updatePreviewPosition(double worldX, double worldY) {
        previewX = snapToGrid(worldX);
        previewY = snapToGrid(worldY);
    }

    private static int snapToGrid(double coordinate) {
        return (int) (Math.floor(coordinate / WORLD_GRID_SPACING + 0.5) * WORLD_GRID_SPACING);
    }

    private void setFacing(Facing facing) {
        if (activeTool == Tool.ADD) {
            Rectangle oldPreviewBounds = previewScreenBounds();
            setToolAttribute(previewTypeId, "facing", facing.name());
            previewFacing = toolFacingByFamily.getOrDefault(previewTypeId, facing);
            repaintUnion(oldPreviewBounds, previewScreenBounds());
            return;
        }
        if (selectedIds.isEmpty()) {
            return;
        }
        Rectangle oldSelectionBounds = selectionScreenBounds();
        beginHistoryGesture();
        boolean linksFollowed = schematic.setFacing(selectedIds, facing);
        statusMessageListener.accept(linksFollowed ? "" : "Some wires could not follow");
        commitHistoryGesture();
        notifySelectionChanged();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private void deleteSelection() {
        if ((selectedIds.isEmpty() && selectedWires.isEmpty()) || activeTool == Tool.ADD) {
            return;
        }
        Rectangle oldSelectionBounds = selectionScreenBounds();
        beginHistoryGesture();
        for (String id : List.copyOf(selectedIds)) {
            schematic.remove(id);
        }
        for (WireSegment wire : List.copyOf(selectedWires)) {
            schematic.removeWire(wire);
        }
        selectedIds.clear();
        selectedWires.clear();
        commitHistoryGesture();
        notifySelectionChanged();
        repaintIfPresent(oldSelectionBounds);
    }

    private List<PlacedComponent> selectedComponents() {
        List<PlacedComponent> selected = new ArrayList<>();
        for (PlacedComponent component : schematic.components()) {
            if (selectedIds.contains(component.id())) {
                selected.add(component);
            }
        }
        return List.copyOf(selected);
    }

    private void notifySelectionChanged() {
        selectionListener.accept(selectedComponents());
        notifyAttributeStateChanged();
    }

    private void resetGesture() {
        history.cancelPending();
        gesture = Gesture.NONE;
        gestureStartScreen = null;
        gestureMoved = false;
        additiveMarquee = false;
        moveStartComponents = Map.of();
        moveStartWires = Set.of();
        wireStartPoint = null;
        pendingWireHit = null;
        wireAxisChosen = false;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        long paintStartedNanos = PAINT_STATS_ENABLED ? System.nanoTime() : 0L;
        super.paintComponent(graphics);

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            paintGrid(g2);
            if (galleryMode) {
                paintGallery(g2);
            } else {
                paintSchematic(g2);
            }
        } finally {
            g2.dispose();
            if (PAINT_STATS_ENABLED) {
                recordPaintDuration(System.nanoTime() - paintStartedNanos);
            }
        }
    }

    /** Blits a client-side software-rendered grid; only view changes rebuild it. */
    private void paintGrid(Graphics graphics) {
        ensureGridCache();
        if (gridImage != null) {
            graphics.drawImage(gridImage, 0, 0, null);
        }
    }

    private void ensureGridCache() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            gridImage = null;
            return;
        }

        long scaleBits = Double.doubleToLongBits(transform.scale());
        long translateXBits = Double.doubleToLongBits(transform.translateX());
        long translateYBits = Double.doubleToLongBits(transform.translateY());
        if (gridImage != null && gridCacheWidth == width && gridCacheHeight == height
                && gridCacheScaleBits == scaleBits
                && gridCacheTranslateXBits == translateXBits
                && gridCacheTranslateYBits == translateYBits) {
            return;
        }

        BufferedImage rebuilt = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D gridGraphics = rebuilt.createGraphics();
        try {
            gridGraphics.setColor(Color.WHITE);
            gridGraphics.fillRect(0, 0, width, height);
            gridGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);

            double scale = transform.scale();
            double screenSpacing = WORLD_GRID_SPACING * scale;
            int stride = Math.max(1, (int) Math.ceil(
                    MIN_SCREEN_GRID_SPACING / screenSpacing));
            double worldStep = WORLD_GRID_SPACING * stride;

            Point2D.Double worldTopLeft = transform.screenToWorld(0.0, 0.0);
            Point2D.Double worldBottomRight = transform.screenToWorld(width, height);
            double firstX = Math.ceil(worldTopLeft.x / worldStep) * worldStep;
            double firstY = Math.ceil(worldTopLeft.y / worldStep) * worldStep;

            int dotSize = screenSpacing >= 8.0 ? 2 : 1;
            gridGraphics.setColor(new Color(205, 209, 214));
            for (double worldY = firstY; worldY <= worldBottomRight.y; worldY += worldStep) {
                int screenY = (int) Math.round(transform.worldToScreen(firstX, worldY).y);
                for (double worldX = firstX; worldX <= worldBottomRight.x; worldX += worldStep) {
                    int screenX = (int) Math.round(transform.worldToScreen(worldX, worldY).x);
                    gridGraphics.fillRect(screenX, screenY, dotSize, dotSize);
                }
            }
        } finally {
            gridGraphics.dispose();
        }

        gridImage = rebuilt;
        gridCacheWidth = width;
        gridCacheHeight = height;
        gridCacheScaleBits = scaleBits;
        gridCacheTranslateXBits = translateXBits;
        gridCacheTranslateYBits = translateYBits;
        gridCacheRebuildCount++;
    }

    long gridCacheRebuildCount() {
        return gridCacheRebuildCount;
    }

    private void recordPaintDuration(long durationNanos) {
        long now = System.nanoTime();
        if (paintStatsWindowStartNanos == 0L) {
            paintStatsWindowStartNanos = now;
        }
        paintStatsPaintCount++;
        paintStatsTotalNanos += durationNanos;
        paintStatsMinNanos = Math.min(paintStatsMinNanos, durationNanos);
        paintStatsMaxNanos = Math.max(paintStatsMaxNanos, durationNanos);

        if (now - paintStatsWindowStartNanos >= PAINT_STATS_INTERVAL_NANOS) {
            double averageMs = paintStatsTotalNanos / (double) paintStatsPaintCount / 1_000_000.0;
            double minimumMs = paintStatsMinNanos / 1_000_000.0;
            double maximumMs = paintStatsMaxNanos / 1_000_000.0;
            System.err.printf(Locale.ROOT,
                    "LogicBench paintStats: n=%d avg=%.3f ms min=%.3f ms max=%.3f ms%n",
                    paintStatsPaintCount, averageMs, minimumMs, maximumMs);
            paintStatsWindowStartNanos = now;
            paintStatsPaintCount = 0L;
            paintStatsTotalNanos = 0L;
            paintStatsMinNanos = Long.MAX_VALUE;
            paintStatsMaxNanos = 0L;
        }
    }

    private void paintSchematic(Graphics2D screenGraphics) {
        Graphics2D worldGraphics = (Graphics2D) screenGraphics.create();
        try {
            worldGraphics.translate(transform.translateX(), transform.translateY());
            worldGraphics.scale(transform.scale(), transform.scale());

            Rectangle screenClip = screenGraphics.getClipBounds();
            if (screenClip == null) {
                screenClip = new Rectangle(0, 0, getWidth(), getHeight());
            }

            int moveDeltaX = gesture == Gesture.MOVE_SELECTION && gestureMoved
                    ? snapToGrid(pointerWorldX - gestureStartWorldX) : 0;
            int moveDeltaY = gesture == Gesture.MOVE_SELECTION && gestureMoved
                    ? snapToGrid(pointerWorldY - gestureStartWorldY) : 0;

            for (WireSegment wire : schematic.wires()) {
                boolean selected = selectedWires.contains(wire);
                boolean moving = selected && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                WireSegment rendered = moving
                        ? translateWire(wire, moveDeltaX, moveDeltaY) : wire;
                if (screenClip.intersects(wireSegmentScreenBounds(rendered, 5))) {
                    paintWireSegment(worldGraphics, rendered, simulationResult.valueOf(wire), selected);
                }
            }

            for (PlacedComponent component : schematic.components()) {
                boolean selected = selectedIds.contains(component.id());
                boolean moving = selected && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                int x = component.x() + (moving ? moveDeltaX : 0);
                int y = component.y() + (moving ? moveDeltaY : 0);
                GateDef definition = schematic.definitionFor(component);
                int labelMargin = component.label().isEmpty()
                        ? DIRTY_REGION_MARGIN : Math.max(
                                DIRTY_REGION_MARGIN, component.label().length() * 7 + 10);
                if (isGateComponent(component)) {
                    labelMargin = Math.max(labelMargin,
                            (int) Math.ceil(22.0 * transform.scale() + 6.0));
                }
                if (!screenClip.intersects(componentScreenBounds(
                        definition, component.facing(), x, y, labelMargin))) {
                    continue;
                }

                if (moving) {
                    worldGraphics.setComposite(AlphaComposite.SrcOver.derive(0.72f));
                } else {
                    worldGraphics.setComposite(AlphaComposite.SrcOver);
                }
                gateRenderer.render(
                        worldGraphics, definition, component.facing(), x, y);
                worldGraphics.setComposite(AlphaComposite.SrcOver);
                if (component.typeId().equals("output")
                        && schematic.isOutputTapped(component.id())) {
                    paintTappedOutputRing(
                            screenGraphics, definition, component.facing(), x, y);
                }
                paintComponentLabel(screenGraphics, component, definition, x, y);
                paintGateName(worldGraphics, component, definition, x, y);

                if (selected) {
                    paintSelectionOutline(worldGraphics, definition,
                            component.facing(), x, y);
                }
                Value signal = componentSignal(component);
                if (signal != null) {
                    if (component.typeId().equals("clock")) {
                        paintClockLevel(screenGraphics, definition, component.facing(), x, y, signal);
                    } else {
                        paintSignalDigit(screenGraphics, definition,
                                component.facing(), x, y, signal);
                    }
                }
            }

            worldGraphics.setColor(Color.BLACK);
            for (Connectivity.Point junction : simulationResult.topology().junctions()) {
                Point2D.Double screen = transform.worldToScreen(junction.x(), junction.y());
                if (screenClip.contains(screen.x, screen.y)) {
                    worldGraphics.fillOval(junction.x() - 3, junction.y() - 3, 6, 6);
                }
            }

            if (gesture == Gesture.DRAW_WIRE && gestureMoved) {
                Graphics2D previewGraphics = (Graphics2D) worldGraphics.create();
                try {
                    previewGraphics.setColor(new Color(36, 112, 214));
                    previewGraphics.setStroke(new BasicStroke(
                            2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10.0f, new float[]{5.0f, 4.0f}, 0.0f));
                    for (WireSegment segment : wirePreviewSegments()) {
                        previewGraphics.drawLine(
                                segment.x1(), segment.y1(), segment.x2(), segment.y2());
                    }
                } finally {
                    previewGraphics.dispose();
                }
            }

            if (activeTool == Tool.ADD && previewTypeId != null) {
                GateDef previewDefinition = resolveToolDefinition(previewTypeId,
                    toolAttributesByFamily.getOrDefault(previewTypeId, Map.of()));
                if (screenClip.intersects(componentScreenBounds(
                        previewDefinition, previewFacing, previewX, previewY, DIRTY_REGION_MARGIN))) {
                    worldGraphics.setComposite(AlphaComposite.SrcOver.derive(0.42f));
                    gateRenderer.render(worldGraphics, previewDefinition,
                            previewFacing, previewX, previewY);
                    worldGraphics.setComposite(AlphaComposite.SrcOver);
                }
            }

            if (gesture == Gesture.SELECT_RECTANGLE && gestureMoved
                    && screenClip.intersects(marqueeScreenBounds())) {
                paintMarquee(worldGraphics);
            }
        } finally {
            worldGraphics.dispose();
        }
        if (simulationResult.oscillating()) {
            Graphics2D status = (Graphics2D) screenGraphics.create();
            try {
                status.setColor(Color.RED);
                status.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                status.drawString("Oscillation detected", 12, 22);
            } finally {
                status.dispose();
            }
        }
    }

    /** User labels remain screen-sized and sit above gates or beside I/O pins. */
    private void paintComponentLabel(
            Graphics2D graphics,
            PlacedComponent component,
            GateDef definition,
            int originX,
            int originY) {
        if (component.label().isEmpty()) {
            return;
        }
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, component.facing());
        Graphics2D labelGraphics = (Graphics2D) graphics.create();
        try {
            Color foreground = UIManager.getColor("Label.foreground");
            labelGraphics.setColor(foreground == null ? Color.DARK_GRAY : foreground);
            labelGraphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            java.awt.FontMetrics metrics = labelGraphics.getFontMetrics();
            int textWidth = metrics.stringWidth(component.label());
            final float x;
            final float baseline;
            if (isGateComponent(component)) {
                double centerX = originX + bounds.x() + bounds.width() / 2.0;
                double topY = originY + bounds.y();
                Point2D.Double screen = transform.worldToScreen(centerX, topY);
                x = (float) (screen.x - textWidth / 2.0);
                baseline = (float) (screen.y - 6.0);
                Rectangle2D.Double textArea = new Rectangle2D.Double(
                        centerX - (textWidth / 2.0 + 3.0) / transform.scale(),
                        topY - (metrics.getAscent() + 9.0) / transform.scale(),
                        (textWidth + 6.0) / transform.scale(),
                        (metrics.getHeight() + 4.0) / transform.scale());
                if (!isLabelAreaClear(textArea, component.id())) {
                    return;
                }
            } else {
                double labelX = component.typeId().equals("input")
                        ? originX + bounds.x() : originX + bounds.x() + bounds.width();
                double labelY = originY + bounds.y() + bounds.height() / 2.0;
                Point2D.Double screen = transform.worldToScreen(labelX, labelY);
                x = (float) (component.typeId().equals("input")
                        ? screen.x - textWidth - 6.0 : screen.x + 6.0);
                baseline = (float) (screen.y
                        + (metrics.getAscent() - metrics.getDescent()) / 2.0);
            }
            labelGraphics.drawString(component.label(), x, baseline);
        } finally {
            labelGraphics.dispose();
        }
    }

    /**
     * Gate names sit just below the rotated artwork and inherit the canvas zoom.
     * They are skipped when the text would obscure a wire, pin or other component.
     */
    private void paintGateName(
            Graphics2D graphics,
            PlacedComponent component,
            GateDef definition,
            int originX,
            int originY) {
        if (!showGateNames || transform.scale() < GateNameRenderer.MIN_ZOOM
                || !isGateComponent(component)) {
            return;
        }
        String text = GateNameRenderer.textForType(component.typeId());
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 8);
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, component.facing());
        java.awt.FontMetrics metrics = graphics.getFontMetrics(font);
        int textWidth = metrics.stringWidth(text);
        double left = originX + bounds.x() + (bounds.width() - textWidth) / 2.0;
        double baseline = originY + bounds.y() + bounds.height() + 11.0;
        Rectangle2D.Double textArea = new Rectangle2D.Double(
                left - 2.0, baseline - metrics.getAscent() - 2.0,
                textWidth + 4.0, metrics.getHeight() + 4.0);
        if (!isLabelAreaClear(textArea, component.id())) {
            return;
        }

        Graphics2D nameGraphics = (Graphics2D) graphics.create();
        try {
            Color foreground = UIManager.getColor("Label.foreground");
            nameGraphics.setColor(foreground == null ? Color.DARK_GRAY : foreground);
            nameGraphics.setFont(font);
            nameGraphics.drawString(text, (float) left, (float) baseline);
        } finally {
            nameGraphics.dispose();
        }
    }

    private boolean isLabelAreaClear(Rectangle2D area, String ownerComponentId) {
        for (WireSegment wire : schematic.wires()) {
            if (new Line2D.Double(
                    wire.x1(), wire.y1(), wire.x2(), wire.y2()).intersects(area)) {
                return false;
            }
        }
        for (Connectivity.PinPosition pin : schematic.pinPositions()) {
            if (area.contains(pin.x(), pin.y())) {
                return false;
            }
        }
        for (PlacedComponent other : schematic.components()) {
            if (other.id().equals(ownerComponentId)) {
                continue;
            }
            GateGeometry.Bounds bounds =
                    GateGeometry.bounds(schematic.definitionFor(other), other.facing());
            Rectangle2D otherBounds = new Rectangle2D.Double(
                    other.x() + bounds.x(), other.y() + bounds.y(),
                    bounds.width(), bounds.height());
            if (otherBounds.intersects(area)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGateComponent(PlacedComponent component) {
        return !component.typeId().equals("input") && !component.typeId().equals("output")
                && !GateNameRenderer.textForType(component.typeId()).isEmpty();
    }

    private void editGateLabelAt(Point screenPoint) {
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        PlacedComponent component = schematic.componentAt(
                (int) Math.floor(world.x), (int) Math.floor(world.y)).orElse(null);
        if (component == null || !isGateComponent(component)) {
            return;
        }

        JTextField field = new JTextField(component.label(), 24);
        int choice = JOptionPane.showConfirmDialog(
                this, new Object[]{"Gate label:", field}, "Edit gate label",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        beginHistoryGesture();
        schematic.setLabel(component.id(), field.getText());
        commitHistoryGesture();
        repaint();
    }

    private Value componentSignal(PlacedComponent component) {
        return switch (component.typeId()) {
            case "input", "clock" ->
                    simulationResult.inputValues().getOrDefault(component.id(), Value.ZERO);
            case "output" -> simulationResult.outputValues().getOrDefault(component.id(), Value.X);
            default -> null;
        };
    }

    private void paintClockLevel(Graphics2D graphics, GateDef definition, Facing facing,
            int originX, int originY, Value value) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
        Point2D.Double center = transform.worldToScreen(
                originX + bounds.x() + bounds.width() / 2.0,
                originY + bounds.y() + bounds.height() * 0.72);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            String digit = value == Value.ONE ? "1" : "0";
            g2.setColor(valueColor(value));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            java.awt.FontMetrics metrics = g2.getFontMetrics();
            float left = (float) (center.x - metrics.stringWidth(digit) / 2.0);
            float baseline = (float) (center.y
                    + (metrics.getAscent() - metrics.getDescent()) / 2.0);
            g2.drawString(digit, left, baseline);
        } finally {
            g2.dispose();
        }
    }

    private void paintSignalDigit(Graphics2D graphics, GateDef definition, Facing facing,
            int originX, int originY, Value value) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
        Point2D.Double center = transform.worldToScreen(
                originX + bounds.x() + bounds.width() / 2.0,
                originY + bounds.y() + bounds.height() / 2.0);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            String digit = value == Value.X ? "x" : value == Value.ONE ? "1" : "0";
            g2.setColor(valueColor(value));
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            java.awt.FontMetrics metrics = g2.getFontMetrics();
            float left = (float) (center.x - metrics.stringWidth(digit) / 2.0);
            float baseline = (float) (center.y
                    + (metrics.getAscent() - metrics.getDescent()) / 2.0);
            g2.drawString(digit, left, baseline);
        } finally { g2.dispose(); }
    }

    private void paintTappedOutputRing(
            Graphics2D graphics,
            GateDef definition,
            Facing facing,
            int originX,
            int originY) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
        Point2D.Double center = transform.worldToScreen(
                originX + bounds.x() + bounds.width() / 2.0,
                originY + bounds.y() + bounds.height() / 2.0);
        int diameter = (int) Math.round(
                Math.max(bounds.width(), bounds.height()) * transform.scale() + 6.0);
        Graphics2D ring = (Graphics2D) graphics.create();
        try {
            Color foreground = UIManager.getColor("Label.foreground");
            if (foreground == null) {
                foreground = UIManager.getColor("Component.borderColor");
            }
            ring.setColor(foreground == null ? Color.DARK_GRAY : foreground);
            ring.setStroke(new BasicStroke(1.0f));
            ring.drawOval(
                    (int) Math.round(center.x - diameter / 2.0),
                    (int) Math.round(center.y - diameter / 2.0),
                    diameter, diameter);
        } finally {
            ring.dispose();
        }
    }

    private static Color valueColor(Value value) {
        return switch (value) {
            case ONE -> new Color(0, 210, 0);
            case ZERO -> new Color(0, 100, 0);
            case X -> new Color(0, 0, 255);
        };
    }

    private void paintWireSegment(Graphics2D graphics, WireSegment wire, Value value, boolean selected) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            if (selected) {
                g2.setStroke(new BasicStroke(9.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(new Color(255, 140, 0, 115));
                g2.drawLine(wire.x1(), wire.y1(), wire.x2(), wire.y2());
            }
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(valueColor(value));
            g2.drawLine(wire.x1(), wire.y1(), wire.x2(), wire.y2());
        } finally { g2.dispose(); }
    }

    private void paintSelectionOutline(
            Graphics2D graphics,
            GateDef definition,
            Facing facing,
            int originX,
            int originY) {
        GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setColor(new Color(36, 112, 214));
            g2.setStroke(new BasicStroke((float) (1.4 / transform.scale())));
            g2.drawRect(originX + bounds.x(), originY + bounds.y(),
                    bounds.width(), bounds.height());
        } finally {
            g2.dispose();
        }
    }

    private Rectangle previewScreenBounds() {
        if (previewTypeId == null) {
            return null;
        }
        return componentScreenBounds(
                resolveToolDefinition(previewTypeId,
                        toolAttributesByFamily.getOrDefault(previewTypeId, Map.of())), previewFacing,
                previewX, previewY, DIRTY_REGION_MARGIN);
    }

    private Rectangle componentScreenBounds(
            PlacedComponent component, int deltaX, int deltaY, int margin) {
        GateDef definition = schematic.definitionFor(component);
        return componentScreenBounds(definition, component.facing(),
                component.x() + deltaX, component.y() + deltaY, margin);
    }

    private Rectangle componentScreenBounds(
            GateDef definition, Facing facing, int originX, int originY, int margin) {
        GateGeometry.Bounds relative = GateGeometry.bounds(definition, facing);
        return worldBoundsToScreen(new GateGeometry.Bounds(
                originX + relative.x(), originY + relative.y(),
                relative.width(), relative.height()), margin);
    }

    private Rectangle selectionScreenBounds() {
        Rectangle result = null;
        for (PlacedComponent component : selectedComponents()) {
            result = union(result, componentScreenBounds(component, 0, 0, DIRTY_REGION_MARGIN));
        }
        for (WireSegment wire : selectedWires) {
            result = union(result, wireSegmentScreenBounds(wire, DIRTY_REGION_MARGIN + 3));
        }
        return result;
    }

    private Rectangle movingSelectionScreenBounds() {
        int deltaX = gestureMoved ? snapToGrid(pointerWorldX - gestureStartWorldX) : 0;
        int deltaY = gestureMoved ? snapToGrid(pointerWorldY - gestureStartWorldY) : 0;
        Rectangle result = null;
        for (PlacedComponent component : moveStartComponents.values()) {
            // Include source and live ghost bounds so switching tools clears both.
            result = union(result, componentScreenBounds(
                    component, 0, 0, DIRTY_REGION_MARGIN));
            result = union(result, componentScreenBounds(
                    component, deltaX, deltaY, DIRTY_REGION_MARGIN));
        }
        for (WireSegment wire : moveStartWires) {
            result = union(result, wireSegmentScreenBounds(wire, DIRTY_REGION_MARGIN + 3));
            result = union(result, wireSegmentScreenBounds(translateWire(wire, deltaX, deltaY),
                    DIRTY_REGION_MARGIN + 3));
        }
        return result;
    }

    private Rectangle marqueeScreenBounds() {
        if (gesture != Gesture.SELECT_RECTANGLE) {
            return null;
        }
        return worldBoundsToScreen(selectionRectangle(
                gestureStartWorldX, gestureStartWorldY,
                pointerWorldX, pointerWorldY), DIRTY_REGION_MARGIN);
    }

    private Rectangle gestureVisualScreenBounds() {
        return switch (gesture) {
            case MOVE_SELECTION -> movingSelectionScreenBounds();
            case SELECT_RECTANGLE -> marqueeScreenBounds();
            case DRAW_WIRE -> wirePreviewScreenBounds();
            default -> null;
        };
    }

    private Rectangle wirePreviewScreenBounds() {
        Rectangle result = null;
        for (WireSegment segment : wirePreviewSegments()) {
            result = union(result, wireSegmentScreenBounds(segment, DIRTY_REGION_MARGIN + 3));
        }
        return result;
    }

    private Rectangle wireSegmentScreenBounds(WireSegment wire, int margin) {
        int left = Math.min(wire.x1(), wire.x2());
        int top = Math.min(wire.y1(), wire.y2());
        int width = Math.abs(wire.x2() - wire.x1());
        int height = Math.abs(wire.y2() - wire.y1());
        return worldBoundsToScreen(new GateGeometry.Bounds(left, top, width, height), margin);
    }

    private static WireSegment translateWire(WireSegment wire, int deltaX, int deltaY) {
        return new WireSegment(
                wire.x1() + deltaX, wire.y1() + deltaY,
                wire.x2() + deltaX, wire.y2() + deltaY);
    }

    private Rectangle worldBoundsToScreen(GateGeometry.Bounds bounds, int margin) {
        double scale = transform.scale();
        double left = bounds.x() * scale + transform.translateX();
        double top = bounds.y() * scale + transform.translateY();
        double right = (bounds.x() + bounds.width()) * scale + transform.translateX();
        double bottom = (bounds.y() + bounds.height()) * scale + transform.translateY();
        int x = (int) Math.floor(Math.min(left, right)) - margin;
        int y = (int) Math.floor(Math.min(top, bottom)) - margin;
        int maxX = (int) Math.ceil(Math.max(left, right)) + margin;
        int maxY = (int) Math.ceil(Math.max(top, bottom)) + margin;
        return new Rectangle(x, y, Math.max(1, maxX - x), Math.max(1, maxY - y));
    }

    private static Rectangle union(Rectangle first, Rectangle second) {
        if (first == null) {
            return second == null ? null : new Rectangle(second);
        }
        if (second == null) {
            return new Rectangle(first);
        }
        Rectangle result = new Rectangle(first);
        result.add(second);
        return result;
    }

    private void repaintUnion(Rectangle first, Rectangle second) {
        repaintIfPresent(union(first, second));
    }

    private void repaintIfPresent(Rectangle dirty) {
        if (dirty != null && !dirty.isEmpty()) {
            repaint(dirty);
        }
    }

    private void paintMarquee(Graphics2D graphics) {
        GateGeometry.Bounds rectangle = selectionRectangle(
                gestureStartWorldX, gestureStartWorldY, pointerWorldX, pointerWorldY);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setColor(new Color(45, 125, 220, 28));
            g2.fillRect(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
            g2.setColor(new Color(36, 112, 214));
            g2.setStroke(new BasicStroke((float) (1.0 / transform.scale())));
            g2.drawRect(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
        } finally {
            g2.dispose();
        }
    }

    static int galleryRowPitch(List<GateDef> definitions) {
        int largestRotatedDimension = 0;
        for (GateDef definition : definitions) {
            for (Facing facing : Facing.values()) {
                GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
                largestRotatedDimension = Math.max(largestRotatedDimension,
                        Math.max(bounds.width(), bounds.height()));
            }
        }
        return roundUpToGrid(largestRotatedDimension + GALLERY_ROW_MARGIN);
    }

    static int galleryFirstOriginY(List<GateDef> definitions) {
        int largestExtentAboveOrigin = 0;
        for (GateDef definition : definitions) {
            for (Facing facing : Facing.values()) {
                GateGeometry.Bounds bounds = GateGeometry.bounds(definition, facing);
                largestExtentAboveOrigin = Math.max(largestExtentAboveOrigin,
                        Math.max(0, -bounds.y()));
            }
        }
        return roundUpToGrid(GALLERY_HEADER_BOTTOM + largestExtentAboveOrigin);
    }

    private static int roundUpToGrid(int value) {
        return ((value + WORLD_GRID_SPACING - 1) / WORLD_GRID_SPACING) * WORLD_GRID_SPACING;
    }

    private void paintGallery(Graphics2D screenGraphics) {
        Graphics2D worldGraphics = (Graphics2D) screenGraphics.create();
        try {
            worldGraphics.translate(transform.translateX(), transform.translateY());
            worldGraphics.scale(transform.scale(), transform.scale());

            List<GateDef> definitions = gateLibrary.all();
            Facing[] facings = Facing.values();
            for (int column = 0; column < facings.length; column++) {
                double labelWorldX = GALLERY_FIRST_ORIGIN_X - 14 + column * GALLERY_COLUMN_SPACING;
                Point2D.Double label = transform.worldToScreen(labelWorldX, 28);
                screenGraphics.setColor(new Color(65, 70, 76));
                screenGraphics.setFont(screenGraphics.getFont().deriveFont(Font.BOLD, 11.0f));
                screenGraphics.drawString(facings[column].name(), (float) label.x, (float) label.y);
            }

            for (int row = 0; row < definitions.size(); row++) {
                GateDef definition = definitions.get(row);
                double originY = galleryFirstOriginY + row * galleryRowPitch;
                Point2D.Double rowLabel = transform.worldToScreen(8, originY + 4);
                screenGraphics.setColor(new Color(65, 70, 76));
                screenGraphics.setFont(screenGraphics.getFont().deriveFont(Font.PLAIN, 10.0f));
                screenGraphics.drawString(definition.id(), (float) rowLabel.x, (float) rowLabel.y);

                for (int column = 0; column < facings.length; column++) {
                    Facing facing = facings[column];
                    double originX = GALLERY_FIRST_ORIGIN_X + column * GALLERY_COLUMN_SPACING;
                    gateRenderer.render(worldGraphics, definition, facing, originX, originY);
                    if (showPinMarkers) {
                        paintPinMarkers(screenGraphics, definition, facing, originX, originY);
                    }
                }
            }
        } finally {
            worldGraphics.dispose();
        }
    }

    /** Pin circles are drawn in screen space but centered on the exact transformed pin endpoints. */
    private void paintPinMarkers(
            Graphics2D graphics,
            GateDef definition,
            Facing facing,
            double originWorldX,
            double originWorldY) {
        for (GateGeometry.Offset pin : definition.inputs()) {
            paintPinMarker(graphics, definition, pin, facing, originWorldX, originWorldY);
        }
        if (definition.output() != null) {
            paintPinMarker(graphics, definition, definition.output(),
                    facing, originWorldX, originWorldY);
        }
    }

    private void paintPinMarker(
            Graphics2D graphics,
            GateDef definition,
            GateGeometry.Offset pin,
            Facing facing,
            double originWorldX,
            double originWorldY) {
        GateGeometry.Offset offset = GateGeometry.pinOffset(definition, pin, facing);
        Point2D.Double screen = transform.worldToScreen(
                originWorldX + offset.x(), originWorldY + offset.y());
        int markerSize = 7;
        int x = (int) Math.round(screen.x - markerSize / 2.0);
        int y = (int) Math.round(screen.y - markerSize / 2.0);
        graphics.setColor(Color.WHITE);
        graphics.fillOval(x, y, markerSize, markerSize);
        graphics.setColor(new Color(25, 30, 35));
        graphics.drawOval(x, y, markerSize, markerSize);
    }
}
