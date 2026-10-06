package vn.machtrip.pipeline.crawler;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import vn.machtrip.pipeline.config.ActorInputs;
import vn.machtrip.pipeline.config.PipelineProperties;

/** {@link CrawlerProvider} backed by the Apify API v2 (https://docs.apify.com/api/v2). */
@Component
public class ApifyProvider implements CrawlerProvider {

    private static final Logger log = LoggerFactory.getLogger(ApifyProvider.class);

    /** Event names per https://docs.apify.com/platform/integrations/webhooks/events (note TIMED_OUT, not TIMED-OUT). */
    static final List<String> WEBHOOK_EVENTS = List.of(
            "ACTOR.RUN.SUCCEEDED", "ACTOR.RUN.FAILED", "ACTOR.RUN.TIMED_OUT", "ACTOR.RUN.ABORTED");
    public static final String WEBHOOK_PATH = "/webhooks/apify/";
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /**
     * A fully described start-run call, shared by the real call and --dry-run so the two cannot drift.
     * Construction refuses a request without the cost caps.
     */
    public record StartRequest(String kind, String actorId, Map<String, String> query, ObjectNode input,
                               String webhookUrl, BigDecimal maxTotalChargeUsd) {
        public StartRequest {
            if (maxTotalChargeUsd == null || maxTotalChargeUsd.signum() <= 0
                    || !query.containsKey("maxTotalChargeUsd") || !query.containsKey("timeout")) {
                throw new IllegalStateException("Refusing to start a run without maxTotalChargeUsd and timeout");
            }
        }
    }

    public record ValidationResult(boolean valid, String message) {
    }

    private final PipelineProperties.Apify cfg;
    private final PipelineProperties.Webhook webhook;
    private final ActorInputs inputs;
    private final ObjectMapper mapper;
    // built on first real call, so --dry-run and offline commands never create an HTTP client
    private volatile RestClient client;
    private volatile RestClient startClient;

    public ApifyProvider(PipelineProperties props, ActorInputs inputs, ObjectMapper mapper) {
        this.cfg = props.apify();
        this.webhook = props.webhook();
        this.inputs = inputs;
        this.mapper = mapper;
    }

