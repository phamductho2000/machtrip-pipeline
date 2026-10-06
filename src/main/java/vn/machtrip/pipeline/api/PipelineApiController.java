package vn.machtrip.pipeline.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.machtrip.pipeline.config.ActorInputs;
import vn.machtrip.pipeline.config.PipelineProperties;
import vn.machtrip.pipeline.crawler.ApifyException;
import vn.machtrip.pipeline.crawler.ApifyProvider;
import vn.machtrip.pipeline.crawler.CrawlService;
import vn.machtrip.pipeline.crawler.SearchQuery;
import vn.machtrip.pipeline.ingest.StatsService;
import vn.machtrip.pipeline.job.Worker;

/**
 * HTTP equivalent of the picocli commands in {@link vn.machtrip.pipeline.cli.Cli}, so the pipeline can be triggered
 * without shell access (e.g. from an admin dashboard or another backend). Every endpoint calls the exact same
 * service method as its CLI counterpart; nothing here duplicates business logic. Guarded by {@link ApiKeyFilter}
 * (header {@code X-API-Key}), only reachable while {@code serve-webhook} is running.
 *
 * {@code work} only ever drains the current queue (CLI's {@code --once}): a continuous loop would block the HTTP
 * request forever, so that mode stays CLI-only.
 */
@RestController
@RequestMapping("/api/v1")
public class PipelineApiController {

    private final CrawlService crawl;
    private final ApifyProvider apify;
    private final Worker worker;
    private final StatsService stats;
    private final ActorInputs inputs;
    private final PipelineProperties props;

    public PipelineApiController(CrawlService crawl, ApifyProvider apify, Worker worker, StatsService stats,
                                  ActorInputs inputs, PipelineProperties props) {
        this.crawl = crawl;
        this.apify = apify;
        this.worker = worker;
        this.stats = stats;
        this.inputs = inputs;
        this.props = props;
    }

    public record SearchBody(List<String> hashtags, Integer limit, Boolean dryRun) {
    }

    public record CommentsBody(Integer limit, Boolean dryRun) {
    }

    /** Mirrors {@code search --hashtag --limit [--dry-run]} ({@code hashtags} takes one or more). */
    @PostMapping("/runs/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody SearchBody body) {
        SearchQuery q = new SearchQuery(body.hashtags(), require(body.limit(), "limit"));
        if (Boolean.TRUE.equals(body.dryRun())) {
            return ResponseEntity.ok(Map.of("dryRun", true, "request", apify.describe(apify.searchRequest(q))));
        }
        long id = crawl.startSearch(q);
        return ResponseEntity.ok(Map.of("crawlRunId", id, "message", "Started crawl_run " + id
                + ". Completion arrives by webhook (or POST /api/v1/reconcile); then POST /api/v1/work to ingest."));
    }

    /** Mirrors {@code comments --limit [--dry-run]}. */
    @PostMapping("/runs/comments")
    public ResponseEntity<Map<String, Object>> comments(@RequestBody CommentsBody body) {
        int limit = require(body.limit(), "limit");
        if (!Boolean.TRUE.equals(body.dryRun())) {
            Optional<Long> id = crawl.startComments(limit);
            return ResponseEntity.ok(id.<Map<String, Object>>map(
                    i -> Map.of("crawlRunId", i, "message", "Started crawl_run " + i))
                    .orElseGet(() -> Map.of("message",
                            "No video passes the filters (or all were crawled recently); nothing started.")));
        }
        var picked = crawl.selectForComments(crawl.maxVideos(limit));
        if (picked.isEmpty()) {
            return ResponseEntity.ok(Map.of("dryRun", true, "selected", 0,
                    "message", "no video passes the filters; nothing would be started."));
        }
        int per = props.comments().perVideoCap();
        List<String> urls = picked.stream().map(CrawlService.Candidate::url).toList();
        return ResponseEntity.ok(Map.of("dryRun", true, "selected", picked.size(),
                "request", apify.describe(apify.commentsRequest(urls, per, picked.size() * per))));
    }

    /** Mirrors {@code resume <crawlRunId>}. */
    @PostMapping("/runs/{crawlRunId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable long crawlRunId) {
        return ResponseEntity.ok(Map.of("result", crawl.resume(crawlRunId)));
    }

    /** Mirrors {@code work --once}; always drains and returns (never the continuous loop). */
    @PostMapping("/work")
    public ResponseEntity<Map<String, Object>> work() {
        return ResponseEntity.ok(Map.of("processed", worker.run(true)));
    }

    /** Mirrors {@code reconcile}. */
    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile() {
        return ResponseEntity.ok(Map.of("reconciled", crawl.reconcile()));
    }

    /** Mirrors {@code stats}. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, String>> stats() {
        return ResponseEntity.ok(stats.snapshot());
    }

    /** Mirrors {@code validate-input --kind search|comments} (real Apify API call). */
    @PostMapping("/validate-input")
    public ResponseEntity<Map<String, Object>> validateInput(@RequestParam String kind) {
        var result = apify.validateInput(inputs.sample(kind));
        return ResponseEntity.status(result.valid() ? HttpStatus.OK : HttpStatus.BAD_REQUEST)
                .body(Map.of("valid", result.valid(), "message", result.message()));
    }

    private static int require(Integer value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ApifyException.class)
    public ResponseEntity<Map<String, String>> apifyFailed(ApifyException e) {
        HttpStatus status = e.httpStatus() > 0 ? HttpStatus.valueOf(e.httpStatus()) : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
