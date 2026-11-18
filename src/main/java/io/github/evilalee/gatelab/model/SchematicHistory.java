package io.github.evilalee.gatelab.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Bounded snapshot history for schematic topology. Call begin/commit around one
 * user gesture so previews and intermediate drag positions never enter history.
 */
public final class SchematicHistory {
    public static final int MAX_HISTORY_STEPS = 100;

    private final Deque<SchematicSnapshot> undo = new ArrayDeque<>();
    private final Deque<SchematicSnapshot> redo = new ArrayDeque<>();
    private SchematicSnapshot pending;

    /** Captures the topology before a gesture; starting again replaces an abandoned gesture. */
    public void begin(Schematic schematic) {
        pending = SchematicSnapshot.capture(Objects.requireNonNull(schematic, "schematic"));
    }

    /** Commits one step only when the gesture changed components or wires. */
    public boolean commit(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        if (pending == null) {
            return false;
        }
        SchematicSnapshot before = pending;
        pending = null;
        SchematicSnapshot after = SchematicSnapshot.capture(schematic);
        if (before.equals(after)) {
            return false;
        }
        pushUndo(before);
        redo.clear();
        return true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public int undoDepth() {
        return undo.size();
    }

    public int redoDepth() {
        return redo.size();
    }

    public boolean undo(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        pending = null;
        if (undo.isEmpty()) {
            return false;
        }
        SchematicSnapshot current = SchematicSnapshot.capture(schematic);
        SchematicSnapshot target = undo.pop();
        redo.push(current);
        schematic.restoreSnapshot(target);
        return true;
    }

    public boolean redo(Schematic schematic) {
        Objects.requireNonNull(schematic, "schematic");
        pending = null;
        if (redo.isEmpty()) {
            return false;
        }
        SchematicSnapshot current = SchematicSnapshot.capture(schematic);
        SchematicSnapshot target = redo.pop();
        pushUndo(current);
        schematic.restoreSnapshot(target);
        return true;
    }

    public void cancelPending() {
        pending = null;
    }

    public void clear() {
        pending = null;
        undo.clear();
        redo.clear();
    }

    private void pushUndo(SchematicSnapshot snapshot) {
        undo.push(snapshot);
        if (undo.size() > MAX_HISTORY_STEPS) {
            undo.removeLast();
        }
    }
}
