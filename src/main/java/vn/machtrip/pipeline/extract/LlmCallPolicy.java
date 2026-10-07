package vn.machtrip.pipeline.extract;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Retry policy for calls to Genway. Genway already retries provider 429/5xx, so an HTTP error response is NEVER
 * retried here (it fails that video); only connection errors and timeouts are retried, at most {@code maxRetries}
 * times with exponential backoff.
 */
public class LlmCallPolicy {

    private static final Logger log = LoggerFactory.getLogger(LlmCallPolicy.class);

    private final int maxRetries;
    private final Duration initialBackoff;

    public LlmCallPolicy(int maxRetries, Duration initialBackoff) {
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
    }

    public <T> T execute(Supplier<T> call) {
        Duration delay = initialBackoff;
        for (int attempt = 0; ; attempt++) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                // 4xx, 429, 5xx: Genway answered, so the answer is final for this video
                throw new LlmException("Genway answered HTTP " + e.getStatusCode().value() + " (not retried)", e);
            } catch (ResourceAccessException e) {
                if (attempt >= maxRetries) {
                    throw new LlmException("Genway unreachable after " + (attempt + 1) + " attempt(s): "
                            + e.getMostSpecificCause().getClass().getSimpleName(), e);
                }
                log.warn("Genway connection/timeout error (attempt {}), retrying in {} ms: {}", attempt + 1,
                        delay.toMillis(), e.getMostSpecificCause().getClass().getSimpleName());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("interrupted while waiting to retry", ie);
                }
                delay = delay.multipliedBy(2);
            }
        }
    }
}