    private RestClient client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = buildClient(Duration.ofSeconds(30));
                }
            }
        }
        return client;
    }

    private RestClient startClient() {
        if (startClient == null) {
            synchronized (this) {
                if (startClient == null) {
                    startClient = buildClient(Duration.ofSeconds(Math.min(cfg.waitForFinishSec(), 60) + 30L));
                }
            }
        }
        return startClient;
    }

    private RestClient buildClient(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    // ---- request construction (no network) --------------------------------------------------------------------

    public StartRequest searchRequest(SearchQuery q) {
        return request("search", inputs.search(q.hashtag(), q.limit()), q.limit());
    }

    public StartRequest commentsRequest(List<String> videoUrls, int maxPerVideo, int maxItems) {
        if (videoUrls.isEmpty()) {
            throw new IllegalArgumentException("no video URLs to crawl comments for");
        }
        return request("comments", inputs.comments(videoUrls, maxPerVideo), maxItems);
    }

    private StartRequest request(String kind, ObjectNode input, int maxItems) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("maxTotalChargeUsd", cfg.maxTotalChargeUsd().toPlainString());
        query.put("timeout", String.valueOf(cfg.timeoutSec()));
        // pay-per-result actors only; the real item cap is inside the actor input (see config/actors)
        query.put("maxItems", String.valueOf(maxItems));
        if (cfg.waitForFinishSec() > 0) {
            query.put("waitForFinish", String.valueOf(Math.min(cfg.waitForFinishSec(), 60)));
        }
        if (cfg.memoryMbytes() != null) {
            query.put("memory", String.valueOf(cfg.memoryMbytes()));
        }
        if (cfg.build() != null && !cfg.build().isBlank()) {
            query.put("build", cfg.build());
        }
        String webhookUrl = webhook.publicBaseUrl().isBlank() || webhook.secret().isBlank() ? null
                : webhook.publicBaseUrl().replaceAll("/+$", "") + WEBHOOK_PATH + webhook.secret();
        return new StartRequest(kind, cfg.actorId(), query, input, webhookUrl, cfg.maxTotalChargeUsd());
    }

    private ArrayNode webhooksJson(String requestUrl) {
        ArrayNode arr = mapper.createArrayNode();
        ObjectNode hook = arr.addObject();
        ArrayNode events = hook.putArray("eventTypes");
        WEBHOOK_EVENTS.forEach(events::add);
        hook.put("requestUrl", requestUrl);
        return arr;
    }

    /** Human readable dump of exactly what start() would send. Never prints the token or the webhook secret. */
    public String describe(StartRequest r) {
        String shownUrl = r.webhookUrl() == null
                ? "<set PUBLIC_WEBHOOK_BASE_URL and WEBHOOK_SECRET; real runs refuse to start without them>"
                : r.webhookUrl().replace(webhook.secret(), "***");
        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(cfg.baseUrl()).append("/actors/").append(r.actorId()).append("/runs\n");
        sb.append("Authorization: Bearer ***\n");
        sb.append("Query params:\n");
        r.query().forEach((k, v) -> sb.append("  ").append(k).append('=').append(v).append('\n'));
        sb.append("  webhooks=<base64 of the JSON array below>\n");
        sb.append("Webhook config (decoded):\n");
        sb.append(pretty(webhooksJson(shownUrl))).append('\n');
        sb.append("Input payload:\n").append(pretty(r.input()));
        return sb.toString();
    }

    private String pretty(JsonNode n) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- CrawlerProvider ------------------------------------------------------------------------------------------

    @Override
    public RunRef startSearch(SearchQuery query) {
        return start(searchRequest(query));
    }

    @Override
    public RunRef startComments(List<String> videoUrls, int maxPerVideo) {
        return start(commentsRequest(videoUrls, maxPerVideo, videoUrls.size() * maxPerVideo));
    }

    public RunRef start(StartRequest r) {
        requireToken();
        if (r.webhookUrl() == null) {
            throw new IllegalStateException("PUBLIC_WEBHOOK_BASE_URL and WEBHOOK_SECRET must be set: "
                    + "every run is started with a completion webhook");
        }
        String webhooks = Base64.getEncoder().encodeToString(
                webhooksJson(r.webhookUrl()).toString().getBytes(StandardCharsets.UTF_8));
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(cfg.baseUrl())
                .pathSegment("actors", r.actorId(), "runs");
        r.query().forEach(b::queryParam);
        b.queryParam("webhooks", "{webhooks}");
        URI uri = b.encode().build(Map.of("webhooks", webhooks));

        // Only 429 is retried for a start: a 5xx or a timeout may have created the run already, and a blind retry
        // would start (and pay for) a second one.
        JsonNode data = withRetry("start run", false, () -> json(startClient().post().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(r.input())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw toException(res.getStatusCode().value(), res.getBody().readAllBytes());
                })
                .body(byte[].class))).path("data");
        RunStatus status = parseRun(data);
        log.info("Started {} run {} (status {}, dataset {})", r.kind(), status.runId(), status.status(),
                status.datasetId());
        return new RunRef(status.runId(), status.datasetId(), status, r.actorId(), r.input(), r.maxTotalChargeUsd());
    }

    @Override
    public RunStatus getRun(RunRef run) {
        requireToken();
        JsonNode data = withRetry("get run", true, () -> get(
                UriComponentsBuilder.fromUriString(cfg.baseUrl()).pathSegment("actor-runs", run.runId())
                        .build().encode().toUri())).path("data");
        return parseRun(data);
    }

    @Override
    public Iterator<JsonNode> iterItems(RunRef run) {
        requireToken();
        return new Iterator<>() {
            private Iterator<JsonNode> page = List.<JsonNode>of().iterator();
            private int offset = 0;
            private boolean last = false;

            @Override
            public boolean hasNext() {
                while (!page.hasNext() && !last) {
                    JsonNode items = fetchPage(run.datasetId(), offset);
                    offset += items.size();
                    last = items.size() < cfg.pageSize();
                    page = items.iterator();
                }
                return page.hasNext();
            }

            @Override
            public JsonNode next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return page.next();
            }
        };
    }

    private JsonNode fetchPage(String datasetId, int offset) {
        URI uri = UriComponentsBuilder.fromUriString(cfg.baseUrl()).pathSegment("datasets", datasetId, "items")
                .queryParam("format", "json").queryParam("offset", offset).queryParam("limit", cfg.pageSize())
                .build().encode().toUri();
        JsonNode items = withRetry("read dataset", true, () -> get(uri));
        if (!items.isArray()) {
            throw new ApifyException(-1, null, "Unexpected dataset response (not a JSON array)");
        }
        return items;
    }

    /** Calls Apify's "Validate Actor input" endpoint. Docs do not say whether it is free; see README. */
    public ValidationResult validateInput(ObjectNode input) {
        requireToken();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(cfg.baseUrl())
                .pathSegment("actors", cfg.actorId(), "validate-input");
        if (cfg.build() != null && !cfg.build().isBlank()) {
            b.queryParam("build", cfg.build());
        }
        URI uri = b.build().encode().toUri();
        try {
            withRetry("validate input", true, () -> json(client().post().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.token())
                    .contentType(MediaType.APPLICATION_JSON).body(input)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw toException(res.getStatusCode().value(), res.getBody().readAllBytes());
                    })
                    .body(byte[].class)));
            return new ValidationResult(true, "input is valid");
        } catch (ApifyException e) {
            if ("invalid-input".equals(e.type())) {
                return new ValidationResult(false, e.getMessage());
            }
            throw e;
        }
    }

    // ---- HTTP plumbing --------------------------------------------------------------------------------------------

    private void requireToken() {
        if (cfg.token() == null || cfg.token().isBlank()) {
            throw new IllegalStateException("APIFY_TOKEN is not set");
        }
    }

    private JsonNode get(URI uri) {
        return json(client().get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.token())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw toException(res.getStatusCode().value(), res.getBody().readAllBytes());
                })
                .body(byte[].class));
    }

    /** Parses the body ourselves so a missing/odd Content-Type cannot break response handling. */
    private JsonNode json(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ApifyException(-1, null, "Apify returned a response that is not valid JSON");
        }
    }

    private ApifyException toException(int status, byte[] body) {
        String type = null;
        String message = null;
        try {
            JsonNode err = mapper.readTree(body).path("error");
            type = err.path("type").asText(null);
            message = err.path("message").asText(null);
        } catch (IOException ignored) {
            // non-JSON error body (e.g. a gateway page): fall back to the status code only
        }
        return ApifyException.fromResponse(status, type, message);
    }

    private <T> T withRetry(String what, boolean retryServerErrors, Supplier<T> call) {
        long deadline = System.nanoTime() + cfg.retry().overallTimeout().toNanos();
        Duration delay = cfg.retry().initialBackoff();
        ApifyException last;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (ApifyException e) {
                if (!(retryServerErrors ? e.isRetryable() : e.httpStatus() == 429)) {
                    throw e;
                }
                last = e;
            } catch (ResourceAccessException e) {
                if (!retryServerErrors) {
                    throw new ApifyException(what + " failed (network error; not retried, the run may exist): "
                            + e.getMessage(), e);
                }
                last = new ApifyException(what + " failed: network error: " + e.getMessage(), e);
            }
            long sleepNanos = ThreadLocalRandom.current().nextLong(delay.toNanos() / 2 + 1, delay.toNanos() + 1);
            if (System.nanoTime() + sleepNanos > deadline) {
                log.warn("Giving up on '{}' after {} attempts", what, attempt);
                throw last;
            }
            log.warn("'{}' failed (attempt {}): {}; retrying", what, attempt, last.getMessage());
            try {
                Thread.sleep(Duration.ofNanos(sleepNanos));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApifyException(what + " interrupted", ie);
            }
            delay = delay.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay.multipliedBy(2);
        }
    }

    private RunStatus parseRun(JsonNode run) {
        if (run.isMissingNode() || !run.hasNonNull("id")) {
            throw new ApifyException(-1, null, "Unexpected run response from Apify (no data.id)");
        }
        JsonNode usage = run.path("usageTotalUsd");
        String build = run.hasNonNull("buildNumber") ? run.get("buildNumber").asText()
                : run.path("options").path("build").asText(null);
        return new RunStatus(run.get("id").asText(), run.path("status").asText(),
                run.path("defaultDatasetId").asText(null),
                usage.isNumber() ? usage.decimalValue() : null,
                build, run.path("pricingInfo").path("pricingModel").asText(null));
    }
}
