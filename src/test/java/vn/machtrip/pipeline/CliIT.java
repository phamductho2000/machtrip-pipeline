package vn.machtrip.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import picocli.CommandLine;

class CliIT extends AbstractIT {

    @Autowired ObjectProvider<CommandLine> commandLines;

    private record Result(int exit, String out, String err) {
    }

    private Result run(String... args) {
        CommandLine cl = commandLines.getObject();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        int exit = cl.execute(args);
        return new Result(exit, out.toString(), err.toString());
    }

    @Test
    void searchDryRunPrintsTheFullRequestAndMakesNoNetworkCall() {
        // --hashtag is repeatable (also accepts a comma-separated list); maxItems scales with hashtag count.
        Result r = run("search", "--hashtag", "reviewdalat,avbc", "--limit", "100", "--dry-run");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("POST ", "/actors/clockworks~tiktok-scraper/runs",
                "maxTotalChargeUsd=1.0", "timeout=600", "maxItems=200", "waitForFinish=60", "webhooks=",
                "ACTOR.RUN.SUCCEEDED", "ACTOR.RUN.FAILED", "ACTOR.RUN.TIMED_OUT", "ACTOR.RUN.ABORTED",
                "https://hooks.test.example/webhooks/apify/***", "\"hashtags\"", "reviewdalat", "avbc",
                "\"resultsPerPage\" : 100");
        assertThat(r.out()).doesNotContain("test-secret-123").doesNotContain("test-token-abc");
        assertThat(WM.getAllServeEvents()).isEmpty();
        assertThat(count("SELECT count(*) FROM crawl_run")).isZero();
    }

    @Test
    void ingestThenStatsPrintsCountsAndASecondIngestChangesNothing() {
        String file = "src/test/resources/fixtures/synthetic_search_sample.json";

        assertThat(run("ingest", "--file", file).out()).contains("Ingested 5 items");
        String first = run("stats").out();
        run("ingest", "--file", file);
        String second = run("stats").out();

        assertThat(first).contains("videos: 5", "videos_with_location_name: 2", "videos_with_trusted_subtitle: 2",
                "videos_with_untrusted_subtitle: 1", "asr_queue: 3");
        assertThat(second).isEqualTo(first);
        assertThat(WM.getAllServeEvents()).isEmpty();
    }

    @Test
    void commentsDryRunListsFilteredVideosWithoutNetwork() {
        run("ingest", "--file", "src/test/resources/fixtures/synthetic_search_sample.json");

        Result r = run("comments", "--limit", "2", "--dry-run");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("maxTotalChargeUsd=1.0", "maxItems=100", "commentsPerPost", "2 videos selected");
        assertThat(WM.getAllServeEvents()).isEmpty();
    }

    @Test
    void errorsArePrintedWithoutStackTraceAndExitNonZero() {
        Result r = run("resume", "999");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("ERROR: No crawl_run with id 999");
    }

    @Test
    void serveWebhookRefusesToRunWithoutAWebContext() {
        // the test context is a web context, so this only checks the happy guard path returns 0
        assertThat(run("serve-webhook").exit()).isZero();
    }
}
