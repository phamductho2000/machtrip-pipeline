package vn.machtrip.pipeline.extract;

/**
 * One LLM call. {@code jsonSchema} is null when structured output is not used; {@code idempotencyKey} is a
 * deterministic key per (video, prompt version, model) for gateways that support request ids.
 */
public record LlmRequest(String model, String system, String user, String jsonSchema, int maxOutputTokens,
                         String idempotencyKey) {
}
