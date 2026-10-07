package vn.machtrip.pipeline.extract;

/** Token counts are null when the gateway does not report usage; {@code model} is the model actually used. */
public record LlmResult(String text, Integer inputTokens, Integer outputTokens, String model) {
}
