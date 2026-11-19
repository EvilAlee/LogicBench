package io.github.evilalee.gatelab.ui;

/** Stable short names used when identifying built-in logic gates on the canvas. */
public final class GateNameRenderer {
    private GateNameRenderer() {
    }

    /**
     * Returns the compact display name for a gate family, or an empty string
     * for non-gate components and unknown catalogue entries.
     */
    public static String textForType(String typeId) {
        if (typeId == null) {
            return "";
        }
        return switch (typeId) {
            case "and" -> "AND";
            case "or" -> "OR";
            case "not" -> "NOT";
            case "nand" -> "NAND";
            case "nor" -> "NOR";
            case "xor" -> "XOR";
            case "xnor" -> "XNOR";
            case "buffer" -> "BUF";
            default -> "";
        };
    }
}
