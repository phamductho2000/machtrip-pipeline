package vn.machtrip.pipeline.crawler;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Handle of a provider run. Returned by the start calls with every field set (so the caller can persist the run
 * immediately); {@link #of} builds a lookup handle from persisted ids, where the start-only fields are null.
 */
public record RunRef(String runId, String datasetId, RunStatus status, String actorId, JsonNode input,
                     BigDecimal maxTotalChargeUsd) {

    public static RunRef of(String runId, String datasetId) {
        return new RunRef(runId, datasetId, null, null, null, null);
    }
}
