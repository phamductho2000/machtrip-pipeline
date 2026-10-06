package vn.machtrip.pipeline.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import vn.machtrip.pipeline.config.PipelineProperties;
import vn.machtrip.pipeline.crawler.ApifyException;
import vn.machtrip.pipeline.crawler.CrawlService;
import vn.machtrip.pipeline.crawler.CrawlerProvider;
import vn.machtrip.pipeline.crawler.RunRef;
import vn.machtrip.pipeline.crawler.RunStatus;

/**
 * Apify run-finished webhook. Does the minimum so it answers fast: authenticate by the secret in the path, take ONLY the
 * run id from the payload, re-fetch the run from Apify and trust that response alone, then record status + job.
 * Nothing is downloaded or parsed here; the `work` command does that.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9]{1,64}");

    private final byte[] secret;
    private final CrawlerProvider provider;
    private final CrawlService crawl;

    public WebhookController(PipelineProperties props, CrawlerProvider provider, CrawlService crawl) {
        this.secret = props.webhook().secret().getBytes(StandardCharsets.UTF_8);
        this.provider = provider;
        this.crawl = crawl;
    }

    @PostMapping("/webhooks/apify/{secret}")
    public ResponseEntity<Void> apify(@PathVariable("secret") String given, @RequestBody JsonNode payload) {
        // Fail closed when no secret is configured. isEqual is constant time for equal-length inputs.
        if (secret.length == 0 || !MessageDigest.isEqual(secret, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String runId = payload.path("eventData").path("actorRunId").asText(null);
        if (runId == null) {
            runId = payload.path("resource").path("id").asText(null);
        }
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            // Never trust the payload's status/resource: ask Apify.
            RunStatus run = provider.getRun(RunRef.of(runId, null));
            Optional<Long> known = crawl.applyRun(run);
            if (known.isEmpty()) {
                log.warn("Webhook for a run we did not start (or a stale state); ignoring");
            }
            return ResponseEntity.ok().build();
        } catch (ApifyException e) {
            // 5xx makes Apify redeliver the webhook later (exponential backoff), which is what we want here.
            log.warn("Webhook: could not verify run with Apify: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
