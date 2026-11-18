package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;

/** Swing-free provider boundary, also used by deterministic scripted tests. */
public interface AiProvider {
    String complete(String systemPrompt, List<Turn> conversation, JsonObject schema)
            throws IOException, InterruptedException;
}
