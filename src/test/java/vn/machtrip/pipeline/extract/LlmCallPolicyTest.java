package vn.machtrip.pipeline.extract;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The retry rules of the Genway client, exercised over real HTTP against WireMock. The endpoint here is a stand-in: the
 * real Genway path/payload are unknown (docs/genway-llm-api.md is missing), but the policy does not depend on them.
 */
class LlmCallPolicyTest {

    private static WireMockServer wm;
    private static RestClient client;
    private final LlmCallPolicy policy = new LlmCallPolicy(2, Duration.ofMillis(5));

    @BeforeAll
    static void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        f.setReadTimeout(Duration.ofMillis(300));
        client = RestClient.builder().requestFactory(f).baseUrl(wm.baseUrl()).build();
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
    }

    private String call() {
        return policy.execute(() -> client.post().uri("/llm").body("{}").retrieve().body(String.class));
    }

    @Test
    void http429IsNotRetried() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(this::call).isInstanceOf(LlmException.class).hasMessageContaining("429");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/llm")));
    }

    @Test
    void http5xxIsNotRetried() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(this::call).isInstanceOf(LlmException.class).hasMessageContaining("503");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/llm")));
    }

    @Test
    void http4xxIsNotRetried() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(this::call).isInstanceOf(LlmException.class);
        wm.verify(1, postRequestedFor(urlPathEqualTo("/llm")));
    }

    @Test
    void aReadTimeoutIsRetriedAtMostTwiceThenFails() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThatThrownBy(this::call).isInstanceOf(LlmException.class).hasMessageContaining("3 attempt");
        wm.verify(3, postRequestedFor(urlPathEqualTo("/llm"))); // 1 call + 2 retries
    }

    @Test
    void aConnectionResetIsRetriedAtMostTwiceThenFails() {
        wm.stubFor(post(urlPathEqualTo("/llm")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(this::call).isInstanceOf(LlmException.class);
        wm.verify(3, postRequestedFor(urlPathEqualTo("/llm")));
    }

    @Test
    void aTimeoutFollowedBySuccessReturnsTheAnswer() {
        wm.stubFor(post(urlPathEqualTo("/llm")).inScenario("t").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200).withFixedDelay(1500)).willSetStateTo("fast"));
        wm.stubFor(post(urlPathEqualTo("/llm")).inScenario("t").whenScenarioStateIs("fast")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        assertThat(call()).isEqualTo("ok");
        wm.verify(2, postRequestedFor(urlPathEqualTo("/llm")));
    }
}
