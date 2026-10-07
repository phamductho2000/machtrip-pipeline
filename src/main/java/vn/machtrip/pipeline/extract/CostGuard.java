package vn.machtrip.pipeline.extract;

import java.math.BigDecimal;

/**
 * Enforces the per-run cost cap across concurrent workers. Before each LLM call a worst-case estimate is reserved; if it
 * would push the run over the cap, the run stops (no further call is made). After the call the reservation is replaced
 * by the real cost.
 */
final class CostGuard {

    private final BigDecimal cap;
    private BigDecimal committed = BigDecimal.ZERO;
    private boolean stopped;

    CostGuard(BigDecimal cap) {
        this.cap = cap;
    }

    synchronized boolean reserve(BigDecimal worstCase) {
        if (stopped || committed.add(worstCase).compareTo(cap) > 0) {
            stopped = true;
            return false;
        }
        committed = committed.add(worstCase);
        return true;
    }

    synchronized void settle(BigDecimal reserved, BigDecimal actual) {
        committed = committed.subtract(reserved).add(actual);
    }

    synchronized boolean stopped() {
        return stopped;
    }

    synchronized BigDecimal spent() {
        return committed;
    }
}
