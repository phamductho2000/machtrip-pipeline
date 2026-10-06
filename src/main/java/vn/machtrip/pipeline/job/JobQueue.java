package vn.machtrip.pipeline.job;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.config.PipelineProperties;

/** pipeline.job as a work queue: FOR UPDATE SKIP LOCKED claim, bounded attempts, last_error on failure. */
@Component
public class JobQueue {

    public record Job(long id, String kind, String apifyRunId, int attempts) {
    }

    private final JdbcClient jdbc;
    private final PipelineProperties.Job cfg;

    public JobQueue(JdbcClient jdbc, PipelineProperties props) {
        this.jdbc = jdbc;
        this.cfg = props.job();
    }

    /** Claims the oldest pending job (or one whose worker died: 'running' for longer than lock-timeout). */
    public Optional<Job> claim() {
        return jdbc.sql("""
                UPDATE job SET status = 'running', locked_at = now(), attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM job
                    WHERE status = 'pending'
                       OR (status = 'running' AND locked_at < now() - make_interval(secs => :lockSecs))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING id, kind, apify_run_id, attempts""")
                .param("lockSecs", cfg.lockTimeout().toSeconds())
                .query((rs, n) -> new Job(rs.getLong("id"), rs.getString("kind"), rs.getString("apify_run_id"),
                        rs.getInt("attempts")))
                .optional();
    }

    public void done(long id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, locked_at = NULL WHERE id = :id")
                .param("id", id).update();
    }

    /** Back to pending while attempts remain, otherwise 'failed' for good. */
    public void fail(long id, String error) {
        jdbc.sql("""
                UPDATE job SET status = CASE WHEN attempts >= :max THEN 'failed' ELSE 'pending' END,
                               last_error = :err, locked_at = NULL
                WHERE id = :id""")
                .param("max", cfg.maxAttempts()).param("err", truncate(error)).param("id", id).update();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
