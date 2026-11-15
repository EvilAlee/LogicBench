package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
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
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
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

    private enum Gesture {
        NONE,
        ADD_CLICK,
        MOVE_SELECTION,
        SELECT_RECTANGLE
    }

    private final ViewTransform transform = new ViewTransform(1.0, 24.0, 24.0);
    private final boolean galleryMode;
    private final GateLibrary gateLibrary;
    private final GateRenderer gateRenderer;
    private final Schematic schematic;
    private final Set<String> selectedIds = new LinkedHashSet<>();

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
    private String previewTypeId;
    private Facing previewFacing = Facing.EAST;
    private int previewX;
    private int previewY;
    private Consumer<List<PlacedComponent>> selectionListener = ignored -> { };
    private Consumer<Tool> toolModeListener = ignored -> { };

    private Gesture gesture = Gesture.NONE;
    private Point gestureStartScreen;
    private double gestureStartWorldX;
    private double gestureStartWorldY;
    private double pointerWorldX;
    private double pointerWorldY;
    private boolean gestureMoved;
    private boolean additiveMarquee;
    private Map<String, PlacedComponent> moveStartComponents = Map.of();

    public CanvasPanel() {
        this(false);
    }

    public CanvasPanel(boolean galleryMode) {
        this.galleryMode = galleryMode;
        this.gateLibrary = GateLibrary.load();
        this.gateRenderer = new GateRenderer(gateLibrary);
        this.schematic = new Schematic(gateLibrary.all());
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

    public void setSelectionListener(Consumer<List<PlacedComponent>> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
        notifySelectionChanged();
    }

    public void setToolModeListener(Consumer<Tool> listener) {
        toolModeListener = listener == null ? ignored -> { } : listener;
        toolModeListener.accept(activeTool);
    }

    /** Enters repeated-click placement mode for a catalogue type. */
    public void enterAddMode(String typeId) {
        if (galleryMode) {
            return;
        }
        String resolvedTypeId = gateLibrary.get(typeId).id();
        if (activeTool == Tool.ADD && resolvedTypeId.equals(previewTypeId)) {
            requestFocusInWindow();
            return;
        }
        previewTypeId = resolvedTypeId;
        previewFacing = Facing.EAST;
        selectedIds.clear();
        notifySelectionChanged();
        activeTool = Tool.ADD;
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
        requestFocusInWindow();
        repaintIfPresent(oldTransientBounds);
    }

    /** Selects the inert Poke tool; no input interaction is implemented in M4. */
    public void setPokeMode() {
        Rectangle oldTransientBounds = union(
                previewScreenBounds(), gestureVisualScreenBounds());
        previewTypeId = null;
        activeTool = Tool.POKE;
        resetGesture();
        toolModeListener.accept(activeTool);
        requestFocusInWindow();
        repaintIfPresent(oldTransientBounds);
    }

    /** One-shot tree drag/drop placement, sharing the same grid-snapped origin rule. */
    public void placeFromDrop(String typeId, Point screenPoint) {
        GateDef definition = gateLibrary.get(typeId);
        Point2D.Double world = transform.screenToWorld(screenPoint.x, screenPoint.y);
        PlacedComponent placed = placeAtWorld(
                definition.id(), world.x, world.y, Facing.EAST);
        selectedIds.clear();
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
        bindKey(KeyEvent.VK_RIGHT, "face-east", () -> setFacing(Facing.EAST));
        bindKey(KeyEvent.VK_DOWN, "face-south", () -> setFacing(Facing.SOUTH));
        bindKey(KeyEvent.VK_LEFT, "face-west", () -> setFacing(Facing.WEST));
        bindKey(KeyEvent.VK_UP, "face-north", () -> setFacing(Facing.NORTH));
    }

    private void bindKey(int keyCode, String actionName, Runnable action) {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
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
                    gesture = Gesture.ADD_CLICK;
                    gestureStartScreen = event.getPoint();
                    Rectangle oldPreviewBounds = previewScreenBounds();
                    pointerFromScreen(event.getPoint());
                    repaintUnion(oldPreviewBounds, previewScreenBounds());
                } else if (activeTool == Tool.EDIT) {
                    beginEditGesture(event);
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

    private void beginEditGesture(MouseEvent event) {
        Rectangle oldSelectionBounds = selectionScreenBounds();
        Point2D.Double world = transform.screenToWorld(event.getX(), event.getY());
        gestureStartWorldX = world.x;
        gestureStartWorldY = world.y;
        pointerWorldX = world.x;
        pointerWorldY = world.y;
        gestureStartScreen = event.getPoint();
        gestureMoved = false;

        int hitX = (int) Math.floor(world.x);
        int hitY = (int) Math.floor(world.y);
        PlacedComponent hit = schematic.componentAt(hitX, hitY).orElse(null);
        boolean shift = event.isShiftDown();

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
                notifySelectionChanged();
            }
            additiveMarquee = shift;
            gesture = Gesture.SELECT_RECTANGLE;
            moveStartComponents = Map.of();
            repaintUnion(oldSelectionBounds, marqueeScreenBounds());
            return;
        }

        if (!selectedIds.contains(hit.id())) {
            selectedIds.clear();
            selectedIds.add(hit.id());
            notifySelectionChanged();
        }
        gesture = Gesture.MOVE_SELECTION;
        Map<String, PlacedComponent> starting = new LinkedHashMap<>();
        for (PlacedComponent component : selectedComponents()) {
            starting.put(component.id(), component);
        }
        moveStartComponents = Map.copyOf(starting);
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
        updateGestureMovement(event.getPoint());

        if (gesture == Gesture.MOVE_SELECTION && gestureMoved) {
            int deltaX = snapToGrid(pointerWorldX - gestureStartWorldX);
            int deltaY = snapToGrid(pointerWorldY - gestureStartWorldY);
            for (PlacedComponent starting : moveStartComponents.values()) {
                schematic.move(starting.id(), starting.x() + deltaX, starting.y() + deltaY);
            }
            notifySelectionChanged();
        } else if (gesture == Gesture.SELECT_RECTANGLE && gestureMoved) {
            GateGeometry.Bounds rectangle = selectionRectangle(
                    gestureStartWorldX, gestureStartWorldY,
                    pointerWorldX, pointerWorldY);
            List<PlacedComponent> hits = schematic.componentsIntersecting(rectangle);
            if (!additiveMarquee) {
                selectedIds.clear();
            }
            for (PlacedComponent component : hits) {
                selectedIds.add(component.id());
            }
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
                typeId, snapToGrid(worldX), snapToGrid(worldY), facing);
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
            previewFacing = facing;
            repaintUnion(oldPreviewBounds, previewScreenBounds());
            return;
        }
        if (selectedIds.isEmpty()) {
            return;
        }
        Rectangle oldSelectionBounds = selectionScreenBounds();
        for (PlacedComponent component : selectedComponents()) {
            schematic.setFacing(component.id(), facing);
        }
        notifySelectionChanged();
        repaintUnion(oldSelectionBounds, selectionScreenBounds());
    }

    private void deleteSelection() {
        if (selectedIds.isEmpty() || activeTool == Tool.ADD) {
            return;
        }
        Rectangle oldSelectionBounds = selectionScreenBounds();
        for (String id : List.copyOf(selectedIds)) {
            schematic.remove(id);
        }
        selectedIds.clear();
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
    }

    private void resetGesture() {
        gesture = Gesture.NONE;
        gestureStartScreen = null;
        gestureMoved = false;
        additiveMarquee = false;
        moveStartComponents = Map.of();
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

            for (PlacedComponent component : schematic.components()) {
                boolean selected = selectedIds.contains(component.id());
                boolean moving = selected && gesture == Gesture.MOVE_SELECTION && gestureMoved;
                int x = component.x() + (moving ? moveDeltaX : 0);
                int y = component.y() + (moving ? moveDeltaY : 0);
                GateDef definition = gateLibrary.get(component.typeId());
                if (!screenClip.intersects(componentScreenBounds(
                        definition, component.facing(), x, y, DIRTY_REGION_MARGIN))) {
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

                if (selected) {
                    paintSelectionOutline(worldGraphics, definition,
                            component.facing(), x, y);
                }
            }

            if (activeTool == Tool.ADD && previewTypeId != null) {
                GateDef previewDefinition = gateLibrary.get(previewTypeId);
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
                gateLibrary.get(previewTypeId), previewFacing,
                previewX, previewY, DIRTY_REGION_MARGIN);
    }

    private Rectangle componentScreenBounds(
            PlacedComponent component, int deltaX, int deltaY, int margin) {
        GateDef definition = gateLibrary.get(component.typeId());
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
        return result;
    }

    private Rectangle movingSelectionScreenBounds() {
        int deltaX = gestureMoved ? snapToGrid(pointerWorldX - gestureStartWorldX) : 0;
        int deltaY = gestureMoved ? snapToGrid(pointerWorldY - gestureStartWorldY) : 0;
        Rectangle result = null;
        for (PlacedComponent component : moveStartComponents.values()) {
            result = union(result, componentScreenBounds(
                    component, deltaX, deltaY, DIRTY_REGION_MARGIN));
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
            default -> null;
        };
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
