package io.github.evilalee.gatelab.util;

import java.util.Locale;

/** Opt-in UI and expression-pipeline timings for local profiling. */
public final class PerfLog {
    private static final String PROPERTY = "logicbench.perf";

    private PerfLog() {
    }

    public static long start() {
        return enabled() ? System.nanoTime() : 0L;
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void record(String stage, long startedNanos) {
        if (startedNanos == 0L || !enabled()) {
            return;
        }
        double elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000.0;
        System.err.printf(Locale.ROOT, "LogicBench perf: %s=%.3f ms%n",
                stage, elapsedMillis);
    }
}
