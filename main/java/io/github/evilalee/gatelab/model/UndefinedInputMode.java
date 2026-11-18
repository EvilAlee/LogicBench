package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** Document option controlling how electrically bare gate input pins are compiled. */
public enum UndefinedInputMode {
    IGNORE("ignore"),
    ERROR("error");

    private final String jsonValue;

    UndefinedInputMode(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }

    public static UndefinedInputMode fromJsonValue(String value) {
        Objects.requireNonNull(value, "value");
        return switch (value) {
            case "ignore" -> IGNORE;
            case "error" -> ERROR;
            default -> throw new IllegalArgumentException(
                    "must be 'ignore' or 'error', got: " + value);
        };
    }
}
