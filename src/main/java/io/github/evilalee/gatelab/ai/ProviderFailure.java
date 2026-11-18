package io.github.evilalee.gatelab.ai;

import java.io.IOException;
import java.util.Objects;

/** A classified failure before a usable model answer has been returned. */
public final class ProviderFailure extends IOException {
    public enum Kind {
        /** HTTP 429/5xx and network/connect/timeout failures. */
        RETRYABLE,
        /** The selected model does not exist or is unavailable (HTTP 404). */
        MODEL_UNAVAILABLE,
        /** Authentication failed (HTTP 401/403 or invalid-key HTTP 400). */
        KEY_REJECTED,
        /** A non-retryable provider response that is not a model answer. */
        FATAL
    }

    private final Kind kind;
    private final String model;
    private final int httpStatus;

    public ProviderFailure(Kind kind, String message, String model, int httpStatus) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.model = model == null ? "" : model;
        this.httpStatus = httpStatus;
    }

    public Kind kind() { return kind; }
    public String model() { return model; }
    /** Returns -1 when no HTTP response was received. */
    public int httpStatus() { return httpStatus; }
}
