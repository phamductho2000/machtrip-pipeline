package vn.machtrip.pipeline.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** Scriptable LlmClient for tests: records every request it receives and answers with the configured handler. */
public class FakeLlmClient implements LlmClient {

    private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<LlmRequest, LlmResult> handler = r -> {
        throw new LlmException("FakeLlmClient: no response scripted");
    };

    /** Every call returns this text (no token usage reported, so tokens are estimated). */
    public void respondWith(String text) {
        handler = r -> new LlmResult(text, null, null, r.model());
    }

    /** Every call returns this text and reports this usage. */
    public void respondWith(String text, int inputTokens, int outputTokens) {
        handler = r -> new LlmResult(text, inputTokens, outputTokens, r.model());
    }

    public void respondWith(Function<LlmRequest, LlmResult> f) {
        handler = f;
    }

    /** Answers the first call with {@code first}, every later call with {@code later}. */
    public void respondWith(String first, String later) {
        handler = r -> new LlmResult(requests.size() == 1 ? first : later, null, null, r.model());
    }

    public void failWith(String message) {
        handler = r -> {
            throw new LlmException(message);
        };
    }

    @Override
    public LlmResult complete(LlmRequest request) {
        requests.add(request);
        return handler.apply(request);
    }

    public List<LlmRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public void reset() {
        requests.clear();
    }
}
