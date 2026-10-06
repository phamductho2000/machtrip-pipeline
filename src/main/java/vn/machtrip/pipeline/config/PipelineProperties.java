package vn.machtrip.pipeline.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pipeline")
public record PipelineProperties(
        @DefaultValue("config") String configDir,
        @DefaultValue("") String hashSalt,
        @DefaultValue("./data/raw") String rawStoreDir,
        @DefaultValue Apify apify,
        @DefaultValue Webhook webhook,
        @DefaultValue Api api,
        @DefaultValue Job job,
        @DefaultValue Reconcile reconcile,
        @DefaultValue Filters filters,
        @DefaultValue Comments comments) {

    public record Apify(
            @DefaultValue("") String token,
            @DefaultValue("https://api.apify.com/v2") String baseUrl,
            @DefaultValue("clockworks~tiktok-scraper") String actorId,
            @DefaultValue("1.0") BigDecimal maxTotalChargeUsd,
            @DefaultValue("600") int timeoutSec,
            @DefaultValue("60") int waitForFinishSec,
            @DefaultValue("100") int pageSize,
            String build,
            Integer memoryMbytes,
            @DefaultValue Retry retry) {

        public record Retry(
                @DefaultValue("500ms") Duration initialBackoff,
                @DefaultValue("60s") Duration overallTimeout) {
        }

        @Override
        public String toString() {
            return "Apify[baseUrl=" + baseUrl + ", actorId=" + actorId + ", token=***]";
        }
    }

    public record Webhook(@DefaultValue("") String publicBaseUrl, @DefaultValue("") String secret) {
        @Override
        public String toString() {
            return "Webhook[publicBaseUrl=" + publicBaseUrl + ", secret=***]";
        }
    }

    /** Auth for the trigger API ({@code /api/**}): header {@code X-API-Key} must equal this, constant-time. */
    public record Api(@DefaultValue("") String key) {
        @Override
        public String toString() {
            return "Api[key=***]";
        }
    }

    public record Job(
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("5s") Duration pollInterval,
            @DefaultValue("30m") Duration lockTimeout) {
    }

    public record Reconcile(@DefaultValue("15m") Duration staleAfter) {
    }

    public record Filters(
            @DefaultValue("30") int minDurationSec,
            @DefaultValue("vi") List<String> allowedLanguages,
            @DefaultValue("true") boolean skipAds,
            @DefaultValue("14") int skipRecrawlDays) {
    }

    public record Comments(
            @DefaultValue("50") int perVideoCap,
            @DefaultValue("1000") int totalItemsCap) {
    }

    @Override
    public String toString() {
        return "PipelineProperties[secrets redacted]";
    }
}
