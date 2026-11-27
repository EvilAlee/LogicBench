package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
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
