package vn.machtrip.pipeline.extract;

/** The only way stage 2 talks to an LLM: through the Genway gateway, never a provider SDK or URL. */
public interface LlmClient {

    LlmResult complete(LlmRequest request);
}
