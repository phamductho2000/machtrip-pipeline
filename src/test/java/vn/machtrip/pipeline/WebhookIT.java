package vn.machtrip.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebhookIT extends AbstractIT {

    private static final String SECRET = "test-secret-123";

    @Autowired MockMvc mvc;

    private static String payload(String runId, String claimedStatus) {
        return """
                {"userId":"u1","createdAt":"2026-10-06T00:00:00.000Z","eventType":"ACTOR.RUN.%s",
                 "eventData":{"actorId":"a1","actorRunId":"%s"},
                 "resource":{"id":"%s","status":"%s","usageTotalUsd":0.0001,"defaultDatasetId":"evil"}}"""
                .formatted(claimedStatus, runId, runId, claimedStatus);
    }

    private void deliver(String secret, String body, int expectedStatus) throws Exception {
        mvc.perform(post("/webhooks/apify/" + secret).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void wrongSecretIsRejectedWithoutTouchingApifyOrTheDatabase() throws Exception {
        seedRun("RUNA", "search", "RUNNING");
        stubRun("RUNA", "SUCCEEDED", "0.5");

        deliver("not-the-secret", payload("RUNA", "SUCCEEDED"), 403);
        deliver("", payload("RUNA", "SUCCEEDED"), 404); // empty path segment does not even route

        assertThat(WM.getAllServeEvents()).isEmpty();
        assertThat(count("SELECT count(*) FROM job")).isZero();
        assertThat(jdbc.sql("SELECT status FROM crawl_run").query(String.class).single()).isEqualTo("RUNNING");
    }

    @Test
    void validDeliveryRecordsRunAndCreatesOneJob() throws Exception {
        seedRun("RUNB", "search", "RUNNING");
        stubRun("RUNB", "SUCCEEDED", "0.37");

        deliver(SECRET, payload("RUNB", "SUCCEEDED"), 200);

        var row = jdbc.sql("SELECT status, cost_usd, actor_build, pricing_model, finished_at FROM crawl_run")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5)}).single();
        assertThat(row[0]).isEqualTo("SUCCEEDED");
        assertThat((java.math.BigDecimal) row[1]).isEqualByComparingTo("0.37");
        assertThat(row[2]).isEqualTo("0.0.5");
        assertThat(row[3]).isEqualTo("PAY_PER_EVENT");
        assertThat(row[4]).isNotNull();
        assertThat(count("SELECT count(*) FROM job WHERE apify_run_id = 'RUNB' AND kind = 'search' "
                + "AND status = 'pending'")).isEqualTo(1);
    }

    @Test
    void duplicateDeliveriesCreateNoDuplicateJobOrData() throws Exception {
        seedRun("RUNC", "search", "RUNNING");
        stubRun("RUNC", "SUCCEEDED", "0.37");

        for (int i = 0; i < 3; i++) {
            deliver(SECRET, payload("RUNC", "SUCCEEDED"), 200);
        }

        assertThat(count("SELECT count(*) FROM job")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM crawl_run")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM video")).isZero();
        assertThat(jdbc.sql("SELECT cost_usd FROM crawl_run").query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo("0.37");
    }

    @Test
    void forgedPayloadIsIgnoredBecauseOnlyTheRefetchedRunIsTrusted() throws Exception {
        seedRun("RUND", "search", "RUNNING");
        stubRun("RUND", "RUNNING", "0.01"); // Apify says: still running

        deliver(SECRET, payload("RUND", "SUCCEEDED"), 200); // payload lies

        assertThat(jdbc.sql("SELECT status FROM crawl_run").query(String.class).single()).isEqualTo("RUNNING");
        assertThat(jdbc.sql("SELECT dataset_id FROM crawl_run").query(String.class).single()).isEqualTo("ds-RUND");
        assertThat(count("SELECT count(*) FROM job")).isZero();
    }

    @Test
    void unknownRunAndMalformedRunIdAreHarmless() throws Exception {
        stubRun("GHOST", "SUCCEEDED", "0");

        deliver(SECRET, payload("GHOST", "SUCCEEDED"), 200);
        deliver(SECRET, payload("../etc/passwd", "SUCCEEDED"), 400);
        deliver(SECRET, "{}", 400);

        assertThat(count("SELECT count(*) FROM job")).isZero();
        assertThat(count("SELECT count(*) FROM crawl_run")).isZero();
    }

    @Test
    void failedRunStillGetsAJobSoPartialDataIsNotLost() throws Exception {
        seedRun("RUNE", "search", "RUNNING");
        stubRun("RUNE", "FAILED", "0.2");

        deliver(SECRET, payload("RUNE", "FAILED"), 200);

        assertThat(jdbc.sql("SELECT status FROM crawl_run").query(String.class).single()).isEqualTo("FAILED");
        assertThat(count("SELECT count(*) FROM job")).isEqualTo(1);
    }

    @Test
    void apifyOutageAnswers502SoApifyRedeliversTheWebhook() throws Exception {
        seedRun("RUNF", "search", "RUNNING");
        WM.stubFor(get(urlPathEqualTo("/v2/actor-runs/RUNF")).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":{\"type\":\"invalid-token\",\"message\":\"x\"}}")));

        deliver(SECRET, payload("RUNF", "SUCCEEDED"), 502);

        assertThat(count("SELECT count(*) FROM job")).isZero();
    }
}
