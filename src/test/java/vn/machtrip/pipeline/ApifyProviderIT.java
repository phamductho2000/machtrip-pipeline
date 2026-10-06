package vn.machtrip.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import vn.machtrip.pipeline.crawler.ApifyException;
import vn.machtrip.pipeline.crawler.ApifyProvider;
import vn.machtrip.pipeline.crawler.RunRef;
import vn.machtrip.pipeline.crawler.RunStatus;
import vn.machtrip.pipeline.crawler.SearchQuery;

class ApifyProviderIT extends AbstractIT {

    private static final String RUNS = "/v2/actors/clockworks~tiktok-scraper/runs";

    @Autowired ApifyProvider provider;
    @Autowired ObjectMapper mapper;

    private static void stubStart(int status, String body) {
        WM.stubFor(post(urlPathEqualTo(RUNS)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void startRunAlwaysCarriesCostCapsAndWebhookAndInputLimit() throws Exception {
        stubStart(201, runJson("RUN1", "RUNNING", "0"));

        RunRef ref = provider.startSearch(new SearchQuery(List.of("#reviewdalat"), 100));

        assertThat(ref.runId()).isEqualTo("RUN1");
        assertThat(ref.datasetId()).isEqualTo("ds-RUN1");
        WM.verify(1, postRequestedFor(urlPathEqualTo(RUNS))
                .withQueryParam("maxTotalChargeUsd", equalTo("1.0"))
                .withQueryParam("timeout", equalTo("600"))
                .withQueryParam("maxItems", equalTo("100"))
                .withQueryParam("waitForFinish", equalTo("60"))
                .withQueryParam("webhooks", matching(".+"))
                .withHeader("Authorization", equalTo("Bearer test-token-abc")));

        Request req = WM.getAllServeEvents().get(0).getRequest();
        // item limit is ALSO enforced inside the actor input, template notes are never sent
        JsonNode body = mapper.readTree(req.getBodyAsString());
        assertThat(body.path("hashtags").get(0).asText()).isEqualTo("reviewdalat");
        assertThat(body.path("resultsPerPage").asInt()).isEqualTo(100);
        assertThat(body.fieldNames()).toIterable().noneMatch(n -> n.startsWith("_"));

        String b64 = req.queryParameter("webhooks").firstValue();
        JsonNode hooks = mapper.readTree(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
        List<String> events = new ArrayList<>();
        hooks.get(0).path("eventTypes").forEach(e -> events.add(e.asText()));
        assertThat(events).containsExactlyInAnyOrder("ACTOR.RUN.SUCCEEDED", "ACTOR.RUN.FAILED",
                "ACTOR.RUN.TIMED_OUT", "ACTOR.RUN.ABORTED");
        assertThat(hooks.get(0).path("requestUrl").asText())
                .isEqualTo("https://hooks.test.example/webhooks/apify/test-secret-123");
    }

    @Test
    void startRequestWithoutCapsIsRefused() {
        assertThatThrownBy(() -> new ApifyProvider.StartRequest("search", "a~b", Map.of(), mapper.createObjectNode(),
                "https://x", BigDecimal.ONE)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxTotalChargeUsd");
        assertThatThrownBy(() -> new ApifyProvider.StartRequest("search", "a~b",
                Map.of("maxTotalChargeUsd", "1", "timeout", "10"), mapper.createObjectNode(), "https://x",
                BigDecimal.ZERO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dryRunDescriptionHidesSecretsAndNeedsNoNetwork() {
        String text = provider.describe(provider.searchRequest(new SearchQuery(List.of("reviewdalat"), 100)));

        assertThat(text).contains("maxTotalChargeUsd=1.0", "timeout=600", "maxItems=100", "ACTOR.RUN.TIMED_OUT",
                "resultsPerPage", "Bearer ***");
        assertThat(text).doesNotContain("test-secret-123").doesNotContain("test-token-abc");
        assertThat(WM.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "400, invalid-input, rejected the actor input",
            "401, invalid-token, token is missing or invalid",
            "403, concurrent-runs-limit-exceeded, Too many concurrent Apify runs",
            "400, max-total-charge-usd-below-minimum, below the minimum charge",
    })
    void startErrorTypesMapToClearNonRetriedErrors(int status, String type, String expected) {
        stubStart(status, "{\"error\":{\"type\":\"" + type + "\",\"message\":\"server detail\"}}");

        assertThatThrownBy(() -> provider.startSearch(new SearchQuery(List.of("x"), 1)))
                .isInstanceOfSatisfying(ApifyException.class, e -> {
                    assertThat(e.type()).isEqualTo(type);
                    assertThat(e.httpStatus()).isEqualTo(status);
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains(expected).contains(type);
                });
        WM.verify(1, postRequestedFor(urlPathEqualTo(RUNS)));
    }

    @Test
    void rateLimitIsRetriedWithBackoffUntilTheOverallTimeout() {
        stubStart(429, "{\"error\":{\"type\":\"rate-limit-exceeded\",\"message\":\"slow down\"}}");

        assertThatThrownBy(() -> provider.startSearch(new SearchQuery(List.of("x"), 1)))
                .isInstanceOfSatisfying(ApifyException.class, e -> {
                    assertThat(e.type()).isEqualTo("rate-limit-exceeded");
                    assertThat(e.getMessage()).contains("rate limit exceeded");
                });
        assertThat(WM.getAllServeEvents().size()).isGreaterThan(2);
    }

    @Test
    void rateLimitedStartSucceedsOnceTheLimitClears() {
        WM.stubFor(post(urlPathEqualTo(RUNS)).inScenario("rl").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withBody(
                        "{\"error\":{\"type\":\"rate-limit-exceeded\",\"message\":\"x\"}}"))
                .willSetStateTo("ok"));
        WM.stubFor(post(urlPathEqualTo(RUNS)).inScenario("rl").whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(201).withBody(runJson("RUN2", "RUNNING", "0"))));

        assertThat(provider.startSearch(new SearchQuery(List.of("x"), 1)).runId()).isEqualTo("RUN2");
        WM.verify(2, postRequestedFor(urlPathEqualTo(RUNS)));
    }

    @Test
    void serverErrorOnStartIsNotRetriedBecauseTheRunMayExist() {
        stubStart(503, "{\"error\":{\"type\":\"internal\",\"message\":\"oops\"}}");

        assertThatThrownBy(() -> provider.startSearch(new SearchQuery(List.of("x"), 1))).isInstanceOf(ApifyException.class);
        WM.verify(1, postRequestedFor(urlPathEqualTo(RUNS)));
    }

    @Test
    void getRunRetries5xxAndParsesCostBuildAndPricing() {
        WM.stubFor(get(urlPathEqualTo("/v2/actor-runs/RUN3")).inScenario("gr").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("up"));
        WM.stubFor(get(urlPathEqualTo("/v2/actor-runs/RUN3")).inScenario("gr").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(200).withBody(runJson("RUN3", "SUCCEEDED", "0.42"))));

        RunStatus s = provider.getRun(RunRef.of("RUN3", null));

        assertThat(s.isTerminal()).isTrue();
        assertThat(s.usageTotalUsd()).isEqualByComparingTo("0.42");
        assertThat(s.actorBuild()).isEqualTo("0.0.5");
        assertThat(s.pricingModel()).isEqualTo("PAY_PER_EVENT");
    }

    @Test
    void getRunDoesNotRetryOther4xx() {
        WM.stubFor(get(urlPathEqualTo("/v2/actor-runs/RUN4")).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":{\"type\":\"record-not-found\",\"message\":\"nope\"}}")));

        assertThatThrownBy(() -> provider.getRun(RunRef.of("RUN4", null))).isInstanceOf(ApifyException.class);
        assertThat(WM.getAllServeEvents()).hasSize(1);
    }

    @Test
    void iterItemsPagesByOffsetAndLimit() {
        for (int offset = 0; offset <= 4; offset += 2) {
            String items = offset == 4 ? "[{\"id\":5}]" : "[{\"id\":" + (offset + 1) + "},{\"id\":" + (offset + 2) + "}]";
            WM.stubFor(get(urlPathEqualTo("/v2/datasets/dsX/items")).withQueryParam("format", equalTo("json"))
                    .withQueryParam("limit", equalTo("2")).withQueryParam("offset", equalTo("" + offset))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                            .withBody(items)));
        }

        List<Integer> ids = new ArrayList<>();
        provider.iterItems(RunRef.of("RUN5", "dsX")).forEachRemaining(n -> ids.add(n.path("id").asInt()));

        assertThat(ids).containsExactly(1, 2, 3, 4, 5);
        assertThat(WM.getAllServeEvents()).hasSize(3);
    }

    @Test
    void validateInputReportsInvalidInput() {
        WM.stubFor(post(urlPathEqualTo("/v2/actors/clockworks~tiktok-scraper/validate-input"))
                .willReturn(aResponse().withStatus(400).withBody(
                        "{\"error\":{\"type\":\"invalid-input\",\"message\":\"Field input.x is required\"}}")));

        ApifyProvider.ValidationResult r = provider.validateInput(mapper.createObjectNode());

        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("Field input.x is required");
    }
}
