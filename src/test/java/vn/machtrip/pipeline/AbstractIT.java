package vn.machtrip.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared infrastructure: one Postgres container (pre-seeded like the backend's database), one WireMock standing in for
 * both Apify and the TikTok CDN. Nothing here can reach a real external service.
 */
@SpringBootTest(properties = "pipeline.cli.enabled=false")
public abstract class AbstractIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("sim-backend.sql");
    static final WireMockServer WM = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final Path RAW_DIR;

    static {
        PG.start();
        WM.start();
        try {
            RAW_DIR = Files.createTempDirectory("machtrip-raw");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("pipeline.apify.base-url", () -> WM.baseUrl() + "/v2");
        r.add("pipeline.apify.token", () -> "test-token-abc");
        r.add("pipeline.apify.page-size", () -> "2");
        r.add("pipeline.apify.retry.initial-backoff", () -> "10ms");
        r.add("pipeline.apify.retry.overall-timeout", () -> "1500ms");
        r.add("pipeline.webhook.public-base-url", () -> "https://hooks.test.example");
        r.add("pipeline.webhook.secret", () -> "test-secret-123");
        r.add("pipeline.hash-salt", () -> "test-salt");
        r.add("pipeline.raw-store-dir", RAW_DIR::toString);
        r.add("pipeline.job.max-attempts", () -> "2");
    }

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        WM.resetAll();
        jdbc.sql("""
                TRUNCATE comment, video_asr_queue, video_audio, video_subtitle, raw_item, video, job, crawl_run
                RESTART IDENTITY CASCADE""").update();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    protected void seedRun(String apifyRunId, String kind, String status) {
        jdbc.sql("""
                INSERT INTO crawl_run (kind, provider, actor_id, input, apify_run_id, dataset_id, status,
                                       max_total_charge_usd)
                VALUES (:kind, 'apify', 'clockworks~tiktok-scraper', '{}'::jsonb, :id, :ds, :status, 1.0)""")
                .param("kind", kind).param("id", apifyRunId).param("ds", "ds-" + apifyRunId).param("status", status)
                .update();
    }

    protected static String runJson(String id, String status, String usage) {
        return """
                {"data": {"id": "%s", "status": "%s", "defaultDatasetId": "ds-%s", "usageTotalUsd": %s,
                 "buildNumber": "0.0.5", "options": {"build": "latest"},
                 "pricingInfo": {"pricingModel": "PAY_PER_EVENT"}}}""".formatted(id, status, id, usage);
    }

    protected static void stubRun(String id, String status, String usage) {
        WM.stubFor(get(urlPathEqualTo("/v2/actor-runs/" + id))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(runJson(id, status, usage))));
    }
}
