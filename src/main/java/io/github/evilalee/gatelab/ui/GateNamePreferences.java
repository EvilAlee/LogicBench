package io.github.evilalee.gatelab.ui;

import java.util.prefs.Preferences;

/** Application preference for showing built-in gate names; never part of a schematic. */
final class GateNamePreferences {
    private static final String SHOW_GATE_NAMES = "showGateNames";

    private GateNamePreferences() {
    }

    static boolean load() {
        try {
            return Preferences.userNodeForPackage(GateNamePreferences.class)
                    .getBoolean(SHOW_GATE_NAMES, true);
        } catch (IllegalStateException | SecurityException exception) {
            return true;
        }
    }

    static void save(boolean show) {
        try {
            Preferences.userNodeForPackage(GateNamePreferences.class)
                    .putBoolean(SHOW_GATE_NAMES, show);
        } catch (IllegalStateException | SecurityException ignored) {
            // The display preference is best-effort when the platform blocks preferences.
        }
    }
}
