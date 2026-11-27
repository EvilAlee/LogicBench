package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PreviewSchedulerTest {
    @Test
    void newerGenerationMakesOlderWorkerResultStale() throws Exception {
        AtomicReference<PreviewScheduler> reference = new AtomicReference<>();
        long[] generations = new long[2];
        SwingUtilities.invokeAndWait(() -> {
            PreviewScheduler scheduler = new PreviewScheduler(300, () -> { });
            reference.set(scheduler);
            generations[0] = scheduler.schedule();
            generations[1] = scheduler.schedule();
            assertFalse(scheduler.isCurrent(generations[0]));
            assertTrue(scheduler.isCurrent(generations[1]));
            scheduler.invalidate();
            assertFalse(scheduler.isCurrent(generations[1]));
            scheduler.stop();
        });
        assertTrue(generations[1] > generations[0]);
    }

    @Test
    void twentyLineExpressionSchedulingKeepsEdtWorkBelowTwentyMillisPerEdit() throws Exception {
        String source = String.join("\\n", List.of(
                "I0 := A & B", "I1 := B ^ C", "I2 := C | D", "I3 := D & E",
                "I4 := E ^ F", "I5 := F | G", "I6 := G & H", "I7 := H ^ I",
                "I8 := I0 ^ I1", "I9 := I2 & I3", "I10 := I4 | I5", "I11 := I6 ^ I7",
                "O0 = I8 ^ I9", "O1 = I9 | I10", "O2 = I10 & I11", "O3 = I11 ^ I8",
                "O4 = (I8 & I10) | (I9 & I11)", "O5 = (I8 ^ I10) ^ (I9 ^ I11)",
                "O6 = (I8 | I9) & (I10 | I11)", "O7 = (I8 & I9) | (I10 & I11)"));
        assertEquals(20, source.lines().count());

        double[] averageMillis = new double[1];
        SwingUtilities.invokeAndWait(() -> {
            PreviewScheduler scheduler = new PreviewScheduler(300, () -> { });
            JButton generate = new JButton("Generate");
            JCheckBox optimize = new JCheckBox();
            JLabel status = new JLabel("Ready");
            JLabel note = new JLabel("16 rows");
            DefaultTableModel model = new DefaultTableModel(1, 1);
            JTable table = new JTable(model);
            JScrollPane scroll = new JScrollPane(table);

            Runnable markPending = () -> {
                generate.setEnabled(false);
                model.setDataVector(new Object[0][0], new Object[0]);
                note.setText("...");
                status.setForeground(Color.DARK_GRAY);
                status.setText("...");
                table.setEnabled(true);
                scroll.setVisible(true);
            };

            int requests = 100;
            long started = System.nanoTime();
            for (int index = 0; index < requests; index++) {
                ExpressionDialog.schedulePreviewRequest(
                        scheduler, source, markPending, optimize::setEnabled, () -> { });
            }
            averageMillis[0] = (System.nanoTime() - started)
                    / (double) requests / 1_000_000.0;
            scheduler.stop();
            assertFalse(generate.isEnabled());
            assertEquals("...", status.getText());
            assertEquals("...", note.getText());
        });

        System.err.printf("LogicBench perf baseline: expression.keystroke.edt=%.3f ms (100 coalesced requests)%n",
                averageMillis[0]);
        assertTrue(averageMillis[0] < 20.0,
                "EDT scheduling must stay below 20 ms; measured " + averageMillis[0] + " ms");
    }

    @Test
    void manyRapidRequestsCoalesceIntoOnePreviewComputation() throws Exception {
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<PreviewScheduler> reference = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            PreviewScheduler scheduler = new PreviewScheduler(90, () -> {
                computations.incrementAndGet();
                completed.countDown();
            });
            reference.set(scheduler);
            scheduler.schedule();
            scheduler.schedule();
            scheduler.schedule();
            scheduler.schedule();
            scheduler.schedule();
        });

        assertTrue(completed.await(2, TimeUnit.SECONDS),
                "debounced preview should run after the final request");
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(reference.get()::stop);
        assertEquals(1, computations.get());
    }
}
