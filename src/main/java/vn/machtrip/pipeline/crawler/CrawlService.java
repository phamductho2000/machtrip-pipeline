package vn.machtrip.pipeline.crawler;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.machtrip.pipeline.config.PipelineProperties;
import vn.machtrip.pipeline.ingest.Filters;

/** Starting runs, recording their state and creating ingest jobs; shared by CLI, webhook and reconcile. */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private static final String TERMINAL_SQL = "('SUCCEEDED','FAILED','TIMED-OUT','ABORTED')";

    public record Candidate(String tiktokId, String url) {
    }

    private final JdbcClient jdbc;
    private final CrawlerProvider provider;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Filters filters;
    private final PipelineProperties props;

    public CrawlService(JdbcClient jdbc, CrawlerProvider provider, TransactionTemplate tx, ObjectMapper mapper,
                        Filters filters, PipelineProperties props) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.tx = tx;
        this.mapper = mapper;
        this.filters = filters;
        this.props = props;
    }

    /** @return crawl_run id */
    public long startSearch(SearchQuery query) {
        return record("search", provider.startSearch(query));
    }

    /** Videos that passed the filters and are worth a comments run, most commented first. */
    public List<Candidate> selectForComments(int maxVideos) {
        return jdbc.sql("""
                SELECT tiktok_id, web_video_url FROM video
                WHERE web_video_url IS NOT NULL AND comment_count > 0
                  AND duration_sec >= :minDuration AND lower(language) IN (:languages)
                  AND (NOT :skipAds OR NOT is_ad)
                  AND (comments_crawled_at IS NULL
                       OR comments_crawled_at < now() - make_interval(days => :recrawlDays))
                ORDER BY comment_count DESC, tiktok_id
                LIMIT :limit""")
                .param("minDuration", filters.minDurationSec())
                .param("languages", filters.allowedLanguages())
                .param("skipAds", filters.skipAds())
                .param("recrawlDays", filters.skipRecrawlDays())
                .param("limit", maxVideos)
                .query((rs, n) -> new Candidate(rs.getString("tiktok_id"), rs.getString("web_video_url"))).list();
    }

    /** Max number of videos for one comments run: --limit, further bounded by the total items cap. */
    public int maxVideos(int limit) {
        PipelineProperties.Comments c = props.comments();
        return Math.max(1, Math.min(limit, c.totalItemsCap() / Math.max(1, c.perVideoCap())));
    }

    /** @return crawl_run id, or empty when no video qualifies */
    public Optional<Long> startComments(int limit) {
        List<Candidate> picked = selectForComments(maxVideos(limit));
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        RunRef ref = provider.startComments(picked.stream().map(Candidate::url).toList(),
                props.comments().perVideoCap());
        long id = record("comments", ref);
        // Marked at start so a re-run does not pay twice for the same videos (also if the run later fails).
        jdbc.sql("UPDATE video SET comments_crawled_at = now() WHERE tiktok_id IN (:ids)")
                .param("ids", picked.stream().map(Candidate::tiktokId).toList()).update();
        return Optional.of(id);
    }

    /** Persists a freshly started run immediately, then applies its status (a short run may already be finished). */
    private long record(String kind, RunRef ref) {
        long id = jdbc.sql("""
                INSERT INTO crawl_run (kind, provider, actor_id, input, apify_run_id, dataset_id, status,
                                       max_total_charge_usd)
                VALUES (:kind, 'apify', :actor, CAST(:input AS jsonb), :runId, :dataset, :status, :maxCharge)
                ON CONFLICT (apify_run_id) DO UPDATE SET dataset_id = EXCLUDED.dataset_id
                RETURNING id""")
                .param("kind", kind).param("actor", ref.actorId()).param("input", ref.input().toString())
                .param("runId", ref.runId()).param("dataset", ref.datasetId()).param("status", ref.status().status())
                .param("maxCharge", ref.maxTotalChargeUsd()).query(Long.class).single();
        applyRun(ref.status());
        return id;
    }

    /**
     * Applies a run state fetched from the provider. For a terminal state it also records cost/build/pricing and
     * creates the ingest job (ON CONFLICT DO NOTHING, so duplicate calls are harmless).
     *
     * @return the crawl_run id, or empty if the run is unknown to us
     */
    public Optional<Long> applyRun(RunStatus run) {
        return tx.execute(status -> {
            boolean terminal = run.isTerminal();
            var row = jdbc.sql("""
                    UPDATE crawl_run SET status = :status,
                        dataset_id = COALESCE(:dataset, dataset_id),
                        actor_build = COALESCE(:build, actor_build),
                        pricing_model = COALESCE(:pricing, pricing_model),
                        cost_usd = COALESCE(:cost, cost_usd),
                        finished_at = CASE WHEN :terminal THEN COALESCE(finished_at, now()) ELSE finished_at END
                    WHERE apify_run_id = :runId
                      AND (:terminal OR status NOT IN """ + TERMINAL_SQL + """
                    )
                    RETURNING id, kind""")
                    .param("status", run.status()).param("dataset", run.datasetId()).param("build", run.actorBuild())
                    .param("pricing", run.pricingModel()).param("cost", run.usageTotalUsd())
                    .param("terminal", terminal).param("runId", run.runId())
                    .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getString("kind")}).optional();
            if (row.isPresent() && terminal) {
                jdbc.sql("""
                        INSERT INTO job (kind, apify_run_id) VALUES (:kind, :runId)
                        ON CONFLICT (apify_run_id, kind) DO NOTHING""")
                        .param("kind", row.get()[1]).param("runId", run.runId()).update();
            }
            return row.map(r -> (Long) r[0]);
        });
    }

    /** `resume`: re-check one run with Apify, (re)create its job, and requeue the job if it had failed. */
    public String resume(long crawlRunId) {
        var row = jdbc.sql("SELECT apify_run_id, dataset_id, kind FROM crawl_run WHERE id = :id").param("id", crawlRunId)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).optional()
                .orElseThrow(() -> new IllegalArgumentException("No crawl_run with id " + crawlRunId));
        RunStatus run = provider.getRun(RunRef.of(row[0], row[1]));
        applyRun(run);
        int requeued = jdbc.sql("""
                UPDATE job SET status = 'pending', attempts = 0, last_error = NULL, locked_at = NULL
                WHERE apify_run_id = :runId AND kind = :kind AND status = 'failed'""")
                .param("runId", row[0]).param("kind", row[2]).update();
        return "crawl_run " + crawlRunId + ": Apify status " + run.status()
                + (run.isTerminal() ? ", ingest job queued" + (requeued > 0 ? " (failed job requeued)" : "")
                : ", still running; the webhook or `reconcile` will pick it up");
    }

    /** Polling fallback: check runs stuck in a non-terminal state, and terminal runs that lost their job. */
    public int reconcile() {
        var stuck = jdbc.sql("""
                SELECT apify_run_id, dataset_id FROM crawl_run c
                WHERE provider = 'apify'
                  AND ((status NOT IN """ + TERMINAL_SQL + """
                        AND started_at < now() - make_interval(secs => :staleSecs))
                    OR (status IN """ + TERMINAL_SQL + """
                        AND NOT EXISTS (SELECT 1 FROM job j WHERE j.apify_run_id = c.apify_run_id AND j.kind = c.kind)))
                ORDER BY id""")
                .param("staleSecs", props.reconcile().staleAfter().toSeconds())
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
        for (String[] r : stuck) {
            RunStatus run = provider.getRun(RunRef.of(r[0], r[1]));
            applyRun(run);
            log.info("Reconciled run {} -> {}", r[0], run.status());
        }
        return stuck.size();
    }
}
