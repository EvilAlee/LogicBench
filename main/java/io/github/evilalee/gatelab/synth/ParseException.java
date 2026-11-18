package io.github.evilalee.gatelab.synth;

/** Syntax error reported at a zero-based character position in the source expression. */
public final class ParseException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final int position;
    private final String detail;

    public ParseException(int position, String detail) {
        super(requirePosition(position) + ": " + requireDetail(detail));
        this.position = position;
        this.detail = detail;
    }

    public int position() {
        return position;
    }

    public String detail() {
        return detail;
    }

    private static int requirePosition(int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must not be negative");
        }
        return position;
    }

    private static String requireDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            throw new IllegalArgumentException("detail must not be blank");
        }
        return detail;
    }
}
