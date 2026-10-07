package vn.machtrip.pipeline.extract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The Genway client against a WireMock that speaks the envelope of docs/genway-llm-api.md. */
class GenwayLlmClientTest {

    private static final String KEY = "gw_secret_key_123";
    private static final String KONG = "kong_secret_key_456";
    private static final LlmRequest REQUEST = new LlmRequest("gpt-4o", "SYSTEM RULES", "USER TEXT", "{\"x\":1}", 500,
            "machtrip-pipeline-extract-abc");

    private static WireMockServer wm;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private GenwayLlmClient client(String systemPromptAs) {
        return new GenwayLlmClient(new GenwayProperties(wm.baseUrl(), KEY, Duration.ofMillis(300),
                Duration.ofSeconds(2), 2, Duration.ofMillis(5), systemPromptAs, KONG, ""), mapper);
    }

    private static void stub(int status, String body) {
        wm.stubFor(post(urlPathEqualTo("/api/generation")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String ok(String responseJson) {
        return "{\"success\":true,\"requestId\":\"r1\",\"status\":\"success\",\"data\":{\"response\":" + responseJson
                + ",\"outputs\":null}}";
    }

    @Test
    void sendsTheDocumentedTextEnvelopeWithAuthAndIdempotencyKey() {
        stub(200, ok("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"mentions\\\":[]}\"}}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30},\"model\":\"gpt-4o-2024\"}"));

        LlmResult r = client("message").complete(REQUEST);

        assertThat(r.text()).isEqualTo("{\"mentions\":[]}");
        assertThat(r.inputTokens()).isEqualTo(120);
        assertThat(r.outputTokens()).isEqualTo(30);
        assertThat(r.model()).isEqualTo("gpt-4o-2024");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation"))
                .withHeader("Authorization", equalTo("Bearer " + KEY))
                .withHeader("Idempotency-Key", equalTo("machtrip-pipeline-extract-abc"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model_key": "gpt-4o", "call_type": "text",
                         "input": {"messages": [{"role": "system", "content": "SYSTEM RULES"},
                                                {"role": "user", "content": "USER TEXT"}],
                                   "max_tokens": 500}}""")));
        // the doc describes no JSON-schema field, so none is sent
        wm.verify(postRequestedFor(urlPathEqualTo("/api/generation")).withRequestBody(notMatching(".*schema.*")));
    }

    @Test
    void sendsTheKongApikeyHeaderOnlyWhenConfiguredAndNeverLeaksIt() {
        stub(200, ok("\"hi\""));
        client("message").complete(REQUEST);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation")).withHeader("apikey", equalTo(KONG))
                .withHeader("Authorization", equalTo("Bearer " + KEY)));

        wm.resetAll();
        stub(200, ok("\"hi\""));
        new GenwayLlmClient(new GenwayProperties(wm.baseUrl(), KEY, Duration.ofMillis(300), Duration.ofSeconds(2), 2,
                Duration.ofMillis(5), "message", "", ""), mapper).complete(REQUEST);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation")).withoutHeader("apikey"));

        wm.resetAll();
        stub(401, "{\"message\":\"No API key found in request\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST)).hasMessageNotContaining(KONG)
                .hasMessageNotContaining(KEY);
    }

    @Test
    void canPutTheSystemPromptInAnInputFieldInstead() {
        stub(200, ok("\"hello\""));

        client("field").complete(REQUEST);

        wm.verify(postRequestedFor(urlPathEqualTo("/api/generation"))
                .withRequestBody(matchingJsonPath("$.input.system", equalTo("SYSTEM RULES")))
                .withRequestBody(matchingJsonPath("$.input.messages.length()", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.input.messages[0].role", equalTo("user"))));
    }

    @Test
    void thinkingIsOnlySentWhenConfigured() {
        stub(200, ok("\"hello\""));

        client("message").complete(REQUEST);
        new GenwayLlmClient(new GenwayProperties(wm.baseUrl(), KEY, Duration.ofMillis(300), Duration.ofSeconds(2), 2,
                Duration.ofMillis(5), "message", KONG, "disabled"), mapper).complete(REQUEST);

        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation"))
                .withRequestBody(matchingJsonPath("$.input[?(@.thinking)]")));
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation"))
                .withRequestBody(matchingJsonPath("$.input.thinking.type", equalTo("disabled"))));
        wm.verify(2, postRequestedFor(urlPathEqualTo("/api/generation")));
    }

    @Test
    void readsAnAnthropicStyleMessageAndAPlainString() {
        stub(200, ok("{\"content\":[{\"type\":\"text\",\"text\":\"ab\"},{\"type\":\"tool_use\"},"
                + "{\"type\":\"text\",\"text\":\"cd\"}],\"usage\":{\"input_tokens\":11,\"output_tokens\":7},"
                + "\"model\":\"claude-x\"}"));
        LlmResult anthropic = client("message").complete(REQUEST);
        assertThat(anthropic.text()).isEqualTo("abcd");
        assertThat(anthropic.inputTokens()).isEqualTo(11);
        assertThat(anthropic.outputTokens()).isEqualTo(7);

        stub(200, ok("\"just text\""));
        LlmResult plain = client("message").complete(REQUEST);
        assertThat(plain.text()).isEqualTo("just text");
        assertThat(plain.inputTokens()).isNull(); // no usage reported: the caller estimates tokens
        assertThat(plain.model()).isEqualTo("gpt-4o");
    }

    @Test
    void aProviderFailureInsideAnHttp200IsAnErrorAndIsNotRetried() {
        stub(200, "{\"success\":false,\"requestId\":\"r9\",\"errorCode\":\"PROVIDER_ERROR\","
                + "\"message\":\"Provider returned 500\"}");

        assertThatThrownBy(() -> client("message").complete(REQUEST)).isInstanceOf(LlmException.class)
                .hasMessageContaining("PROVIDER_ERROR").hasMessageContaining("Provider returned 500")
                .hasMessageContaining("r9");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
    void everyHttpErrorStatusFailsTheVideoWithoutARetry(int status) {
        stub(status, "{\"success\":false,\"errorCode\":\"X_CODE\",\"message\":\"some message\"}");

        assertThatThrownBy(() -> client("message").complete(REQUEST)).isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP " + status).hasMessageContaining("X_CODE")
                .hasMessageNotContaining(KEY);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/api/generation")));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void onlyConfigurationLevelFailuresAbortTheWholeRun(int status) {
        stub(status, "{\"success\":false,\"errorCode\":\"X\",\"message\":\"m\"}");
        boolean fatal = status == 400 || status == 401 || status == 403 || status == 404;

        assertThatThrownBy(() -> client("message").complete(REQUEST))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRunFatal()).isEqualTo(fatal));
    }

    @Test
    void aBillingErrorAbortsTheRunButAProviderErrorOnlyFailsTheVideo() {
        stub(200, "{\"success\":false,\"errorCode\":\"PROVIDER_BILLING_ERROR\",\"message\":\"no credit\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRunFatal()).isTrue());

        stub(200, "{\"success\":false,\"errorCode\":\"PROVIDER_ERROR\",\"message\":\"500\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.isRunFatal()).isFalse());
    }

    @Test
    void explainsTheTwoAuthFailures() {
        stub(401, "{\"success\":false,\"errorCode\":\"UNAUTHORIZED\",\"message\":\"bad key\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST)).hasMessageContaining("GENWAY_API_KEY");

        stub(403, "{\"success\":false,\"errorCode\":\"UNAUTHORIZED\",\"message\":\"Forbidden\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST)).hasMessageContaining("not granted the provider");
    }

    @Test
    void aProcessingAnswerAndAnUnknownShapeAreClearErrors() {
        stub(200, "{\"success\":true,\"requestId\":\"r2\",\"status\":\"processing\",\"pollUrl\":\"/api/generation/r2\"}");
        assertThatThrownBy(() -> client("message").complete(REQUEST)).hasMessageContaining("processing");

        stub(200, ok("{\"something\":\"else\"}"));
        assertThatThrownBy(() -> client("message").complete(REQUEST)).hasMessageContaining("unrecognised shape");
    }

    @Test
    void aTimeoutIsRetriedAtMostTwiceWithTheSameIdempotencyKey() {
        wm.stubFor(post(urlPathEqualTo("/api/generation")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThatThrownBy(() -> client("message").complete(REQUEST)).isInstanceOf(LlmException.class)
                .hasMessageContaining("3 attempt").hasMessageNotContaining(KEY);
        wm.verify(3, postRequestedFor(urlPathEqualTo("/api/generation"))
                .withHeader("Idempotency-Key", equalTo("machtrip-pipeline-extract-abc")));
    }

    @Test
    void aConnectionResetIsRetriedAtMostTwice() {
        wm.stubFor(post(urlPathEqualTo("/api/generation")).willReturn(aResponse()
                .withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client("message").complete(REQUEST)).isInstanceOf(LlmException.class);
        wm.verify(3, postRequestedFor(urlPathEqualTo("/api/generation")));
    }

    @Test
    void refusesToCallWithoutBaseUrlAndKey() {
        var noKey = new GenwayLlmClient(new GenwayProperties("http://x", "", Duration.ofSeconds(1),
                Duration.ofSeconds(1), 2, Duration.ofMillis(5), "message", KONG, ""), mapper);

        assertThatThrownBy(() -> noKey.complete(REQUEST)).hasMessageContaining("GENWAY_BASE_URL")
                .hasMessageContaining("GENWAY_API_KEY");
        assertThat(new GenwayProperties("https://g", KEY, null, null, 2, null, "message", KONG, "").toString())
                .doesNotContain(KEY).doesNotContain(KONG);
    }
}
