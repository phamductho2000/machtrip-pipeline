package vn.machtrip.pipeline.crawler;

import java.math.BigDecimal;
import java.util.Set;

public record RunStatus(String runId, String status, String datasetId, BigDecimal usageTotalUsd,
                        String actorBuild, String pricingModel) {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "TIMED-OUT", "ABORTED");

    public boolean isTerminal() {
        return isTerminal(status);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
