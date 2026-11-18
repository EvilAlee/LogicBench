package io.github.evilalee.gatelab.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headless connection probe that checks configured models in priority order. */
public final class AiConnectionTester {
    private AiConnectionTester() {
    }

    public record Result(
            boolean connected,
            String answeringModel,
            List<String> modelAttempts,
            String message) {
        public Result {
            answeringModel = answeringModel == null ? "" : answeringModel;
            modelAttempts = List.copyOf(modelAttempts == null ? List.of() : modelAttempts);
            message = message == null ? "" : message;
        }
    }

    public static Result test(AiProvider provider) throws InterruptedException {
        Objects.requireNonNull(provider, "provider");
        List<String> configuredModels = provider.models();
        if (configuredModels == null || configuredModels.isEmpty()) {
            configuredModels = List.of("default");
        }

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "OBJECT");
        JsonObject properties = new JsonObject();
        JsonObject ok = new JsonObject();
        ok.addProperty("type", "BOOLEAN");
        properties.add("ok", ok);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("ok");
        schema.add("required", required);

        List<String> outcomes = new ArrayList<>();
        ProviderFailure lastFailure = null;
        ProviderFailure lastRetryable = null;
        for (String model : new LinkedHashSet<>(configuredModels)) {
            try {
                provider.complete(model,
                        "Return a tiny JSON object with ok set to true.",
                        List.of(new Turn(Turn.Role.USER,
                                "Connection test. Return {\"ok\":true}.")),
                        schema);
                String safeModel = provider.redact(model);
                outcomes.add(safeModel + ": 200 OK");
                return new Result(true, safeModel, outcomes,
                        "Gemini connection OK via " + safeModel + ".");
            } catch (IOException exception) {
                ProviderFailure failure = classify(provider, exception, model);
                lastFailure = failure;
                if (failure.kind() == ProviderFailure.Kind.RETRYABLE) {
                    lastRetryable = failure;
                }
                String safeModel = provider.redact(model);
                String outcome = failure.httpStatus() >= 100
                        ? safeModel + ": HTTP " + failure.httpStatus()
                        : safeModel + ": NO HTTP RESPONSE - " + failure.getMessage();
                outcomes.add(provider.redact(outcome));

                // A rejected key is global to this credential, so no model fallback helps.
                if (failure.kind() == ProviderFailure.Kind.KEY_REJECTED
                        || failure.kind() == ProviderFailure.Kind.FATAL) {
                    break;
                }
            }
        }

        ProviderFailure report = lastFailure == null ? null
                : lastRetryable != null ? lastRetryable : lastFailure;
        String message = report == null
                ? "No Gemini models are configured."
                : failureMessage(provider, report);
        return new Result(false, "", outcomes, provider.redact(message));
    }

    private static ProviderFailure classify(
            AiProvider provider, IOException exception, String model) {
        if (exception instanceof ProviderFailure failure) {
            return new ProviderFailure(failure.kind(), provider.redact(failure.getMessage()),
                    provider.redact(failure.model()), failure.httpStatus());
        }
        String message = exception.getMessage() == null
                ? "Gemini request failed." : provider.redact(exception.getMessage());
        Matcher matcher = Pattern.compile("HTTP\\s+(\\d{3})",
                Pattern.CASE_INSENSITIVE).matcher(message);
        int status = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        ProviderFailure.Kind kind = status == 401 || status == 403
                || lower.contains("key rejected") ? ProviderFailure.Kind.KEY_REJECTED
                : status == 404 || lower.contains("model not found")
                    ? ProviderFailure.Kind.MODEL_UNAVAILABLE
                    : ProviderFailure.Kind.RETRYABLE;
        return new ProviderFailure(kind, provider.redact(message), provider.redact(model), status);
    }

    private static String failureMessage(AiProvider provider, ProviderFailure failure) {
        String model = provider.redact(failure.model());
        if (failure.kind() == ProviderFailure.Kind.KEY_REJECTED) {
            return "API key rejected (HTTP " + failure.httpStatus() + "). Model: " + model + ".";
        }
        if (failure.httpStatus() == 503 || failure.httpStatus() >= 500) {
            return "Gemini is overloaded (HTTP " + failure.httpStatus()
                    + ") on model " + model + ".";
        }
        if (failure.httpStatus() == 429) {
            return "Gemini rate limit reached (HTTP 429) on model " + model + ".";
        }
        if (failure.kind() == ProviderFailure.Kind.MODEL_UNAVAILABLE) {
            return "No configured Gemini model is available (HTTP 404). Last model: "
                    + model + ".";
        }
        if (failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timed out")) {
            return "Gemini request timed out on model " + model + ".";
        }
        return "Gemini connection unavailable on model " + model + ": "
                + provider.redact(failure.getMessage());
    }
}
