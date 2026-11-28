package io.github.evilalee.gatelab.ui;

import javax.swing.Timer;
import java.util.Objects;

/** EDT-confined debounce timer and generation gate for expression previews. */
final class PreviewScheduler {
    private final Timer timer;
    private long generation;

    PreviewScheduler(int delayMillis, Runnable task) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("delayMillis must not be negative");
        }
        Objects.requireNonNull(task, "task");
        timer = new Timer(delayMillis, event -> task.run());
        timer.setRepeats(false);
        timer.setCoalesce(true);
    }

    long schedule() {
        generation = Math.incrementExact(generation);
        timer.restart();
        return generation;
    }

    long generation() {
        return generation;
    }

    boolean isCurrent(long candidate) {
        return candidate == generation;
    }

    long invalidate() {
        timer.stop();
        generation = Math.incrementExact(generation);
        return generation;
    }

    void stop() {
        timer.stop();
    }
}
