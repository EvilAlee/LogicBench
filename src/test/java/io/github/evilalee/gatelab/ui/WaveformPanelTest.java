package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaveformPanelTest {
    @Test
    void sampleCellsAdvanceLeftToRightAndNewestCellEndsAtRightEdge() {
        int traceRight = 200;

        assertEquals(164, WaveformPanel.sampleX(0, 3, traceRight));
        assertEquals(176, WaveformPanel.sampleX(1, 3, traceRight));
        assertEquals(188, WaveformPanel.sampleX(2, 3, traceRight));
        assertEquals(traceRight,
                WaveformPanel.sampleX(2, 3, traceRight) + WaveformPanel.SAMPLE_WIDTH);
    }

    @Test
    void oneSampleOccupiesTheCellImmediatelyBeforeTheRightEdge() {
        assertEquals(88, WaveformPanel.sampleX(0, 1, 100));
    }

    @Test
    void sampleCoordinateRejectsEmptyHistoryAndOutOfRangeIndexes() {
        assertThrows(IllegalArgumentException.class,
                () -> WaveformPanel.sampleX(0, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> WaveformPanel.sampleX(-1, 2, 100));
        assertThrows(IllegalArgumentException.class,
                () -> WaveformPanel.sampleX(2, 2, 100));
    }
}
