package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, Swing-free history of labelled simulation signal samples.
 *
 * <p>Labels are ordered by their first appearance. A signal that is introduced
 * after recording has started reads as unknown in all earlier samples.</p>
 */
public final class SignalRecorder {
    public static final int MAX_SAMPLES = 256;

    private final Set<String> knownLabels = new LinkedHashSet<>();
    private final List<String> labels = new ArrayList<>();
    private final List<Map<String, Value>> samples = new ArrayList<>();

    /**
     * Appends a snapshot of the supplied signal values.
     *
     * @param sample values for the signals at one logical time step
     */
    public void record(Map<String, Value> sample) {
        Objects.requireNonNull(sample, "sample");

        Map<String, Value> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : sample.entrySet()) {
            String label = Objects.requireNonNull(entry.getKey(), "signal label");
            if (label.isBlank()) {
                throw new IllegalArgumentException("signal label must not be blank");
            }
            Value value = Objects.requireNonNull(entry.getValue(),
                    "signal value for " + label);
            snapshot.put(label, value);
        }

        for (String label : snapshot.keySet()) {
            if (knownLabels.add(label)) {
                labels.add(label);
            }
        }

        samples.add(Collections.unmodifiableMap(snapshot));
        if (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /** Removes every recorded sample and forgets all signal labels. */
    public void clear() {
        samples.clear();
        labels.clear();
        knownLabels.clear();
    }

    /** Returns signal labels in the order they were first observed. */
    public List<String> labels() {
        return List.copyOf(labels);
    }

    /** Returns the number of retained samples. */
    public int sampleCount() {
        return samples.size();
    }

    /**
     * Returns one signal value at a retained sample index.
     *
     * @throws IllegalArgumentException if the label has never been recorded
     * @throws IndexOutOfBoundsException if the sample index is outside the
     *         retained history
     */
    public Value valueAt(String label, int index) {
        Objects.requireNonNull(label, "label");
        if (!knownLabels.contains(label)) {
            throw new IllegalArgumentException("unknown signal label: " + label);
        }
        Map<String, Value> sample = samples.get(index);
        return sample.getOrDefault(label, Value.X);
    }
}
