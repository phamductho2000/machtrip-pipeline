package vn.machtrip.pipeline.extract;

/**
 * An LLM call failed. The message never contains the API key or the prompt.
 *
 * <p>{@code runFatal} marks failures that would hit every video the same way (bad or missing credentials, tenant not
 * allowed, unknown model, provider out of balance): the run stops and nothing is recorded per video, instead of
 * marking every video {@code failed}.
 */
public class LlmException extends RuntimeException {

    private final boolean runFatal;

    public LlmException(String message) {
        this(message, false);
    }

    public LlmException(String message, boolean runFatal) {
        super(message);
        this.runFatal = runFatal;
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
        this.runFatal = false;
    }

    public boolean isRunFatal() {
        return runFatal;
    }
}
