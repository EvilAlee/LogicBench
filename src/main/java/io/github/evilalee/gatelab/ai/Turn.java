package io.github.evilalee.gatelab.ai;

import java.util.Objects;

/** One text turn in the Gemini conversation. */
public record Turn(Role role, String text) {
    public enum Role { USER, MODEL }

    public Turn {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
    }

    public String geminiRole() {
        return role == Role.USER ? "user" : "model";
    }

    @Override
    public String toString() {
        return "Turn[role=" + role + ", text=<redacted>]";
    }
}
