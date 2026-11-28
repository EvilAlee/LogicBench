package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.ComponentLibrary;
import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.io.SchematicClipboard;
import io.github.evilalee.gatelab.model.ComponentAttributeRegistry;
import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
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
import io.github.evilalee.gatelab.sim.ChipPowerChecker;
import io.github.evilalee.gatelab.sim.ClockStepper;
import io.github.evilalee.gatelab.sim.PinStateHelper;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.SchematicCompiler.SimulationResult;
import io.github.evilalee.gatelab.util.PerfLog;

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
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Grid canvas for the M4 schematic editor, with an optional M3 SVG gallery. */
public final class CanvasPanel extends JPanel {
    private static final int WORLD_GRID_SPACING = PlacedComponent.GRID_SPACING;
    private static final int REPEATED_PASTE_OFFSET = 20;
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
        PENDING_AUTO_WIRE_TARGET,
        DRAW_WIRE,
        MOVE_SELECTION,
        SELECT_RECTANGLE
    }

    private final ViewTransform transform = new ViewTransform(1.0, 24.0, 24.0);
    private final boolean galleryMode;
    private boolean readOnly;
    private Consumer<ComponentDefinition> componentDefinitionViewListener = ignored -> { };
    private final GateLibrary gateLibrary;
    private final ComponentLibrary componentLibrary;
    private final GateRenderer gateRenderer;
    private final ComponentInstanceRenderer componentInstanceRenderer =
            new ComponentInstanceRenderer();
    private Schematic schematic;
    private final SchematicHistory history = new SchematicHistory();
    private Runnable historyStateListener = () -> { };
    private Runnable editActionStateListener = () -> { };
    private final Runnable schematicSimulationListener = this::schematicChanged;
    private Consumer<Schematic> schematicReplacementListener = ignored -> { };
    private final List<Consumer<Schematic>> schematicListeners = new ArrayList<>();
    private Runnable componentLibraryChangedListener = () -> { };
    private Runnable signalHistoryClearListener = () -> { };
    private Runnable signalSampleListener = () -> { };
    private SimulationResult simulationResult;
    private PinStateHelper.Result pinStateCache;
    private SimulationResult pinStateCacheSimulation;
    private Schematic pinStateCacheSchematic;
    private final ClockStepper clockStepper = new ClockStepper();
    private long simulationRecomputeCount;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private final Set<WireSegment> selectedWires = new LinkedHashSet<>();

    private final int galleryFirstOriginY;
    private final int galleryRowPitch;
    private final GridPatternCache gridPatternCache = new GridPatternCache();
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
    private boolean showPinDots = true;
    private boolean chipsNeedPower = true;
    private String previewTypeId;
    private String previewComponentName;
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
    private final Map<String, Facing> componentFacingByName = new HashMap<>();

    private Gesture gesture = Gesture.NONE;
    private Point gestureStartScreen;
    private double gestureStartWorldX;
    private double gestureStartWorldY;
    private double pointerWorldX;
    private double pointerWorldY;
    private boolean pointerKnown;
    private String lastPasteText;
    private boolean hasPastedClipboardText;
    private int lastPasteDeltaX;
    private int lastPasteDeltaY;
    private boolean gestureMoved;
    private boolean additiveMarquee;
    private Map<String, PlacedComponent> moveStartComponents = Map.of();
    private Map<String, ComponentInstance> moveStartInstances = Map.of();
    private Set<WireSegment> moveStartWires = Set.of();
    private Connectivity.Point wireStartPoint;
    private Connectivity.PinPosition gestureStartPin;
    private WireSegment pendingWireHit;
    private AutoWireRouter.Endpoint pendingAutoWireStart;
    private Connectivity.PinPosition pendingAutoWireStartPin;
    private Connectivity.PinPosition pendingAutoWireTargetPin;
    private boolean wireAxisChosen;
    private boolean wireHorizontalFirst;

    public CanvasPanel() {
        this(false);
    }

    public CanvasPanel(boolean galleryMode) {
        this.galleryMode = galleryMode;
        this.gateLibrary = GateLibrary.load();
        this.componentLibrary = ComponentLibrary.load();
        this.gateRenderer = new GateRenderer(gateLibrary);
        this.schematic = new Schematic(gateLibrary.all());
        this.schematic.addChangeListener(schematicSimulationListener);
        recomputeSimulation();
        List<GateDef> visibleGalleryDefinitions = galleryDefinitions(gateLibrary.all());
        this.galleryFirstOriginY = galleryMode ? galleryFirstOriginY(visibleGalleryDefinitions) : 0;
        this.galleryRowPitch = galleryMode ? galleryRowPitch(visibleGalleryDefinitions) : 0;

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

    /** The built-in and user-defined component catalogue used by the explorer. */
    public ComponentLibrary componentLibrary() {
        return componentLibrary;
    }

    /** Installs the explorer refresh callback used after a definition is saved. */
    public void setComponentLibraryChangedListener(Runnable listener) {
        componentLibraryChangedListener = listener == null ? () -> { } : listener;
    }

    /** Notifies the explorer that the in-memory user library has changed. */
    public void notifyComponentLibraryChanged() {
        componentLibraryChangedListener.run();
    }

    /** Installs the action used to open a component's read-only definition view. */
    public void setComponentDefinitionViewListener(
            Consumer<ComponentDefinition> listener) {
        componentDefinitionViewListener = listener == null ? ignored -> { } : listener;
    }

    /** Prevents schematic editing while retaining pan, zoom, and definition navigation. */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
        if (readOnly) {
            setEditMode();
        }
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
        clearAutoWirePending();
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
        statusMessageListener.accept(statusBarText());
        recordSignalSample();
    }

    public SimulationResult simulationResult() { return simulationResult; }

    public void setShowPinDots(boolean show) {
        if (showPinDots != show) {
            showPinDots = show;
            repaint();
        }
    }

    public boolean showPinDots() {
        return showPinDots;
    }

    /** Enables the transient, non-persisted DIP supply requirement. */
    public void setChipsNeedPower(boolean required) {
        if (chipsNeedPower == required) return;
        chipsNeedPower = required;
        recomputeSimulation();
        recordSignalSample();
    }

    public boolean chipsNeedPower() {
        return chipsNeedPower;
    }

    /** Compatibility alias for callers using the former diagnostic name. */
    @Deprecated
    public void setRequirePowerOnChips(boolean required) {
        setChipsNeedPower(required);
    }

    /** Compatibility alias for callers using the former diagnostic name. */
    @Deprecated
    public boolean requirePowerOnChips() {
        return chipsNeedPower();
    }

    public List<ChipPowerChecker.PowerIssue> chipPowerIssues() {
        if (simulationResult == null) return List.of();
        return ChipPowerChecker.check(schematic, simulationResult, chipsNeedPower);
    }

    private String statusBarText() {
        if (chipsNeedPower && simulationResult != null) {
            String powerText = ChipPowerChecker.statusText(schematic, simulationResult, true);
            if (!powerText.isBlank()) {
                return powerText + " — chip outputs are X; turn off Simulate > Chips need power "
                        + "to use ideal logic.";
            }
        }
        return simulationStatusText();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        String fallback = super.getToolTipText(event);
        if (galleryMode || simulationResult == null) {
            return fallback;
        }

        Point2D.Double mouse = new Point2D.Double(event.getX(), event.getY());
        double hitRadius = Math.max(
                8.0, PinDotStyle.radiusForZoom(transform.scale()) + 4.0);
        double bestDistanceSquared = hitRadius * hitRadius;
        PinStateHelper.Pin bestPin = null;
        for (PinStateHelper.Pin pin : pinStateDiagnostics().pins().values()) {
            Point2D.Double screen = transform.worldToScreen(
                    pin.position().x(), pin.position().y());
            double dx = screen.x - mouse.x;
            double dy = screen.y - mouse.y;
            double distanceSquared = dx * dx + dy * dy;
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestPin = pin;
            }
        }
        if (bestPin == null) {
            return fallback;
        }
        PinStateHelper.Pin selectedPin = bestPin;
        String value = switch (selectedPin.value()) {
            case ZERO -> "0";
            case ONE -> "1";
            case X -> "X";
        };
        ComponentInstance instance = schematic.componentInstances().stream()
                .filter(candidate -> candidate.id().equals(selectedPin.position().componentId()))
                .findFirst().orElse(null);
        if (instance != null) {
            ComponentDefinition definition = schematic.componentDefinitionFor(instance);
            if (definition.isChipStyle()) {
                Integer number = definition.ports().stream()
                        .filter(port -> port.name().equals(selectedPin.position().pinId()))
                        .map(ComponentDefinition.Port::pin)
                        .findFirst().orElse(null);
                if (number != null) {
                    return "pin " + number + ": " + selectedPin.position().pinId() + " = " + value;
                }
            }
        }
        return selectedPin.position().pinId() + " = " + value;
    }

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
            simulationResult = clockStepper.step(schematic, simulationResult, chipsNeedPower);
        } finally {
            schematic.addChangeListener(schematicSimulationListener);
        }
        simulationRecomputeCount++;
        repaint();
        statusMessageListener.accept(statusBarText());
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
            simulationResult = clockStepper.reset(schematic, chipsNeedPower);
        } finally {
            schematic.addChangeListener(schematicSimulationListener);
        }
        simulationRecomputeCount++;
        repaint();
        statusMessageListener.accept(statusBarText());
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
        replaceWithCircuit(generated, false, false);
    }

    /** Replaces topology as one undo step and resets every source and stored gate value. */
    public void replaceWithSequentialCircuit(Schematic generated) {
        replaceWithCircuit(generated, false, true);
    }

    /** Replaces topology as one undo step while retaining the tidy result's switch values. */
    public void replaceWithTidiedCircuit(Schematic tidied) {
        replaceWithCircuit(tidied, true, false);
    }

    private void replaceWithCircuit(
            Schematic replacement, boolean preserveInputValues, boolean resetSimulationState) {
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
        if (resetSimulationState) {
            resetSimulation();
        } else {
            recomputeSimulation();
        }
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
        for (ComponentInstance instance : schematic.componentInstances()) {
            ComponentInstance.Geometry bounds =
                    instance.geometry(schematic.componentDefinitionFor(instance));
            minX = Math.min(minX, instance.x());
            minY = Math.min(minY, instance.y());
            maxX = Math.max(maxX, instance.x() + bounds.width());
            maxY = Math.max(maxY, instance.y() + bounds.height());
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
        if (readOnly || !history.undo(schematic)) {
            notifyHistoryStateChanged();
            return false;
        }
        clearSelectionAfterHistoryChange();
        return true;
    }

    public boolean redo() {
        if (readOnly || !history.redo(schematic)) {
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
        simulationResult = SchematicCompiler.compileAndSettle(
                schematic, simulationResult, chipsNeedPower);
        simulationRecomputeCount++;
        repaint();
        statusMessageListener.accept(statusBarText());
    }

    public void resetInputs() {
        if (!schematic.resetInputs()) recomputeSimulation();
    }

    public List<WireSegment> selectedWires() {
        return List.copyOf(selectedWires);
    }

    /** Whether Cut, Copy, or Duplicate currently has an editable selection. */
    public boolean hasSelection() {
        return !readOnly && activeTool != Tool.ADD
                && (!selectedIds.isEmpty() || !selectedWires.isEmpty());
    }

    public void setEditActionStateListener(Runnable listener) {
        editActionStateListener = listener == null ? () -> { } : listener;
        editActionStateListener.run();
    }

    /** Copies the current mixed selection to the system clipboard. */
    public boolean copySelectionToClipboard() {
        if (!hasSelection()) {
            return false;
        }
        Optional<String> encoded = selectedClipboardText();
        if (encoded.isEmpty() || !writeSystemClipboard(encoded.get())) {
            statusMessageListener.accept("Could not access the system clipboard.");
            return false;
        }
        lastPasteText = encoded.get();
        hasPastedClipboardText = false;
        return true;
    }

    /** Cuts the current selection only after the clipboard write succeeds. */
    public boolean cutSelectionToClipboard() {
        if (!hasSelection()) {
            return false;
        }
        Optional<String> encoded = selectedClipboardText();
        if (encoded.isEmpty() || !writeSystemClipboard(encoded.get())) {
            statusMessageListener.accept("Could not access the system clipboard.");
            return false;
        }
        lastPasteText = encoded.get();
        hasPastedClipboardText = false;
        removeCurrentSelection();
        return true;
    }

    /** Duplicates the current selection without reading or writing the system clipboard. */
    public boolean duplicateSelection() {
        if (!hasSelection()) {
            return false;
        }
        Optional<String> encoded = selectedClipboardText();
        if (encoded.isEmpty()) {
            return false;
        }
        lastPasteText = encoded.get();
        hasPastedClipboardText = false;
        return applyClipboardPaste(encoded.get(), true);
    }

    /** Pastes only a valid LogicBench marker payload from the system clipboard. */
    public boolean pasteFromClipboard() {
        if (readOnly || galleryMode || activeTool == Tool.ADD) {
            return false;
        }
        Optional<String> clipboard = readSystemClipboard();
        return clipboard.isPresent() && applyClipboardPaste(clipboard.get(), false);
    }

    public boolean canPasteFromClipboard() {
        if (readOnly || galleryMode || activeTool == Tool.ADD) {
            return false;
        }
        Optional<String> clipboard = readSystemClipboard();
        return clipboard.isPresent()
                && SchematicClipboard.decode(
                        clipboard.get(), schematic.gateDefinitions(), componentLibrary).isPresent();
    }

    private Optional<String> selectedClipboardText() {
        return SchematicClipboard.encode(schematic, selectedIds, selectedWires);
    }

    private boolean applyClipboardPaste(String clipboardText, boolean forceOffset) {
        Optional<SchematicClipboard.Payload> decoded = SchematicClipboard.decode(
                clipboardText, schematic.gateDefinitions(), componentLibrary);
        if (decoded.isEmpty()) {
            return false;
        }
        boolean repeated = !forceOffset && hasPastedClipboardText
                && clipboardText.equals(lastPasteText);
        Integer pointerX = !forceOffset && !repeated && pointerKnown
                ? (int) Math.floor(pointerWorldX + 0.5) : null;
        Integer pointerY = pointerX == null
                ? null : (int) Math.floor(pointerWorldY + 0.5);
        int fallbackX = repeated ? Math.addExact(lastPasteDeltaX, REPEATED_PASTE_OFFSET)
                : REPEATED_PASTE_OFFSET;
        int fallbackY = repeated ? Math.addExact(lastPasteDeltaY, REPEATED_PASTE_OFFSET)
                : REPEATED_PASTE_OFFSET;

        beginHistoryGesture();
        Optional<SchematicClipboard.PasteResult> pasted;
        try {
            pasted = SchematicClipboard.paste(
                    schematic, decoded.get(), pointerX, pointerY, fallbackX, fallbackY);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            history.cancelPending();
            statusMessageListener.accept("Paste failed: " + exception.getMessage());
            return false;
        }
        if (pasted.isEmpty()) {
            history.cancelPending();
            statusMessageListener.accept(
                    "No safe paste location was found within 20 grid cells.");
            return false;
        }

        SchematicClipboard.PasteResult result = pasted.get();
        selectedIds.clear();
        selectedIds.addAll(result.componentIds());
        selectedWires.clear();
        selectedWires.addAll(result.wires());
        lastPasteText = clipboardText;
        hasPastedClipboardText = true;
        lastPasteDeltaX = result.deltaX();
        lastPasteDeltaY = result.deltaY();
        commitHistoryGesture();
        notifySelectionChanged();
        if (result.notices().isEmpty()) {
            statusMessageListener.accept("");
        } else {
            statusMessageListener.accept(String.join(" ", result.notices()));
        }
        repaint();
        return true;
    }

    private void removeCurrentSelection() {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        SchematicClipboard.deleteSelection(
                schematic, history, selectedIds, selectedWires);
        selectedIds.clear();
        selectedWires.clear();
        notifyHistoryStateChanged();
        notifySelectionChanged();
        repaintIfPresent(oldSelectionBounds);
    }

    private static Optional<String> readSystemClipboard() {
        try {
            Transferable contents = Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return Optional.empty();
            }
            Object value = contents.getTransferData(DataFlavor.stringFlavor);
            return value instanceof String text ? Optional.of(text) : Optional.empty();
        } catch (HeadlessException | IllegalStateException | SecurityException
                | UnsupportedFlavorException | IOException exception) {
            return Optional.empty();
        }
    }

    private static boolean writeSystemClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            return true;
        } catch (HeadlessException | IllegalStateException | SecurityException exception) {
            return false;
        }
    }

    /** Selected black-box instance ids, exposed to selection observers and headless tests. */
    public List<String> selectedInstanceIds() {
        return selectedInstances().stream().map(ComponentInstance::id).toList();
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
        if (readOnly) {
            return false;
        }
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
        return typeId.equals("input") || typeId.equals("output")
                || typeId.equals("clock") || typeId.equals("constant");
    }

    private void notifyAttributeStateChanged() {
        attributeStateListener.run();
    }

    private void schematicChanged() {
        recomputeSimulation();
        notifyAttributeStateChanged();
    }


    /** Enters repeated-click placement mode for a gate or black-box component. */
    public void enterAddMode(String typeId) {
        if (readOnly) {
            return;
        }
        if (typeId != null && typeId.startsWith("component:")) {
            enterComponentAddMode(typeId.substring("component:".length()));
            return;
        }
        if (galleryMode) {
            return;
        }
        String family = gateLibrary.get(typeId).family();
        if (activeTool == Tool.ADD && family.equals(previewTypeId)
                && previewComponentName == null) {
            requestFocusInWindow();
            return;
        }
        clearAutoWirePending();
        previewTypeId = family;
        previewComponentName = null;
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

    /** Enters repeated-click placement mode for one component-library definition. */
    public void enterComponentAddMode(String definitionName) {
        if (galleryMode || readOnly) {
            return;
        }
        ComponentDefinition definition = componentLibrary.get(definitionName);
        if (activeTool == Tool.ADD && definition.name().equals(previewComponentName)) {
            requestFocusInWindow();
            return;
        }
        clearAutoWirePending();
        previewTypeId = null;
        previewComponentName = definition.name();
        previewFacing = componentFacingByName.getOrDefault(definition.name(), Facing.EAST);
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
                union(previewScreenBounds(), gestureVisualScreenBounds()),
                autoWirePreviewScreenBounds());
        clearAutoWirePending();
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
        if (readOnly) {
            return;
        }
        Rectangle oldTransientBounds = union(
                union(previewScreenBounds(), gestureVisualScreenBounds()),
                autoWirePreviewScreenBounds());
        clearAutoWirePending();
        previewTypeId = null;
        activeTool = Tool.POKE;
        resetGesture();
        toolModeListener.accept(activeTool);
        notifyAttributeStateChanged();
        requestFocusInWindow();
        repaintIfPresent(oldTransientBounds);
    }

    /** One-shot tree drag/drop placement with one undo snapshot for either item kind. */
    public void placeFromDrop(String typeId, Point screenPoint) {
        if (readOnly || galleryMode) {
            return;
        }
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        if (typeId.startsWith("component:")) {
            String definitionName = typeId.substring("component:".length());
            ComponentDefinition definition = componentLibrary.get(definitionName);
            Facing facing = componentFacingByName.getOrDefault(definitionName, Facing.EAST);
            beginHistoryGesture();
            schematic.addInstance(definition,
                    snapToGrid(world.x), snapToGrid(world.y), facing);
            commitHistoryGesture();
            componentFacingByName.put(definitionName, facing);
            selectedIds.clear();
            selectedWires.clear();
            setEditMode();
            notifySelectionChanged();
            repaint();
            return;
        }

        String family = gateLibrary.get(typeId).family();
        Facing facing = toolFacingByFamily.getOrDefault(family, Facing.EAST);
        beginHistoryGesture();
        PlacedComponent placed = placeAtWorld(family, world.x, world.y, facing);
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
                if (!galleryMode && !readOnly) {
                    action.run();
                }
            }
        });
    }

    private void installDropHandler() {
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return !galleryMode && !readOnly && support.isDrop()
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

    private ComponentInstance componentInstanceAt(Point screenPoint) {
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        return schematic.componentInstanceAt(
                (int) Math.floor(world.x), (int) Math.floor(world.y)).orElse(null);
    }

    private void openComponentDefinition(ComponentInstance instance) {
        componentDefinitionViewListener.accept(schematic.componentDefinitionFor(instance));
    }

    private boolean showComponentInstancePopup(MouseEvent event) {
        ComponentInstance instance = componentInstanceAt(event.getPoint());
        if (instance == null) {
            return false;
        }
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem viewDefinition = new javax.swing.JMenuItem("View definition");
        viewDefinition.addActionListener(ignored -> openComponentDefinition(instance));
        popup.add(viewDefinition);
        popup.show(this, event.getX(), event.getY());
        return true;
    }

    private void installMouseControls() {
        MouseAdapter mouseControls = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)
                        && !galleryMode && activeTool == Tool.EDIT) {
                    ComponentInstance instanceHit = componentInstanceAt(event.getPoint());
                    if (instanceHit != null) {
                        openComponentDefinition(instanceHit);
                        return;
                    }
                    Point2D.Double world = transform.screenToWorld(event.getX(), event.getY());
                    WireSegment wireHit = schematic.wireAt(
                            world.x, world.y, 6.0 / transform.scale()).orElse(null);
                    if (wireHit != null) {
                        selectWireNet(wireHit);
                        return;
                    }
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
                if (event.isPopupTrigger() && showComponentInstancePopup(event)) {
                    return;
                }
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
                if (galleryMode || readOnly || !SwingUtilities.isLeftMouseButton(event)) {
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
                if (readOnly) {
                    return;
                }
                if (activeTool == Tool.EDIT) {
                    if (gesture == Gesture.PENDING_WIRE_START) {
                        Rectangle oldPreviewBounds = wirePreviewScreenBounds();
                        updateWireGestureMovement(event.getPoint());
                        if (gestureMoved) {
                            if (gestureStartPin != null) {
                                beginHistoryGesture();
                            }
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
                    Rectangle newPreviewBounds = previewScreenBounds();
                    if (!oldPreviewBounds.equals(newPreviewBounds)) {
                        repaintUnion(oldPreviewBounds, newPreviewBounds);
                    }
                } else if (activeTool == Tool.EDIT && !readOnly
                        && pendingAutoWireStart != null && gesture == Gesture.NONE) {
                    Rectangle oldBounds = autoWirePreviewScreenBounds();
                    pointerFromScreen(event.getPoint());
                    repaintUnion(oldBounds, autoWirePreviewScreenBounds());
                } else if (activeTool == Tool.EDIT && !readOnly && gesture == Gesture.NONE) {
                    Point2D.Double world = transform.screenToWorld(event.getX(), event.getY());
                    pointerWorldX = world.x;
                    pointerWorldY = world.y;
                    pointerKnown = true;
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger() && showComponentInstancePopup(event)) {
                    return;
                }
                if (panning) {
                    pointerFromScreen(event.getPoint());
                    panning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(event) || readOnly) {
                    return;
                }

                if (activeTool == Tool.ADD && gesture == Gesture.ADD_CLICK) {
                    updateGestureMovement(event.getPoint());
                    if (!gestureMoved
                            && (previewTypeId != null || previewComponentName != null)) {
                        Rectangle previewBounds = previewScreenBounds();
                        if (previewComponentName != null) {
                            ComponentDefinition definition = componentLibrary.get(previewComponentName);
                            schematic.addInstance(
                                    definition, previewX, previewY, previewFacing);
                            componentFacingByName.put(definition.name(), previewFacing);
                        } else {
                            placeAtWorld(previewTypeId, previewX, previewY, previewFacing);
                        }
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
        pointerKnown = true;
        gestureStartScreen = event.getPoint();
        gestureMoved = false;

        boolean additive = event.isShiftDown() || event.isControlDown();
        Connectivity.PinPosition pin = pinNear(world.x, world.y);
        double wireTolerance = 6.0 / transform.scale();
        WireSegment wireHit = schematic.wireAt(world.x, world.y, wireTolerance).orElse(null);

        if (pendingAutoWireStart != null) {
            Rectangle oldAutoWireBounds = autoWirePreviewScreenBounds();
            if (pin != null) {
                if (!pin.equals(pendingAutoWireStartPin)) {
                    pendingAutoWireTargetPin = pin;
                    gestureStartPin = pin;
                    gesture = Gesture.PENDING_AUTO_WIRE_TARGET;
                }
                repaintUnion(oldAutoWireBounds, autoWirePreviewScreenBounds());
                return;
            }
            clearAutoWirePending();
            repaintIfPresent(oldAutoWireBounds);
        }

        if (wireHit != null && selectedWires.contains(wireHit) && !additive) {
            gesture = Gesture.MOVE_SELECTION;
            captureSelectionForMove();
            moveStartWires = Set.copyOf(selectedWires);
            beginHistoryGesture();
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }
        if (wireHit != null && additive) {
            if (!selectedWires.add(wireHit)) {
                selectedWires.remove(wireHit);
            }
            notifySelectionChanged();
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }

        // Electrical pin hit-testing precedes instance/gate body hit-testing.
        if (pin != null) {
            gestureStartPin = pin;
            wireStartPoint = new Connectivity.Point(pin.x(), pin.y());
            wireAxisChosen = false;
            wireHorizontalFirst = true;
            pendingWireHit = wireHit;
            gesture = Gesture.PENDING_WIRE_START;
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
        ComponentInstance instanceHit = schematic.componentInstanceAt(hitX, hitY).orElse(null);
        if (instanceHit != null) {
            if (additive) {
                if (!selectedIds.add(instanceHit.id())) {
                    selectedIds.remove(instanceHit.id());
                }
                notifySelectionChanged();
                gesture = Gesture.NONE;
                repaintUnion(oldSelectionBounds, selectionScreenBounds());
                return;
            }
            if (!selectedIds.contains(instanceHit.id())) {
                selectedIds.clear();
                selectedWires.clear();
                selectedIds.add(instanceHit.id());
                notifySelectionChanged();
            }
            gesture = Gesture.MOVE_SELECTION;
            captureSelectionForMove();
            moveStartWires = Set.copyOf(selectedWires);
            beginHistoryGesture();
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }

        PlacedComponent hit = schematic.componentAt(hitX, hitY).orElse(null);
        if (hit != null && additive) {
            if (!selectedIds.add(hit.id())) {
                selectedIds.remove(hit.id());
            }
            notifySelectionChanged();
            gesture = Gesture.NONE;
            repaintUnion(oldSelectionBounds, selectionScreenBounds());
            return;
        }

        if (hit == null) {
            if (!additive) {
                selectedIds.clear();
                selectedWires.clear();
                notifySelectionChanged();
            }
            additiveMarquee = additive;
            gesture = Gesture.SELECT_RECTANGLE;
            moveStartComponents = Map.of();
            moveStartInstances = Map.of();
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
        captureSelectionForMove();
        moveStartWires = Set.copyOf(selectedWires);
        beginHistoryGesture();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private void captureSelectionForMove() {
        Map<String, PlacedComponent> startingComponents = new LinkedHashMap<>();
        for (PlacedComponent component : selectedComponents()) {
            startingComponents.put(component.id(), component);
        }
        moveStartComponents = Map.copyOf(startingComponents);

        Map<String, ComponentInstance> startingInstances = new LinkedHashMap<>();
        for (ComponentInstance instance : selectedInstances()) {
            startingInstances.put(instance.id(), instance);
        }
        moveStartInstances = Map.copyOf(startingInstances);
    }

    boolean hasPendingAutoWireStart() {
        return pendingAutoWireStart != null;
    }

    AutoWireRouter.Endpoint endpointForPin(Connectivity.PinPosition pin) {
        java.util.Objects.requireNonNull(pin, "pin");
        PlacedComponent component = schematic.components().stream()
                .filter(candidate -> candidate.id().equals(pin.componentId()))
                .findFirst().orElse(null);
        if (component != null) {
            GateDef definition = schematic.definitionFor(component);
            boolean output = definition.output() != null && pin.pinId().equals("out");
            return new AutoWireRouter.Endpoint(
                    new Connectivity.Point(pin.x(), pin.y()), component.facing(), output);
        }

        ComponentInstance instance = schematic.componentInstances().stream()
                .filter(candidate -> candidate.id().equals(pin.componentId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "unknown component pin: " + pin.componentId() + "." + pin.pinId()));
        ComponentInstance.PinGeometry geometry = instance.geometry(
                schematic.componentDefinitionFor(instance)).pins().stream()
                .filter(candidate -> candidate.name().equals(pin.pinId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "unknown instance pin: " + pin.componentId() + "." + pin.pinId()));
        return new AutoWireRouter.Endpoint(
                new Connectivity.Point(pin.x(), pin.y()), instance.rotation(),
                geometry.direction() == ComponentDefinition.Direction.OUT);
    }

    private void armAutoWire(Connectivity.PinPosition pin) {
        pendingAutoWireStartPin = pin;
        pendingAutoWireStart = endpointForPin(pin);
        statusMessageListener.accept("");
    }

    private void clearAutoWirePending() {
        pendingAutoWireStart = null;
        pendingAutoWireStartPin = null;
        pendingAutoWireTargetPin = null;
    }

    private Rectangle completeAutoWire(Connectivity.PinPosition targetPin) {
        Connectivity.PinPosition sourcePin = pendingAutoWireStartPin;
        if (sourcePin == null || targetPin == null) {
            return null;
        }
        AutoWireRouter.Endpoint sourceEndpoint = endpointForPin(sourcePin);
        AutoWireRouter.Endpoint targetEndpoint = endpointForPin(targetPin);
        Connectivity.Result topology = Connectivity.analyze(
                schematic.pinPositions(), schematic.wires());
        Connectivity.PinPosition clickedSourcePin = sourcePin;
        Connectivity.PinPosition clickedTargetPin = targetPin;
        Connectivity.Net sourceNet = topology.nets().stream()
                .filter(net -> net.pins().contains(clickedSourcePin)).findFirst().orElse(null);
        Connectivity.Net targetNet = topology.nets().stream()
                .filter(net -> net.pins().contains(clickedTargetPin)).findFirst().orElse(null);
        if (sourceNet == null || targetNet == null) {
            statusMessageListener.accept("Could not identify one of the selected pin nets");
            return null;
        }
        if (sourceNet.equals(targetNet)) {
            statusMessageListener.accept("These pins are already connected");
            return null;
        }

        if (sourceEndpoint.output() && targetEndpoint.output()) {
            statusMessageListener.accept("Cannot connect two output pins (two drivers)");
            return null;
        }
        int sourceDrivers = outputDriverCount(sourceNet);
        int targetDrivers = outputDriverCount(targetNet);
        if (!sourceEndpoint.output() && !targetEndpoint.output()
                && (sourceDrivers > 0 || targetDrivers > 0)) {
            statusMessageListener.accept(
                    "Cannot connect two input pins when either net is already driven");
            return null;
        }
        if (sourceDrivers > 0 && targetDrivers > 0) {
            statusMessageListener.accept("Cannot connect nets with multiple output drivers");
            return null;
        }

        if (!sourceEndpoint.output() && targetEndpoint.output()) {
            Connectivity.PinPosition pinSwap = sourcePin;
            sourcePin = targetPin;
            targetPin = pinSwap;
            AutoWireRouter.Endpoint endpointSwap = sourceEndpoint;
            sourceEndpoint = targetEndpoint;
            targetEndpoint = endpointSwap;
        }

        List<GateGeometry.Bounds> obstacles = new ArrayList<>();
        for (PlacedComponent component : schematic.components()) {
            GateGeometry.Bounds relative = GateGeometry.bounds(
                    schematic.definitionFor(component), component.facing());
            obstacles.add(new GateGeometry.Bounds(
                    component.x() + relative.x(), component.y() + relative.y(),
                    relative.width(), relative.height()));
        }
        for (ComponentInstance instance : schematic.componentInstances()) {
            obstacles.add(instance.bounds(schematic.componentDefinitionFor(instance)));
        }
        Connectivity.PinPosition routeSourcePin = sourcePin;
        Connectivity.PinPosition routeTargetPin = targetPin;
        List<Connectivity.Point> otherPins = schematic.pinPositions().stream()
                .filter(pin -> !pin.equals(routeSourcePin) && !pin.equals(routeTargetPin))
                .map(pin -> new Connectivity.Point(pin.x(), pin.y()))
                .toList();

        AutoWireRouter.Request request = new AutoWireRouter.Request(
                sourceEndpoint, targetEndpoint, obstacles, otherPins,
                schematic.wires(), topology.junctions());
        AutoWireRouter.Route route = AutoWireRouter.route(request);
        if (route.segments().isEmpty()) {
            statusMessageListener.accept("Could not create a wire between these pins");
            return null;
        }

        beginHistoryGesture();
        Rectangle routeBounds = null;
        for (WireSegment segment : route.segments()) {
            schematic.addWire(segment);
            routeBounds = union(routeBounds,
                    wireSegmentScreenBounds(segment, DIRTY_REGION_MARGIN + 3));
        }
        commitHistoryGesture();
        statusMessageListener.accept(route.usedFallback()
                ? "Could not find a clean route; drew a direct wire" : "");
        return routeBounds;
    }

    private int outputDriverCount(Connectivity.Net net) {
        int drivers = 0;
        for (Connectivity.PinPosition pin : net.pins()) {
            if (endpointForPin(pin).output()) {
                drivers++;
            }
        }
        return drivers;
    }

    private Connectivity.PinPosition pinNear(double worldX, double worldY) {
        Connectivity.PinPosition nearest = null;
        double nearestDistance = PlacedComponent.GRID_SPACING / 2.0;
        for (Connectivity.PinPosition pin : schematic.pinPositions()) {
            double distance = Math.hypot(worldX - pin.x(), worldY - pin.y());
            if (distance < nearestDistance) {
                nearest = pin;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void startWireGesture(int x, int y) {
        beginHistoryGesture();
        gestureStartPin = null;
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

    private List<WireSegment> autoWirePreviewSegments() {
        if (pendingAutoWireStart == null) {
            return List.of();
        }
        Connectivity.Point start = pendingAutoWireStart.position();
        int targetX = snapToGrid(pointerWorldX);
        int targetY = snapToGrid(pointerWorldY);
        boolean horizontalFirst = Math.abs(targetX - start.x())
                >= Math.abs(targetY - start.y());
        int cornerX = horizontalFirst ? targetX : start.x();
        int cornerY = horizontalFirst ? start.y() : targetY;
        List<WireSegment> preview = new ArrayList<>(2);
        if (start.x() != cornerX || start.y() != cornerY) {
            preview.add(new WireSegment(start.x(), start.y(), cornerX, cornerY));
        }
        if (cornerX != targetX || cornerY != targetY) {
            preview.add(new WireSegment(cornerX, cornerY, targetX, targetY));
        }
        return List.copyOf(preview);
    }

    private Rectangle autoWirePreviewScreenBounds() {
        if (pendingAutoWireStart == null) {
            return null;
        }
        Point2D.Double screen = transform.worldToScreen(
                pendingAutoWireStart.position().x(), pendingAutoWireStart.position().y());
        Rectangle result = new Rectangle(
                (int) Math.floor(screen.x) - 9, (int) Math.floor(screen.y) - 9, 18, 18);
        for (WireSegment segment : autoWirePreviewSegments()) {
            result = union(result, wireSegmentScreenBounds(segment, DIRTY_REGION_MARGIN + 3));
        }
        return result;
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

    /** Returns all segments in the Connectivity net containing the supplied wire. */
    static Set<WireSegment> collectNetSegments(Schematic schematic, WireSegment wire) {
        java.util.Objects.requireNonNull(schematic, "schematic");
        java.util.Objects.requireNonNull(wire, "wire");
        return Connectivity.analyze(schematic.pinPositions(), schematic.wires()).nets().stream()
                .filter(net -> net.segments().contains(wire))
                .findFirst()
                .map(Connectivity.Net::segments)
                .orElseGet(Set::of);
    }

    private void selectWireClick(WireSegment wire) {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        selectedIds.clear();
        selectedWires.clear();
        selectedWires.add(wire);
        notifySelectionChanged();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private void selectWireNet(WireSegment wire) {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        selectedIds.clear();
        selectedWires.clear();
        selectedWires.addAll(collectNetSegments(schematic, wire));
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
        Rectangle oldGestureBounds = union(
                gestureVisualScreenBounds(), autoWirePreviewScreenBounds());
        pointerFromScreen(event.getPoint());
        if (gesture == Gesture.PENDING_AUTO_WIRE_TARGET) {
            Rectangle addedWireBounds = completeAutoWire(pendingAutoWireTargetPin);
            resetGesture();
            clearAutoWirePending();
            repaintUnion(oldGestureBounds,
                    union(addedWireBounds, selectionScreenBounds()));
            return;
        }
        if (gesture == Gesture.PENDING_WIRE_START || gesture == Gesture.DRAW_WIRE) {
            updateWireGestureMovement(event.getPoint());
        } else {
            updateGestureMovement(event.getPoint());
        }

        if (gesture == Gesture.PENDING_WIRE_START && !gestureMoved) {
            if (gestureStartPin != null) {
                armAutoWire(gestureStartPin);
            } else if (pendingWireHit != null) {
                selectWireClick(pendingWireHit);
            }
            resetGesture();
            repaintUnion(oldGestureBounds,
                    union(autoWirePreviewScreenBounds(), selectionScreenBounds()));
            return;
        }
        if (gesture == Gesture.PENDING_WIRE_START) {
            if (gestureStartPin != null) {
                beginHistoryGesture();
            }
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
            Set<String> movingIds = new LinkedHashSet<>(moveStartComponents.keySet());
            movingIds.addAll(moveStartInstances.keySet());
            Schematic.MoveResult result = schematic.moveSelection(
                    movingIds, moveStartWires, deltaX, deltaY);
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
            for (ComponentInstance instance :
                    schematic.componentInstancesIntersecting(rectangle)) {
                selectedIds.add(instance.id());
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
        pointerKnown = true;
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
        if (readOnly) {
            return;
        }
        if (activeTool == Tool.ADD) {
            Rectangle oldPreviewBounds = previewScreenBounds();
            if (previewComponentName != null) {
                previewFacing = facing;
                componentFacingByName.put(previewComponentName, facing);
            } else if (previewTypeId != null) {
                setToolAttribute(previewTypeId, "facing", facing.name());
                previewFacing = toolFacingByFamily.getOrDefault(previewTypeId, facing);
            }
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
        if (readOnly || (selectedIds.isEmpty() && selectedWires.isEmpty())
                || activeTool == Tool.ADD) {
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

    private List<ComponentInstance> selectedInstances() {
        List<ComponentInstance> selected = new ArrayList<>();
        for (ComponentInstance instance : schematic.componentInstances()) {
            if (selectedIds.contains(instance.id())) {
                selected.add(instance);
            }
        }
        return List.copyOf(selected);
    }

    private void notifySelectionChanged() {
        selectionListener.accept(selectedComponents());
        notifyAttributeStateChanged();
        editActionStateListener.run();
    }

    private void resetGesture() {
        history.cancelPending();
        gesture = Gesture.NONE;
        gestureStartScreen = null;
        gestureMoved = false;
        additiveMarquee = false;
        moveStartComponents = Map.of();
        moveStartInstances = Map.of();
        moveStartWires = Set.of();
        wireStartPoint = null;
        gestureStartPin = null;
        pendingWireHit = null;
        pendingAutoWireTargetPin = null;
        wireAxisChosen = false;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        long paintStartedNanos = PAINT_STATS_ENABLED ? System.nanoTime() : 0L;
        long perfPaintStarted = PerfLog.start();
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
            PerfLog.record("canvas.paint.total", perfPaintStarted);
        }
    }

    /** Paints a reusable screen-space grid texture anchored to the world lattice. */
    private void paintGrid(Graphics graphics) {
        long started = PerfLog.start();
        BufferedImage grid = gridPatternCache.imageFor(
                getWidth(), getHeight(), transform.scale(),
                transform.translateX(), transform.translateY(),
                WORLD_GRID_SPACING, MIN_SCREEN_GRID_SPACING);
        if (grid != null) {
            graphics.drawImage(grid, 0, 0, null);
        }
        PerfLog.record("canvas.grid", started);
    }

    long gridCacheRebuildCount() {
        return gridPatternCache.keyChangeCount();
    }

    long gridFullRebuildCount() {
        return gridPatternCache.fullRebuildCount();
    }

    long gridIncrementalUpdateCount() {
        return gridPatternCache.incrementalUpdateCount();
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
        record PendingLabel(PlacedComponent component, GateDef definition, int x, int y) {
        }
        List<PendingLabel> pendingLabels = new ArrayList<>();
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

            long wiresStarted = PerfLog.start();
            for (WireSegment wire : schematic.wires()) {
                boolean selected = selectedWires.contains(wire);
                boolean moving = selected && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                WireSegment rendered = moving
                        ? translateWire(wire, moveDeltaX, moveDeltaY) : wire;
                if (screenClip.intersects(wireSegmentScreenBounds(rendered, 5))) {
                    paintWireSegment(worldGraphics, rendered, simulationResult.valueOf(wire), selected);
                }
            }
            PerfLog.record("canvas.wires", wiresStarted);

            long componentsStarted = PerfLog.start();
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
                pendingLabels.add(new PendingLabel(component, definition, x, y));

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

            Set<String> unpoweredChipIds = simulationResult == null
                    ? Set.of()
                    : ChipPowerChecker.unpoweredInstanceIds(
                            schematic, simulationResult, chipsNeedPower);
            for (ComponentInstance instance : schematic.componentInstances()) {
                ComponentDefinition definition = schematic.componentDefinitionFor(instance);
                boolean selected = selectedIds.contains(instance.id());
                boolean moving = selected && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                ComponentInstance rendered = moving
                        ? translatedInstance(instance, moveDeltaX, moveDeltaY) : instance;
                ComponentInstance.Geometry geometry = rendered.geometry(definition);
                int labelMargin = Math.max(
                        DIRTY_REGION_MARGIN + 12,
                        (int) Math.ceil(36.0 * transform.scale()));
                if (screenClip.intersects(componentInstanceScreenBounds(
                        rendered, definition, 0, 0, labelMargin))) {
                    if (moving) {
                        worldGraphics.setComposite(AlphaComposite.SrcOver.derive(0.72f));
                    } else {
                        worldGraphics.setComposite(AlphaComposite.SrcOver);
                    }
                    paintComponentInstance(worldGraphics, rendered, definition, geometry,
                            true, unpoweredChipIds.contains(instance.id()));
                    worldGraphics.setComposite(AlphaComposite.SrcOver);
                    if (selected) {
                        paintInstanceSelectionOutline(worldGraphics, rendered, definition);
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

            long pinsStarted = PerfLog.start();
            paintPinDots(screenGraphics, moveDeltaX, moveDeltaY);
            PerfLog.record("canvas.pinDots", pinsStarted);

            for (ComponentInstance instance : schematic.componentInstances()) {
                ComponentDefinition definition = schematic.componentDefinitionFor(instance);
                boolean moving = selectedIds.contains(instance.id())
                        && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                ComponentInstance rendered = moving
                        ? translatedInstance(instance, moveDeltaX, moveDeltaY) : instance;
                if (screenClip.intersects(componentInstanceScreenBounds(
                        rendered, definition, 0, 0, DIRTY_REGION_MARGIN + 12))) {
                    componentInstanceRenderer.renderLabels(
                            worldGraphics, rendered, definition,
                            rendered.geometry(definition), transform.scale());
                }
            }

            for (PendingLabel label : pendingLabels) {
                paintComponentLabel(
                        screenGraphics, label.component(), label.definition(), label.x(), label.y());
                paintGateName(
                        worldGraphics, label.component(), label.definition(), label.x(), label.y());
            }

            if ((gesture == Gesture.DRAW_WIRE && gestureMoved)
                    || (pendingAutoWireStart != null
                            && (gesture == Gesture.NONE
                                    || gesture == Gesture.PENDING_AUTO_WIRE_TARGET))) {
                Graphics2D previewGraphics = (Graphics2D) worldGraphics.create();
                try {
                    previewGraphics.setColor(new Color(36, 112, 214));
                    previewGraphics.setStroke(new BasicStroke(
                            2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            10.0f, new float[]{5.0f, 4.0f}, 0.0f));
                    List<WireSegment> previewSegments = pendingAutoWireStart != null
                            && gesture != Gesture.DRAW_WIRE
                            ? autoWirePreviewSegments() : wirePreviewSegments();
                    for (WireSegment segment : previewSegments) {
                        previewGraphics.drawLine(
                                segment.x1(), segment.y1(), segment.x2(), segment.y2());
                    }
                    if (pendingAutoWireStart != null) {
                        Connectivity.Point anchor = pendingAutoWireStart.position();
                        previewGraphics.drawOval(anchor.x() - 5, anchor.y() - 5, 10, 10);
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
            } else if (activeTool == Tool.ADD && previewComponentName != null) {
                ComponentDefinition definition = componentLibrary.get(previewComponentName);
                ComponentInstance preview = new ComponentInstance(
                        definition.name(), definition.hash(), "Preview", "",
                        previewX, previewY, previewFacing);
                ComponentInstance.Geometry geometry = preview.geometry(definition);
                int labelMargin = Math.max(
                        DIRTY_REGION_MARGIN + 12,
                        (int) Math.ceil(36.0 * transform.scale()));
                if (screenClip.intersects(componentInstanceScreenBounds(
                        preview, definition, 0, 0, labelMargin))) {
                    worldGraphics.setComposite(AlphaComposite.SrcOver.derive(0.42f));
                    paintComponentInstance(worldGraphics, preview, definition, geometry, false);
                    worldGraphics.setComposite(AlphaComposite.SrcOver);
                }
            }

            if (gesture == Gesture.SELECT_RECTANGLE && gestureMoved
                    && screenClip.intersects(marqueeScreenBounds())) {
                paintMarquee(worldGraphics);
            }
            PerfLog.record("canvas.components", componentsStarted);
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

    /** Draws a reusable black-box symbol without changing its electrical terminals. */
    private void paintComponentInstance(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            boolean deferLabels) {
        paintComponentInstance(graphics, instance, definition, geometry, deferLabels, false);
    }

    private void paintComponentInstance(
            Graphics2D graphics,
            ComponentInstance instance,
            ComponentDefinition definition,
            ComponentInstance.Geometry geometry,
            boolean deferLabels,
            boolean unpowered) {
        if (deferLabels) {
            componentInstanceRenderer.renderSymbol(
                    graphics, instance, definition, geometry, transform.scale(),
                    unpowered ? Color.RED : null);
        } else {
            componentInstanceRenderer.render(
                    graphics, instance, definition, geometry, transform.scale());
        }
    }

    private PinStateHelper.Result pinStateDiagnostics() {
        if (pinStateCache == null || pinStateCacheSimulation != simulationResult
                || pinStateCacheSchematic != schematic) {
            pinStateCache = PinStateHelper.analyze(schematic, simulationResult);
            pinStateCacheSimulation = simulationResult;
            pinStateCacheSchematic = schematic;
        }
        return pinStateCache;
    }

    private void paintPinDots(Graphics2D graphics, int moveDeltaX, int moveDeltaY) {
        if (!showPinDots || simulationResult == null
                || !PinDotStyle.visibleAtZoom(transform.scale())) {
            return;
        }

        PinStateHelper.Result result = pinStateDiagnostics();
        Map<String, PlacedComponent> componentsById = new HashMap<>();
        for (PlacedComponent component : schematic.components()) {
            componentsById.put(component.id(), component);
        }
        Rectangle clip = graphics.getClipBounds();

        for (PinStateHelper.Pin pin : result.pins().values()) {
            Connectivity.PinPosition position = pin.position();
            boolean moving = gesture == Gesture.MOVE_SELECTION && gestureMoved
                    && selectedIds.contains(position.componentId());
            int dx = moving ? moveDeltaX : 0;
            int dy = moving ? moveDeltaY : 0;
            Point2D.Double screen = transform.worldToScreen(
                    position.x() + dx, position.y() + dy);
            PinDotStyle.Style style = PinDotStyle.forPin(
                    pin.state(), isGateInput(componentsById.get(position.componentId()), position),
                    transform.scale());
            paintPinDot(graphics, screen, style, clip);
        }

        for (Connectivity.Point endpoint : result.danglingWireEnds()) {
            boolean moving = gesture == Gesture.MOVE_SELECTION && gestureMoved
                    && selectedWires.stream().anyMatch(wire -> isEndpoint(wire, endpoint));
            Point2D.Double screen = transform.worldToScreen(
                    endpoint.x() + (moving ? moveDeltaX : 0),
                    endpoint.y() + (moving ? moveDeltaY : 0));
            PinDotStyle.Style style = new PinDotStyle.Style(
                    PinDotStyle.danglingEndColor(), false,
                    PinDotStyle.radiusForZoom(transform.scale()));
            paintPinDot(graphics, screen, style, clip);
        }
    }

    private static boolean isGateInput(
            PlacedComponent component, Connectivity.PinPosition position) {
        return component != null && isGateComponent(component)
                && position.pinId().startsWith("in");
    }

    private static boolean isEndpoint(WireSegment wire, Connectivity.Point point) {
        return (wire.x1() == point.x() && wire.y1() == point.y())
                || (wire.x2() == point.x() && wire.y2() == point.y());
    }

    private static void paintPinDot(
            Graphics2D graphics,
            Point2D.Double center,
            PinDotStyle.Style style,
            Rectangle clip) {
        int x = (int) Math.round(center.x);
        int y = (int) Math.round(center.y);
        int radius = style.radius();
        int diameter = radius * 2 + 1;
        Rectangle bounds = new Rectangle(x - radius, y - radius, diameter, diameter);
        if (clip != null && !clip.intersects(bounds)) {
            return;
        }

        Graphics2D dot = (Graphics2D) graphics.create();
        try {
            dot.setColor(style.color());
            if (style.filled()) {
                dot.fillOval(bounds.x, bounds.y, diameter, diameter);
            } else {
                dot.setStroke(new BasicStroke(1.4f));
                dot.drawOval(bounds.x, bounds.y, diameter, diameter);
            }
        } finally {
            dot.dispose();
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
                boolean labelOnLeft = component.typeId().equals("input")
                        || component.typeId().equals("constant");
                double labelX = labelOnLeft
                        ? originX + bounds.x() : originX + bounds.x() + bounds.width();
                double labelY = originY + bounds.y() + bounds.height() / 2.0;
                Point2D.Double screen = transform.worldToScreen(labelX, labelY);
                x = (float) (labelOnLeft
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
            case "constant" -> component.fixedSourceValue();
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

    private void paintInstanceSelectionOutline(
            Graphics2D graphics, ComponentInstance instance, ComponentDefinition definition) {
        GateGeometry.Bounds bounds = instance.bounds(definition);
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setColor(new Color(36, 112, 214));
            g2.setStroke(new BasicStroke((float) (1.4 / transform.scale())));
            g2.drawRect(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        } finally {
            g2.dispose();
        }
    }

    private Rectangle previewScreenBounds() {
        if (previewComponentName != null) {
            ComponentDefinition definition = componentLibrary.get(previewComponentName);
            ComponentInstance preview = new ComponentInstance(
                    definition.name(), definition.hash(), "Preview", "",
                    previewX, previewY, previewFacing);
            int labelMargin = Math.max(
                    DIRTY_REGION_MARGIN + 12,
                    (int) Math.ceil(36.0 * transform.scale()));
            return componentInstanceScreenBounds(
                    preview, definition, 0, 0, labelMargin);
        }
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

    private Rectangle componentInstanceScreenBounds(
            ComponentInstance instance, int deltaX, int deltaY, int margin) {
        return componentInstanceScreenBounds(
                instance, schematic.componentDefinitionFor(instance), deltaX, deltaY, margin);
    }

    private Rectangle componentInstanceScreenBounds(
            ComponentInstance instance, ComponentDefinition definition,
            int deltaX, int deltaY, int margin) {
        ComponentInstance.Geometry geometry = instance.geometry(definition);
        return worldBoundsToScreen(new GateGeometry.Bounds(
                instance.x() + deltaX, instance.y() + deltaY,
                geometry.width(), geometry.height()), margin);
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
        for (ComponentInstance instance : selectedInstances()) {
            result = union(result, componentInstanceScreenBounds(
                    instance, 0, 0, DIRTY_REGION_MARGIN));
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
        for (ComponentInstance instance : moveStartInstances.values()) {
            result = union(result, componentInstanceScreenBounds(
                    instance, 0, 0, DIRTY_REGION_MARGIN));
            result = union(result, componentInstanceScreenBounds(
                    translatedInstance(instance, deltaX, deltaY), 0, 0, DIRTY_REGION_MARGIN));
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
            case PENDING_AUTO_WIRE_TARGET -> autoWirePreviewScreenBounds();
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

    private static ComponentInstance translatedInstance(
            ComponentInstance instance, int deltaX, int deltaY) {
        return new ComponentInstance(
                instance.definitionName(), instance.definitionHash(), instance.id(),
                instance.label(), instance.x() + deltaX, instance.y() + deltaY,
                instance.rotation());
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

    static List<GateDef> galleryDefinitions(List<GateDef> definitions) {
        return definitions.stream()
                .filter(definition -> !definition.family().equals("constant"))
                .toList();
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

            List<GateDef> definitions = galleryDefinitions(gateLibrary.all());
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
