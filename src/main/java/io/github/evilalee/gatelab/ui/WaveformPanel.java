package io.github.evilalee.gatelab.ui;

import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SignalRecorder;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Scrollable timing-diagram view over a {@link SignalRecorder}.
 *
 * <p>The panel only reads the recorder. Call {@link #refresh()} after recording
 * another sample to resize/repaint the traces and follow the newest sample.</p>
 */
public final class WaveformPanel extends JPanel {
    static final int SAMPLE_WIDTH = 12;
    static final int ROW_HEIGHT = 28;

    private static final int LABEL_LEFT = 8;
    private static final int MIN_LABEL_WIDTH = 88;
    private static final int LABEL_TRACE_GAP = 14;
    private static final int RIGHT_PADDING = 12;
    private static final int MIN_CANVAS_WIDTH = 480;
    private static final int MIN_CANVAS_HEIGHT = 84;

    private static final BasicStroke SOLID_STROKE = new BasicStroke(1.5f);
    private static final BasicStroke DASHED_STROKE =
            new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10.0f, new float[]{3.0f, 3.0f}, 0.0f);

    private final SignalRecorder recorder;
    private final WaveformCanvas canvas;
    private final JScrollPane scrollPane;

    public WaveformPanel(SignalRecorder recorder) {
        this.recorder = java.util.Objects.requireNonNull(recorder, "recorder");
        setLayout(new java.awt.BorderLayout());
        setOpaque(true);

        canvas = new WaveformCanvas();
        scrollPane = new JScrollPane(
                canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(themeColor("Panel.background", Color.WHITE));
        add(scrollPane, java.awt.BorderLayout.CENTER);
        refresh();
    }

    /**
     * Revalidates and repaints the view using the recorder's current contents.
     * The recorder itself is never changed by this method.
     */
    public void refresh() {
        canvas.revalidate();
        scrollPane.revalidate();
        revalidate();
        repaint();
        SwingUtilities.invokeLater(this::scrollToLatestSample);
    }

    private void scrollToLatestSample() {
        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        horizontal.setValue(horizontal.getMaximum() - horizontal.getVisibleAmount());
    }

    private final class WaveformCanvas extends JPanel implements Scrollable {
        private WaveformCanvas() {
            setOpaque(true);
            setBackground(themeColor("Panel.background", Color.WHITE));
        }

        @Override
        public Dimension getPreferredSize() {
            int signalCount = recorder.labels().size();
            int sampleCount = recorder.sampleCount();
            int labelWidth = labelColumnWidth();
            int traceWidth = Math.max(1, sampleCount) * SAMPLE_WIDTH;
            int width = Math.max(MIN_CANVAS_WIDTH,
                    labelWidth + LABEL_TRACE_GAP + traceWidth + RIGHT_PADDING);
            int height = Math.max(MIN_CANVAS_HEIGHT, signalCount * ROW_HEIGHT);
            return new Dimension(width, height);
        }

        private int labelColumnWidth() {
            Font font = getFont();
            if (font == null) {
                font = UIManager.getFont("Label.font");
            }
            if (font == null) {
                font = new Font(Font.DIALOG, Font.PLAIN, 12);
            }
            FontMetrics metrics = getFontMetrics(font);
            int width = MIN_LABEL_WIDTH;
            for (String label : recorder.labels()) {
                width = Math.max(width, metrics.stringWidth(label));
            }
            return width + LABEL_LEFT * 2;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setColor(themeColor("Panel.background", Color.WHITE));
                g2.fillRect(0, 0, getWidth(), getHeight());

                java.util.List<String> labels = recorder.labels();
                int sampleCount = recorder.sampleCount();
                int traceRight = getWidth() - RIGHT_PADDING;

                Font paintFont = getFont();
                if (paintFont == null) {
                    paintFont = UIManager.getFont("Label.font");
                }
                if (paintFont == null) {
                    paintFont = new Font(Font.DIALOG, Font.PLAIN, 12);
                }
                g2.setFont(paintFont);
                g2.setColor(themeColor("Label.foreground", Color.DARK_GRAY));

                for (int row = 0; row < labels.size(); row++) {
                    int rowTop = row * ROW_HEIGHT;
                    String label = labels.get(row);
                    g2.drawString(label, LABEL_LEFT, rowTop + 18);

                    if (sampleCount > 0) {
                        paintTrace(g2, label, rowTop, sampleCount, traceRight);
                    }

                    if (row + 1 < labels.size()) {
                        g2.setStroke(new BasicStroke(1.0f));
                        g2.setColor(separatorColor());
                        g2.drawLine(0, rowTop + ROW_HEIGHT - 1,
                                getWidth(), rowTop + ROW_HEIGHT - 1);
                        g2.setColor(themeColor("Label.foreground", Color.DARK_GRAY));
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        private void paintTrace(Graphics2D graphics, String label,
                int rowTop, int sampleCount, int traceRight) {
            int middleY = rowTop + ROW_HEIGHT / 2;
            for (int index = 0; index < sampleCount; index++) {
                int x = sampleX(index, sampleCount, traceRight);
                Value value = recorder.valueAt(label, index);
                int y = levelY(value, rowTop);

                if (index > 0) {
                    Value previous = recorder.valueAt(label, index - 1);
                    int previousY = levelY(previous, rowTop);
                    if (previousY != y) {
                        graphics.setStroke(SOLID_STROKE);
                        graphics.drawLine(x, previousY, x, y);
                    }
                }

                if (value == Value.X) {
                    graphics.setStroke(DASHED_STROKE);
                    graphics.drawLine(x, middleY, x + SAMPLE_WIDTH, middleY);
                } else {
                    graphics.setStroke(SOLID_STROKE);
                    graphics.drawLine(x, y, x + SAMPLE_WIDTH, y);
                }
            }
            graphics.setStroke(SOLID_STROKE);
        }

        private int levelY(Value value, int rowTop) {
            return switch (value) {
                case ONE -> rowTop + 6;
                case ZERO -> rowTop + ROW_HEIGHT - 6;
                case X -> rowTop + ROW_HEIGHT / 2;
            };
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(MIN_CANVAS_WIDTH, MIN_CANVAS_HEIGHT);
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? ROW_HEIGHT : SAMPLE_WIDTH;
        }

        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL
                    ? Math.max(ROW_HEIGHT, visibleRect.height - ROW_HEIGHT)
                    : Math.max(SAMPLE_WIDTH, visibleRect.width - SAMPLE_WIDTH);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            if (!(getParent() instanceof JViewport viewport)) {
                return false;
            }
            return getPreferredSize().width <= viewport.getWidth();
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * Returns the left x-coordinate of a sample's 12-pixel cell. The final
     * sample's cell ends exactly at {@code traceRight}, keeping newest time
     * at the right edge of the trace.
     */
    static int sampleX(int sampleIndex, int sampleCount, int traceRight) {
        if (sampleCount < 1 || sampleIndex < 0 || sampleIndex >= sampleCount) {
            throw new IllegalArgumentException("sample index must be within a non-empty history");
        }
        return traceRight - (sampleCount - sampleIndex) * SAMPLE_WIDTH;
    }

    private static Color separatorColor() {
        Color color = UIManager.getColor("Separator.foreground");
        if (color == null) {
            color = UIManager.getColor("Component.borderColor");
        }
        return color == null ? new Color(210, 210, 210) : color;
    }

    private static Color themeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}
