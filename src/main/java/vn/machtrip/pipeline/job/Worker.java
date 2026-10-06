package vn.machtrip.pipeline.job;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.config.PipelineProperties;
import vn.machtrip.pipeline.ingest.IngestService;

/** The `work` loop: claim a job, ingest the run's dataset, mark done / retry / failed. */
@Component
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final JobQueue queue;
    private final IngestService ingest;
    private final PipelineProperties.Job cfg;
    private volatile boolean stopping;

    public Worker(JobQueue queue, IngestService ingest, PipelineProperties props) {
        this.queue = queue;
        this.ingest = ingest;
        this.cfg = props.job();
    }

    public void stop() {
        stopping = true;
    }

    /** @param once drain the queue and return instead of waiting for new jobs
     *  @return number of jobs processed (successfully or not) */
    public int run(boolean once) {
        int processed = 0;
        while (!stopping) {
            Optional<JobQueue.Job> job = queue.claim();
            if (job.isEmpty()) {
                if (once) {
                    break;
                }
                try {
                    Thread.sleep(cfg.pollInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            process(job.get());
            processed++;
        }
        return processed;
    }

    private void process(JobQueue.Job job) {
        try {
            if (job.attempts() > cfg.maxAttempts()) {
                throw new IllegalStateException("job exceeded " + cfg.maxAttempts() + " attempts (stale lock)");
            }
            ingest.runJob(job);
            queue.done(job.id());
        } catch (Exception e) {
            log.warn("Job {} failed (attempt {}/{}): {}", job.id(), job.attempts(), cfg.maxAttempts(), e.toString());
            queue.fail(job.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
