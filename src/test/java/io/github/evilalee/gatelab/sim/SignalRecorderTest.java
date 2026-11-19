package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalRecorderTest {
    @Test
    void recordsSamplesAndReportsTheirValuesInTimeOrder() {
        SignalRecorder recorder = new SignalRecorder();

        recorder.record(Map.of("Clock", Value.ZERO, "Data", Value.ONE));
        recorder.record(Map.of("Clock", Value.ONE, "Data", Value.X));

        assertEquals(2, recorder.sampleCount());
        assertEquals(Value.ZERO, recorder.valueAt("Clock", 0));
        assertEquals(Value.ONE, recorder.valueAt("Data", 0));
        assertEquals(Value.ONE, recorder.valueAt("Clock", 1));
        assertEquals(Value.X, recorder.valueAt("Data", 1));
    }

    @Test
    void retainsOnlyTheNewest256SamplesAndDropsTheOldestFirst() {
        SignalRecorder recorder = new SignalRecorder();

        for (int index = 0; index < SignalRecorder.MAX_SAMPLES + 1; index++) {
            recorder.record(Map.of("Clock", index % 2 == 0 ? Value.ZERO : Value.ONE));
        }

        assertEquals(SignalRecorder.MAX_SAMPLES, recorder.sampleCount());
        // The original sample zero was evicted; the retained history begins at one.
        assertEquals(Value.ONE, recorder.valueAt("Clock", 0));
        assertEquals(Value.ZERO, recorder.valueAt("Clock", SignalRecorder.MAX_SAMPLES - 1));
    }

    @Test
    void clearRemovesSamplesAndForgetsLabels() {
        SignalRecorder recorder = new SignalRecorder();
        recorder.record(Map.of("A", Value.ONE));

        recorder.clear();

        assertEquals(0, recorder.sampleCount());
        assertTrue(recorder.labels().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> recorder.valueAt("A", 0));
    }

    @Test
    void signalsFirstSeenLaterReadUnknownInEarlierSamples() {
        SignalRecorder recorder = new SignalRecorder();
        recorder.record(Map.of("Clock", Value.ZERO));

        recorder.record(Map.of("Clock", Value.ONE, "Data", Value.ZERO));

        assertEquals(Value.X, recorder.valueAt("Data", 0));
        assertEquals(Value.ZERO, recorder.valueAt("Data", 1));
    }

    @Test
    void labelsKeepTheirFirstSeenOrder() {
        SignalRecorder recorder = new SignalRecorder();
        Map<String, Value> first = new LinkedHashMap<>();
        first.put("Clock", Value.ZERO);
        first.put("Data", Value.ONE);
        recorder.record(first);
        Map<String, Value> second = new LinkedHashMap<>();
        second.put("Data", Value.ZERO);
        second.put("Carry", Value.ONE);
        recorder.record(second);

        assertEquals(java.util.List.of("Clock", "Data", "Carry"), recorder.labels());
        assertEquals(3, recorder.labels().size());
    }

    @Test
    void recordTakesASnapshotAndRejectsInvalidSignalEntries() {
        SignalRecorder recorder = new SignalRecorder();
        Map<String, Value> sample = new LinkedHashMap<>();
        sample.put("A", Value.ONE);

        recorder.record(sample);
        sample.put("A", Value.ZERO);

        assertEquals(Value.ONE, recorder.valueAt("A", 0));
        assertThrows(IllegalArgumentException.class,
                () -> recorder.record(Map.of(" ", Value.ONE)));
        assertFalse(recorder.labels().isEmpty());
    }

    @Test
    void rejectsUnknownLabelsAndInvalidSampleIndexes() {
        SignalRecorder recorder = new SignalRecorder();
        recorder.record(Map.of("A", Value.ONE));

        assertThrows(IllegalArgumentException.class, () -> recorder.valueAt("B", 0));
        assertThrows(IndexOutOfBoundsException.class, () -> recorder.valueAt("A", 1));
        assertThrows(IndexOutOfBoundsException.class, () -> recorder.valueAt("A", -1));
    }
}
