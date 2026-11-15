package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.io.GateLibrary;
import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.GateGeometry;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.util.List;

/** White M2 work surface, with an optional M3 SVG gallery. */
public final class CanvasPanel extends JPanel {
    private static final int WORLD_GRID_SPACING = 10;
    private static final double MIN_SCREEN_GRID_SPACING = 4.0;
    private static final double ZOOM_PER_WHEEL_NOTCH = 1.12;

    private static final int GALLERY_FIRST_ORIGIN_X = 90;
    private static final int GALLERY_FIRST_ORIGIN_Y = 54;
    private static final int GALLERY_COLUMN_SPACING = 165;
    private static final int GALLERY_ROW_SPACING = 66;

    private final ViewTransform transform = new ViewTransform(1.0, 24.0, 24.0);
    private final boolean galleryMode;
    private final GateLibrary gateLibrary;
    private final GateRenderer gateRenderer;
    private boolean showPinMarkers = true;
    private boolean spaceDown;
    private boolean panning;
    private Point lastPanPoint;

    public CanvasPanel() {
        this(false);
    }

    public CanvasPanel(boolean galleryMode) {
        this.galleryMode = galleryMode;
        this.gateLibrary = galleryMode ? GateLibrary.load() : null;
        this.gateRenderer = galleryMode ? new GateRenderer(gateLibrary) : null;

        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        setPreferredSize(new Dimension(900, 700));
        setToolTipText(galleryMode
                ? "Middle-drag or Space + drag to pan; Ctrl + wheel to zoom; G toggles pin markers"
                : "Middle-drag or Space + drag to pan; Ctrl + mouse wheel to zoom");

        installSpaceBinding();
        installMouseControls();
        if (galleryMode) {
            installGalleryKeyBinding();
        }
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
                showPinMarkers = !showPinMarkers;
                repaint();
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
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!panning || lastPanPoint == null) {
                    return;
                }
                transform.panBy(
                        event.getX() - lastPanPoint.x,
                        event.getY() - lastPanPoint.y);
                lastPanPoint = event.getPoint();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (panning) {
                    panning = false;
                    lastPanPoint = null;
                    setCursor(Cursor.getDefaultCursor());
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
                repaint();
                event.consume();
            }
        };

        addMouseListener(mouseControls);
        addMouseMotionListener(mouseControls);
        addMouseWheelListener(mouseControls);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);

        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            paintGrid(g2);
            if (galleryMode) {
                paintGallery(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintGrid(Graphics2D graphics) {
        double scale = transform.scale();
        double screenSpacing = WORLD_GRID_SPACING * scale;
        int stride = Math.max(1, (int) Math.ceil(
                MIN_SCREEN_GRID_SPACING / screenSpacing));
        double worldStep = WORLD_GRID_SPACING * stride;

        Point2D.Double worldTopLeft = transform.screenToWorld(0.0, 0.0);
        Point2D.Double worldBottomRight = transform.screenToWorld(
                getWidth(), getHeight());
        double firstX = Math.ceil(worldTopLeft.x / worldStep) * worldStep;
        double firstY = Math.ceil(worldTopLeft.y / worldStep) * worldStep;

        int dotSize = screenSpacing >= 8.0 ? 2 : 1;
        graphics.setColor(new Color(205, 209, 214));

        for (double worldY = firstY; worldY <= worldBottomRight.y; worldY += worldStep) {
            Point2D.Double rowStart = transform.worldToScreen(firstX, worldY);
            int screenY = (int) Math.round(rowStart.y);
            for (double worldX = firstX; worldX <= worldBottomRight.x; worldX += worldStep) {
                Point2D.Double point = transform.worldToScreen(worldX, worldY);
                int screenX = (int) Math.round(point.x);
                graphics.fillOval(screenX, screenY, dotSize, dotSize);
            }
        }
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
                double originY = GALLERY_FIRST_ORIGIN_Y + row * GALLERY_ROW_SPACING;
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
