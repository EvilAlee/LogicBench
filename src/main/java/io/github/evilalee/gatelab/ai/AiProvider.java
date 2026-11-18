package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/** Swing-free provider boundary, also used by deterministic scripted tests. */
public interface AiProvider {
    String complete(String systemPrompt, List<Turn> conversation, JsonObject schema)
            throws IOException, InterruptedException;

    /** Model-aware entry point; scripted providers can keep implementing the original method. */
    default String complete(String model, String systemPrompt,
                            List<Turn> conversation, JsonObject schema)
            throws IOException, InterruptedException {
        return complete(systemPrompt, conversation, schema);
    }

    /** Models to try, in configured priority order. */
    default List<String> models() {
        return List.of("default");
    }

    /** Redacts secrets before provider text is exposed to the service or UI. */
    default String redact(String text) {
        return text == null ? "" : text;
    }
}
