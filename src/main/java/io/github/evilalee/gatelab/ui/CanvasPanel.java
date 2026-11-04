package io.github.evilalee.gatelab.ui;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;

/** Empty M2 work surface with a zoomable, pannable world grid. */
public final class CanvasPanel extends JPanel {
    private static final int WORLD_GRID_SPACING = 10;
    private static final double MIN_SCREEN_GRID_SPACING = 4.0;
    private static final double ZOOM_PER_WHEEL_NOTCH = 1.12;

    private final ViewTransform transform = new ViewTransform(1.0, 24.0, 24.0);
    private boolean spaceDown;
    private boolean panning;
    private Point lastPanPoint;

    public CanvasPanel() {
        setOpaque(true);
        setBackground(Color.WHITE);
        setFocusable(true);
        setPreferredSize(new Dimension(900, 700));
        setToolTipText("Middle-drag or Space + drag to pan; Ctrl + mouse wheel to zoom");

        installSpaceBinding();
        installMouseControls();
    }

    public ViewTransform viewTransform() {
        return transform;
    }

    void setSpaceDown(boolean down) {
        spaceDown = down;
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
}
