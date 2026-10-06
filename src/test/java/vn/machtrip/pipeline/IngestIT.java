package vn.machtrip.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import vn.machtrip.pipeline.crawler.CrawlService;
import vn.machtrip.pipeline.ingest.IngestService;
import vn.machtrip.pipeline.ingest.StatsService;
import vn.machtrip.pipeline.job.Worker;

class IngestIT extends AbstractIT {

    private static final Path SYNTHETIC = Path.of("src/test/resources/fixtures/synthetic_search_sample.json");
    private static final Path REAL = Path.of(
            "src/test/resources/fixtures/dataset_tiktok-scraper_2026-10-05_15-27-25-301.json");

    @Autowired IngestService ingest;
    @Autowired StatsService stats;
    @Autowired Worker worker;
    @Autowired CrawlService crawl;
    @Autowired ObjectMapper mapper;

    @TempDir Path tmp;

    private Path fixtureWithCdn() throws IOException {
        Path f = tmp.resolve("sample.json");
        Files.writeString(f, Files.readString(SYNTHETIC).replace("__CDN__", WM.baseUrl()));
        return f;
    }

    // ---- offline ingest ---------------------------------------------------------------------------------------

    @Test
    void offlineIngestClassifiesVideosAndIsIdempotent() {
        var first = ingest.ingestFile(SYNTHETIC, "search");
        Map<String, String> s1 = stats.snapshot();
        ingest.ingestFile(SYNTHETIC, "search");
        Map<String, String> s2 = stats.snapshot();

        assertThat(first.items()).isEqualTo(5);
        assertThat(s1).containsEntry("videos", "5")
                .containsEntry("videos_with_location_name", "2")
                .containsEntry("videos_with_trusted_subtitle", "2")
                .containsEntry("videos_with_untrusted_subtitle", "1")
                .containsEntry("asr_queue", "3")
                .containsEntry("asr_queue.no_subtitle", "2")
                .containsEntry("asr_queue.untrusted_version", "1")
                .containsEntry("audio_files", "0");
        assertThat(s2).isEqualTo(s1);
        assertThat(count("SELECT count(*) FROM raw_item")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM crawl_run")).isEqualTo(1);
        assertThat(WM.getAllServeEvents()).as("offline ingest must make no network call").isEmpty();
    }

    @Test
    void realExportMatchesTheExpectedCountsWhenPresent() {
        assumeTrue(Files.exists(REAL), "real fixture not placed in src/test/resources/fixtures yet");

        ingest.ingestFile(REAL, "search");
        Map<String, String> s1 = stats.snapshot();
        ingest.ingestFile(REAL, "search");

        assertThat(s1).containsEntry("videos", "100")
                .containsEntry("videos_with_location_name", "35")
                .containsEntry("videos_with_trusted_subtitle", "68")
                .containsEntry("videos_with_untrusted_subtitle", "10")
                .containsEntry("asr_queue", "32");
        assertThat(stats.snapshot()).isEqualTo(s1);
    }

    @Test
    void usernamesNeverReachTheDatabaseOnlySaltedHashes() throws Exception {
        ingest.ingestFile(SYNTHETIC, "search");

        String expected = sha256Hex("test-salt" + "1001");
        assertThat(jdbc.sql("SELECT author_hash FROM video WHERE tiktok_id = '7000000000000000001'")
                .query(String.class).single()).isEqualTo(expected);

        // every column of every table of schema pipeline, except video.web_video_url (see below)
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'pipeline'
                AND table_name <> 'flyway_schema_history'""").query(String.class).list();
        for (String t : tables) {
            String dump = jdbc.sql("SELECT coalesce(string_agg(((row_to_json(x)::jsonb) - 'web_video_url')::text, ' '), '') "
                    + "FROM pipeline." + t + " x").query(String.class).single();
            assertThat(dump).as("table " + t).doesNotContain("secretuser");
        }
        // web_video_url is part of the requested schema and TikTok URLs embed the @handle: known, reported to the owner
        assertThat(jdbc.sql("SELECT web_video_url FROM video WHERE tiktok_id = '7000000000000000001'")
                .query(String.class).single()).contains("@secretuser1");
    }

    // ---- live (worker) ingest through WireMock ----------------------------------------------------------------

    private void stubDataset(String datasetId, Path file) throws IOException {
        JsonNode all = mapper.readTree(file.toFile());
        for (int offset = 0; offset < all.size(); offset += 2) {
            var page = mapper.createArrayNode();
            for (int i = offset; i < Math.min(offset + 2, all.size()); i++) {
                page.add(all.get(i));
            }
            WM.stubFor(get(urlPathEqualTo("/v2/datasets/" + datasetId + "/items"))
                    .withQueryParam("offset", equalTo("" + offset)).withQueryParam("limit", equalTo("2"))
                    .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                            .withBody(page.toString())));
        }
        if (all.size() % 2 == 0) {
            WM.stubFor(get(urlPathEqualTo("/v2/datasets/" + datasetId + "/items"))
                    .withQueryParam("offset", equalTo("" + all.size()))
                    .willReturn(aResponse().withStatus(200).withBody("[]")));
        }
    }

    private void queueJob(String runId, String kind) {
        seedRun(runId, kind, "SUCCEEDED");
        jdbc.sql("INSERT INTO job (kind, apify_run_id) VALUES (:k, :r)").param("k", kind).param("r", runId).update();
    }

    @Test
    void workerIngestsDatasetDownloadsExpiringLinksAndSkipsDeadOnes() throws Exception {
        stubDataset("ds-RUNL", fixtureWithCdn());
        WM.stubFor(get(urlPathEqualTo("/cdn/v1.vtt")).willReturn(aResponse().withStatus(200)
                .withBody("WEBVTT\n\n00:00.000 --> 00:02.000\nxin chao")));
        WM.stubFor(get(urlPathEqualTo("/cdn/v5.vtt")).willReturn(aResponse().withStatus(403)));
        WM.stubFor(get(urlPathEqualTo("/cdn/v2.mp3")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "audio/mpeg").withBody("fake-audio-bytes")));
        queueJob("RUNL", "search");

        assertThat(worker.run(true)).isEqualTo(1);

        assertThat(jdbc.sql("SELECT status FROM job").query(String.class).single()).isEqualTo("done");
        assertThat(count("SELECT count(*) FROM video")).isEqualTo(5);
        assertThat(jdbc.sql("SELECT item_count FROM crawl_run").query(Long.class).single()).isEqualTo(5);
        // trusted subtitle downloaded immediately; MT and untrusted ones are never downloaded
        assertThat(jdbc.sql("SELECT vtt_text FROM video_subtitle WHERE video_id = '7000000000000000001'")
                .query(String.class).single()).contains("xin chao");
        WM.verify(0, getRequestedFor(urlPathEqualTo("/cdn/v1-en.vtt")));
        WM.verify(0, getRequestedFor(urlPathEqualTo("/cdn/v2.vtt")));
        WM.verify(getRequestedFor(urlPathEqualTo("/cdn/v1.vtt"))
                .withHeader("Referer", equalTo("https://www.tiktok.com/"))
                .withHeader("User-Agent", matching("Mozilla.+")));
        // v5: trusted link answered 403 -> no text -> falls back to the ASR queue, run keeps going
        assertThat(jdbc.sql("SELECT reason FROM video_asr_queue WHERE video_id = '7000000000000000005'")
                .query(String.class).single()).isEqualTo("no_subtitle");
        // audio only for queued videos that pass the filters: v2 yes; v3 (ad) and v4 (10s) no
        assertThat(jdbc.sql("SELECT video_id FROM video_audio").query(String.class).list())
                .containsExactly("7000000000000000002");
        WM.verify(0, getRequestedFor(urlPathEqualTo("/cdn/v3.mp3")));
        WM.verify(0, getRequestedFor(urlPathEqualTo("/cdn/v4.mp3")));
        Path audio = Path.of(jdbc.sql("SELECT storage_path FROM video_audio").query(String.class).single());
        assertThat(audio).hasContent("fake-audio-bytes").hasFileName("7000000000000000002.mp3");
        // raw JSON: gzip, one file per run, complete
        try (var in = new GZIPInputStream(Files.newInputStream(RAW_DIR.resolve("RUNL.json.gz")))) {
            assertThat(mapper.readTree(in)).hasSize(5);
        }

        // replaying the job (duplicate delivery, retry) changes nothing
        Map<String, String> before = stats.snapshot();
        jdbc.sql("UPDATE job SET status = 'pending'").update();
        worker.run(true);
        assertThat(stats.snapshot()).isEqualTo(before);
    }

    @Test
    void deadCdnLinksNeverCrashTheJob() throws Exception {
        stubDataset("ds-RUND", fixtureWithCdn());
        WM.stubFor(get(urlPathEqualTo("/cdn/v1.vtt")).willReturn(aResponse().withStatus(404)));
        WM.stubFor(get(urlPathEqualTo("/cdn/v5.vtt")).willReturn(aResponse().withStatus(403)));
        WM.stubFor(get(urlPathEqualTo("/cdn/v2.mp3")).willReturn(aResponse().withStatus(404)));
        queueJob("RUND", "search");

        worker.run(true);

        assertThat(jdbc.sql("SELECT status FROM job").query(String.class).single()).isEqualTo("done");
        assertThat(count("SELECT count(*) FROM video")).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM video_audio")).isZero();
        assertThat(count("SELECT count(*) FROM video_asr_queue")).isEqualTo(5);
    }

    @Test
    void failingJobIsRetriedThenMarkedFailedWithLastError() {
        WM.stubFor(get(urlPathEqualTo("/v2/datasets/ds-RUNX/items")).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":{\"type\":\"record-not-found\",\"message\":\"Dataset not found\"}}")));
        queueJob("RUNX", "search");

        worker.run(true);

        var job = jdbc.sql("SELECT status, attempts, last_error FROM job")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getString(3)}).single();
        assertThat(job[0]).isEqualTo("failed");
        assertThat(job[1]).isEqualTo(2); // pipeline.job.max-attempts=2 in tests
        assertThat((String) job[2]).contains("ApifyException").doesNotContain("test-token-abc");
    }

    @Test
    void commentRunsKeepRawDataAndWriteAValuesFreeSchemaReport() throws Exception {
        Path comments = tmp.resolve("comments.json");
        Files.writeString(comments, "[{\"cid\":\"c1\",\"text\":\"very secret words\",\"user\":{\"uniqueId\":\"someone\"}},"
                + "{\"cid\":\"c2\",\"text\":\"more\",\"diggCount\":3}]");
        stubDataset("ds-RUNK", comments);
        queueJob("RUNK", "comments");

        worker.run(true);

        assertThat(jdbc.sql("SELECT status FROM job").query(String.class).single()).isEqualTo("done");
        assertThat(count("SELECT count(*) FROM comment")).as("mapping is built only after schema review").isZero();
        String report = Files.readString(RAW_DIR.resolve("RUNK.schema.txt"));
        assertThat(report).contains("items: 2", "cid : string (in 2)", "user.uniqueId : string (in 1)",
                "diggCount : number (in 1)").doesNotContain("very secret words").doesNotContain("someone");
    }

    // ---- crawl_run / job lifecycle ----------------------------------------------------------------------------

    @Test
    void reconcileChecksOnlyStaleNonTerminalRuns() {
        seedRun("OLDRUN", "search", "RUNNING");
        jdbc.sql("UPDATE crawl_run SET started_at = now() - interval '2 hours'").update();
        seedRun("NEWRUN", "search", "RUNNING"); // started just now: not stale, must not be requested
        stubRun("OLDRUN", "SUCCEEDED", "0.1");

        assertThat(crawl.reconcile()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT status FROM crawl_run WHERE apify_run_id = 'OLDRUN'").query(String.class).single())
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT status FROM crawl_run WHERE apify_run_id = 'NEWRUN'").query(String.class).single())
                .isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM job WHERE apify_run_id = 'OLDRUN'")).isEqualTo(1);
        WM.verify(0, getRequestedFor(urlPathEqualTo("/v2/actor-runs/NEWRUN")));
    }

    @Test
    void resumeQueuesTheJobAndRequeuesAFailedOne() {
        seedRun("RUNR", "search", "RUNNING");
        stubRun("RUNR", "SUCCEEDED", "0.1");
        long id = jdbc.sql("SELECT id FROM crawl_run").query(Long.class).single();

        assertThat(crawl.resume(id)).contains("SUCCEEDED").contains("ingest job queued");
        jdbc.sql("UPDATE job SET status = 'failed', attempts = 3, last_error = 'boom'").update();
        assertThat(crawl.resume(id)).contains("requeued");

        var job = jdbc.sql("SELECT status, attempts FROM job")
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2)}).single();
        assertThat(job).containsExactly("pending", 0);
    }

    @Test
    void commentsRunUsesOnlyFilteredVideosWithCapsAndIsNotRepeated() throws Exception {
        ingest.ingestFile(SYNTHETIC, "search");
        // eligible: commentCount > 0, >= 30s, vi, not an ad -> v5 (12), v2 (9), v1 (5); v3 is an ad, v4 is 10s
        assertThat(crawl.selectForComments(10)).extracting(CrawlService.Candidate::tiktokId)
                .containsExactly("7000000000000000005", "7000000000000000002", "7000000000000000001");

        WM.stubFor(post(urlPathEqualTo("/v2/actors/clockworks~tiktok-scraper/runs"))
                .willReturn(aResponse().withStatus(201).withBody(runJson("RUNCOM", "RUNNING", "0"))));
        long id = crawl.startComments(10).orElseThrow();

        WM.verify(postRequestedFor(urlPathEqualTo("/v2/actors/clockworks~tiktok-scraper/runs"))
                .withQueryParam("maxTotalChargeUsd", equalTo("1.0")).withQueryParam("timeout", equalTo("600"))
                .withQueryParam("maxItems", equalTo("150")));
        JsonNode body = mapper.readTree(WM.getAllServeEvents().get(0).getRequest().getBodyAsString());
        assertThat(body.path("postURLs")).hasSize(3);
        assertThat(body.path("commentsPerPost").asInt()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT kind FROM crawl_run WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("comments");
        assertThat(crawl.selectForComments(10)).as("recently crawled videos are skipped").isEmpty();
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return String.format("%064x", new BigInteger(1, d));
    }
}
