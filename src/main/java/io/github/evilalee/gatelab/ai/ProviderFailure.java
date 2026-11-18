package io.github.evilalee.gatelab.ai;

import java.io.IOException;
import java.util.List;
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
    private final List<String> modelAttempts;

    public ProviderFailure(Kind kind, String message, String model, int httpStatus) {
        this(kind, message, model, httpStatus, List.of());
    }

    public ProviderFailure(Kind kind, String message, String model, int httpStatus,
                           List<String> modelAttempts) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.model = model == null ? "" : model;
        this.httpStatus = httpStatus;
        this.modelAttempts = List.copyOf(modelAttempts == null ? List.of() : modelAttempts);
    }

    public Kind kind() { return kind; }
    public String model() { return model; }
    /** Returns -1 when no HTTP response was received. */
    public int httpStatus() { return httpStatus; }
    public List<String> modelAttempts() { return modelAttempts; }

    public ProviderFailure withModelAttempts(List<String> attempts) {
        return new ProviderFailure(kind, getMessage(), model, httpStatus, attempts);
    }
}
