package vn.machtrip.pipeline.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import picocli.CommandLine;

import vn.machtrip.pipeline.AbstractIT;
import vn.machtrip.pipeline.extract.ExtractionService.Options;

/** Whole stage 2 pipeline against Postgres, with a scripted FakeLlmClient: no LLM, no network. */
@Import(ExtractionIT.FakeConfig.class)
@TestPropertySource(properties = {
        "extract.model=test-model",
        "extract.pricing.input-per-token=0.000001",
        "extract.pricing.output-per-token=0.000002",
        "extract.max-output-tokens=1000",
        "extract.concurrency=1"})
class ExtractionIT extends AbstractIT {

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeLlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    private static final String CHO_DEM_CAPTION = "Kinh nghiệm đi chợ đêm Đà Lạt";
    private static final String CHO_DEM_VTT = """
            WEBVTT

            00:00:03.900 --> 00:00:06.000
            2 tô bún 400.000 riêu đó, cháo lòng 300.000

            00:00:06.900 --> 00:00:09.000
            chụp hình chỗ này coi chừng mất 50.000

            00:00:20.900 --> 00:00:24.000
            đi đâu chịu khó check Google chứ đừng kêu taxi
            """;
    private static final String CAFE_VTT = """
            WEBVTT

            00:00:02.000 --> 00:00:05.000
            hôm nay mình đến Sương Mù Coffee nằm trên đồi thông

            00:00:09.000 --> 00:00:12.000
            view săn mây cực đẹp, ly cà phê muối 45k

            00:00:15.000 --> 00:00:18.000
            chỗ này hợp đi cặp đôi hoặc đi chụp ảnh
            """;

    @Autowired FakeLlmClient fake;
    @Autowired ExtractionService extraction;
    @Autowired ExtractionReporting reporting;
    @Autowired PromptLoader prompts;
    @Autowired ObjectMapper mapper;
    @Autowired ObjectProvider<CommandLine> commandLines;
    @TempDir Path tmp;

    @BeforeEach
    void resetFake() {
        fake.reset();
        fake.failWith("no response scripted");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String exampleOutput(int n) {
        return PromptAndGatewayTest.exampleOutputs(prompts.load("v1").system()).get(n - 1);
    }

    private void seedVideo(String id, String caption, String vtt) throws IOException {
        jdbc.sql("""
                INSERT INTO video (tiktok_id, web_video_url, author_hash, language, duration_sec, comment_count,
                                   hashtags, location_name, location_city)
                VALUES (:id, 'https://www.tiktok.com/@secretuser/video/' || :id, 'AUTHORHASH-XYZ', 'vi', 60, 10,
                        ARRAY['dalat'], NULL, 'Da Lat')""").param("id", id).update();
        if (vtt != null) {
            jdbc.sql("""
                    INSERT INTO video_subtitle (video_id, language, source, version, trusted, vtt_text)
                    VALUES (:id, 'vie-VN', 'ASR', '1::whisper_lid', true, :vtt)""").param("id", id).param("vtt", vtt)
                    .update();
        }
        if (caption != null) {
            Path raw = tmp.resolve("raw-" + id + ".json");
            Files.writeString(raw, mapper.createArrayNode().add(mapper.createObjectNode().put("id", id)
                    .put("text", caption)).toString());
            seedRun("RUN-" + id, "search", "SUCCEEDED");
            jdbc.sql("""
                    INSERT INTO raw_item (crawl_run_id, item_id, raw_path)
                    VALUES ((SELECT id FROM crawl_run WHERE apify_run_id = :run), :id, :path)""")
                    .param("run", "RUN-" + id).param("id", id).param("path", "file:" + raw).update();
        }
    }

    private void seedComment(String id, String videoId, String text, int likes) {
        jdbc.sql("""
                INSERT INTO comment (id, video_id, author_hash, text, like_count)
                VALUES (:id, :video, 'COMMENTHASH-ABC', :text, :likes)""")
                .param("id", id).param("video", videoId).param("text", text).param("likes", likes).update();
    }

    private Options opts(int limit) {
        return new Options("v1", "test-model", limit, List.of(), false, null);
    }

    private ObjectNode placeMention(String name, String quote) throws IOException {
        ObjectNode m = (ObjectNode) mapper.readTree(exampleOutput(2)).path("mentions").get(0).deepCopy();
        m.put("name_raw", name);
        ((ObjectNode) m.path("evidence")).put("quote", quote);
        return m;
    }

    private String withExtraMention(String output, ObjectNode extra) throws IOException {
        ObjectNode root = (ObjectNode) mapper.readTree(output);
        ((ArrayNode) root.get("mentions")).add(extra);
        return root.toString();
    }

    private record Result(int exit, String out, String err) {
    }

    private Result cli(String... args) {
        CommandLine cl = commandLines.getObject();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cl.setOut(new PrintWriter(out, true));
        cl.setErr(new PrintWriter(err, true));
        int exit = cl.execute(args);
        return new Result(exit, out.toString(), err.toString());
    }

    // ---- the real example -----------------------------------------------------------------------------------------

    @Test
    void choDemExampleYieldsPriceWarningAndTipMentionsAndNoPlace() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(exampleOutput(1), 1200, 400);

        var summary = extraction.execute(opts(5));

        assertThat(summary.ok()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT kind FROM mention ORDER BY id").query(String.class).list())
                .containsExactly("price", "price", "warning", "tip");
        assertThat(count("SELECT count(*) FROM mention WHERE kind = 'place' OR name_raw IS NOT NULL")).isZero();
        assertThat(count("SELECT count(*) FROM mention_rejected")).isZero();
        assertThat(jdbc.sql("SELECT price_vnd_min FROM mention WHERE price_text = '50.000'").query(Long.class).single())
                .isEqualTo(50000L);
        var row = jdbc.sql("SELECT status, input_tokens, output_tokens, tokens_estimated, cost_usd, raw_response_path "
                + "FROM video_extraction").query((rs, n) -> new Object[]{rs.getString(1), rs.getInt(2), rs.getInt(3),
                rs.getBoolean(4), rs.getBigDecimal(5), rs.getString(6)}).single();
        assertThat(row[0]).isEqualTo("ok");
        assertThat(row[1]).isEqualTo(1200);
        assertThat(row[2]).isEqualTo(400);
        assertThat(row[3]).isEqualTo(false);
        assertThat((BigDecimal) row[4]).isEqualByComparingTo("0.002");
        try (var in = new GZIPInputStream(Files.newInputStream(Path.of((String) row[5])))) {
            assertThat(mapper.readTree(in).get(0).path("text").asText()).contains("\"mentions\"");
        }
        assertThat(summary.costUsd()).isEqualByComparingTo("0.002");
    }

    @Test
    void whatIsSentToTheModelHasNoPeopleIdentifiersAndIsAFaithfulInput() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        seedComment("c1", "v1", "Hồi đó ở đây rẻ lắm", 7);
        fake.respondWith("{\"mentions\": []}");

        extraction.execute(opts(5));

        LlmRequest req = fake.requests().get(0);
        assertThat(req.model()).isEqualTo("test-model");
        assertThat(req.user()).contains("<caption>\nKinh nghiệm đi chợ đêm Đà Lạt\n</caption>",
                "[00:03] 2 tô bún 400.000 riêu đó, cháo lòng 300.000", "<comment id=\"c1\" likes=\"7\">Hồi đó ở đây rẻ lắm</comment>");
        assertThat(req.user()).doesNotContain("AUTHORHASH").doesNotContain("COMMENTHASH").doesNotContain("secretuser")
                .doesNotContain("@");
        assertThat(req.system()).contains("cloud-hunting").doesNotContain("{{TAGS}}");
        assertThat(req.idempotencyKey()).startsWith("machtrip-pipeline-extract-");
        assertThat(req.jsonSchema()).isNull(); // structured output stays off until Genway's doc says it is supported
    }

    @Test
    void bothFewShotExamplesAreAcceptedUnchangedByTheValidator() throws Exception {
        seedVideo("v2", "Cafe view đồi thông cực chill #dalat #cafedalat", CAFE_VTT);
        seedComment("c101", "v2", "Quán này đóng cửa từ tháng 8 rồi mọi người ơi", 87);
        seedComment("c102", "v2", "Đẹp quá trời luôn", 12);
        fake.respondWith(exampleOutput(2));

        extraction.execute(opts(5));

        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM mention_rejected")).isZero();
        assertThat(jdbc.sql("SELECT tags FROM mention WHERE kind = 'place'").query(String.class).single())
                .isEqualTo("{cloud-hunting,cafe-view,photo-spot}");
    }

    // ---- validation after the model answers -------------------------------------------------------------------

    @Test
    void anInventedPlaceWhoseQuoteIsNotInTheInputIsDroppedAndAudited() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(withExtraMention(exampleOutput(1),
                placeMention("Chợ Đêm Đà Lạt", "Chợ đêm Đà Lạt có bún riêu ngon nhất")));

        extraction.execute(opts(5));

        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM mention WHERE kind = 'place'")).isZero();
        assertThat(jdbc.sql("SELECT reason FROM mention_rejected").query(String.class).single())
                .isEqualTo("quote_not_in_input");
        assertThat(jdbc.sql("SELECT payload->>'name_raw' FROM mention_rejected").query(String.class).single())
                .isEqualTo("Chợ Đêm Đà Lạt");
    }

    @Test
    void tagsOutsideTheListAreRemovedBeforeStoring() throws Exception {
        seedVideo("v2", "Cafe view đồi thông cực chill", CAFE_VTT);
        ObjectNode root = (ObjectNode) mapper.readTree(exampleOutput(2));
        ((ObjectNode) root.get("mentions").get(0)).putArray("tags").add("cafe-view").add("invented-tag");
        fake.respondWith(root.toString());

        extraction.execute(opts(5));

        assertThat(jdbc.sql("SELECT tags FROM mention WHERE kind = 'place'").query(String.class).single())
                .isEqualTo("{cafe-view}");
    }

    @Test
    void invalidJsonGetsExactlyOneRepairAttemptThenTheVideoFails() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith("sorry, here are the places: Chợ Đêm");

        var summary = extraction.execute(opts(5));

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(fake.requests()).hasSize(2);
        assertThat(fake.requests().get(1).user()).contains("<previous_response>", "<validation_errors>",
                "not valid JSON");
        var row = jdbc.sql("SELECT status, error FROM video_extraction")
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        assertThat(row[0]).isEqualTo("failed");
        assertThat(row[1]).contains("invalid output after 2 attempt(s)");
        assertThat(count("SELECT count(*) FROM mention")).isZero();
    }

    @Test
    void schemaInvalidJsonIsAlsoRepairedOnce() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith("{\"mentions\": [{\"kind\": \"restaurant\"}]}", exampleOutput(1));

        var summary = extraction.execute(opts(5));

        assertThat(summary.ok()).isEqualTo(1);
        assertThat(fake.requests()).hasSize(2);
        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT error FROM video_extraction").query(String.class).optional()).isEmpty();
    }

    @Test
    void aGatewayFailureFailsOnlyThatVideo() throws Exception {
        seedVideo("good", CHO_DEM_CAPTION, CHO_DEM_VTT);
        seedVideo("bad", "BADMARK", CHO_DEM_VTT);
        fake.respondWith(r -> {
            if (r.user().contains("BADMARK")) {
                throw new LlmException("Genway answered HTTP 503 (not retried)");
            }
            return new LlmResult(exampleOutput(1), null, null, r.model());
        });

        var summary = extraction.execute(opts(5));

        assertThat(summary.ok()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT error FROM video_extraction WHERE video_id = 'bad'").query(String.class).single())
                .contains("503");
        assertThat(fake.requests()).hasSize(2); // the failed video was not retried or repaired
    }

    // ---- selection, idempotency, caps ---------------------------------------------------------------------------

    @Test
    void runningExtractTwiceDoesNotDuplicateExtractionsMentionsOrCalls() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(exampleOutput(1));

        extraction.execute(opts(5));
        var second = extraction.execute(opts(5));

        assertThat(second.selected()).isZero();
        assertThat(fake.requests()).hasSize(1);
        assertThat(count("SELECT count(*) FROM video_extraction")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(4);

        extraction.execute(new Options("v1", "test-model", 5, List.of(), true, null)); // --force replaces in place
        assertThat(fake.requests()).hasSize(2);
        assertThat(count("SELECT count(*) FROM video_extraction")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(4);
    }

    @Test
    void anotherModelOrPromptVersionIsASeparateExtraction() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(exampleOutput(1));

        extraction.execute(opts(5));
        extraction.execute(new Options("v1", "other-model", 5, List.of(), false, null));

        assertThat(count("SELECT count(*) FROM video_extraction")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM mention")).isEqualTo(8);
    }

    @Test
    void videosWithNoTranscriptAndNoCommentsAreSkippedWithAReasonAndAsrQueueIsOutOfScope() throws Exception {
        seedVideo("empty", null, null);
        seedVideo("queued", null, null);
        seedComment("cq", "queued", "bình luận", 1);
        jdbc.sql("INSERT INTO video_asr_queue (video_id, reason) VALUES ('queued', 'no_subtitle')").update();
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(exampleOutput(1));

        var summary = extraction.execute(opts(5));

        assertThat(summary.ok()).isEqualTo(1);
        assertThat(fake.requests()).hasSize(1);
        var skipped = jdbc.sql("SELECT status, skip_reason FROM video_extraction WHERE video_id = 'empty'")
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).single();
        assertThat(skipped).containsExactly("skipped", "no_transcript_no_comments");
        assertThat(count("SELECT count(*) FROM video_extraction WHERE video_id = 'queued'")).isZero();

        // once input appears, the skipped video is picked up
        seedComment("ce", "empty", "Quán này ngon", 3);
        extraction.execute(opts(5));
        assertThat(jdbc.sql("SELECT status FROM video_extraction WHERE video_id = 'empty'").query(String.class)
                .single()).isEqualTo("ok");
    }

    @Test
    void theCostCapStopsTheRunBeforeItWouldBeExceeded() throws Exception {
        for (String id : List.of("v1", "v2", "v3")) {
            seedVideo(id, CHO_DEM_CAPTION, CHO_DEM_VTT);
        }
        fake.respondWith(exampleOutput(1), 2000, 1000); // real cost 0.004 per call
        BigDecimal worstCase = extraction.plan(opts(1)).get(0).estCostUsd();
        BigDecimal cap = worstCase.multiply(new BigDecimal("1.5"));

        var summary = extraction.execute(new Options("v1", "test-model", 5, List.of(), false, cap));

        assertThat(summary.stoppedByCostCap()).isTrue();
        assertThat(summary.ok()).isEqualTo(1);
        assertThat(fake.requests()).hasSize(1); // the second call would have exceeded the cap, so it was never made
        assertThat(summary.costUsd()).isLessThanOrEqualTo(cap);
        assertThat(count("SELECT count(*) FROM video_extraction")).isEqualTo(1);
    }

    @Test
    void realRunsRefuseToStartWithoutAModelOrPrices() {
        assertThatThrownBy(() -> extraction.execute(new Options("v1", null, 5, List.of(), false, null)))
                .hasMessageContaining("No model");
    }

    // ---- CLI ------------------------------------------------------------------------------------------------------

    @Test
    void dryRunPrintsExactInputsAndEstimatedCostWithoutAnyCall() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        seedVideo("empty", null, null);

        Result r = cli("extract", "--limit", "5", "--dry-run");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("DRY RUN: model=test-model prompt=v1 videos=1", "=== video v1 ===",
                "system prompt sha256: " + prompts.load("v1").hash(), "estimated input tokens:",
                "estimated cost (worst case): $", "--- user content (exactly what would be sent) ---",
                "<caption>\nKinh nghiệm đi chợ đêm Đà Lạt\n</caption>", "[00:03] 2 tô bún 400.000 riêu đó",
                "would also record 1 video(s) as skipped", "no network call was made");
        assertThat(fake.requests()).isEmpty();
        assertThat(WM.getAllServeEvents()).isEmpty();
        assertThat(count("SELECT count(*) FROM video_extraction")).as("a dry run writes nothing").isZero();
    }

    @Test
    void extractReportIsAReadableMarkdownReview() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(withExtraMention(exampleOutput(1),
                placeMention("Chợ Đêm Đà Lạt", "Chợ đêm Đà Lạt có bún riêu ngon nhất")), 1000, 500);
        assertThat(cli("extract", "--limit", "5").exit()).isZero();

        Result r = cli("extract-report", "--limit", "5", "--out", tmp.resolve("reports").toString());

        assertThat(r.exit()).isZero();
        Path file;
        try (Stream<Path> files = Files.list(tmp.resolve("reports"))) {
            file = files.findFirst().orElseThrow();
        }
        String md = Files.readString(file);
        assertThat(md).contains("# Extraction review", "## 1. Video v1", "**Caption:** Kinh nghiệm đi chợ đêm Đà Lạt",
                "### Accepted mentions (4)", "| kind | name_raw | conf | price | evidence | text |",
                "transcript [00:06]: \"chụp hình chỗ này coi chừng mất 50.000\"",
                "### Rejected mentions (1)", "quote_not_in_input", "Chợ Đêm Đà Lạt",
                "4 accepted mentions, 1 rejected mentions, 1000 input tokens, 500 output tokens");
    }

    @Test
    void evalAndCompareScorePlacesAgainstTheGoldFile() throws Exception {
        seedVideo("g1", "Cafe view đồi thông cực chill", CAFE_VTT);
        Path gold = tmp.resolve("gold.jsonl");
        Files.writeString(gold, "{\"video_id\": \"g1\", \"places\": [\"Suong Mu Coffee\", \"Thác Datanla\"]}\n");
        String perfectButIncomplete = exampleOutput(2);
        // a second place whose quote is real but whose name was made up: counts against precision
        String withWrongName = withExtraMention(perfectButIncomplete,
                placeMention("Quán Hoa Sữa", "chỗ này hợp đi cặp đôi hoặc đi chụp ảnh"));
        fake.respondWith(r -> new LlmResult("model-b".equals(r.model()) ? withWrongName : perfectButIncomplete,
                null, null, r.model()));

        Result cmp = cli("extract-compare", "--models", "model-a,model-b", "--gold", gold.toString(), "--limit", "5");

        assertThat(cmp.exit()).isZero();
        assertThat(cmp.out()).containsPattern("model-a\\s+100\\.0%\\s+50\\.0%")
                .containsPattern("model-b\\s+50\\.0%\\s+50\\.0%").contains("precision", "recall", "rejected%", "cost USD");

        Result eval = cli("extract-eval", "--gold", gold.toString(), "--model", "model-b");
        assertThat(eval.out()).contains("places predicted 2, gold 2, correct 1", "precision 50.0%, recall 50.0%");

        Path empty = tmp.resolve("empty.jsonl");
        Files.writeString(empty, "");
        assertThat(cli("extract-eval", "--gold", empty.toString(), "--model", "model-a").out())
                .contains("no labeled videos");
    }

    @Test
    void extractStatsCountsStatusesKindsRejectionsAndCost() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        seedVideo("empty", null, null);
        fake.respondWith(withExtraMention(exampleOutput(1), placeMention("X", "not in the input at all")), 1000, 500);
        Result run = cli("extract", "--limit", "5");
        assertThat(run.exit()).as(run.err()).isZero();

        String out = cli("extract-stats").out();

        assertThat(out).contains("extractions.ok: 1", "extractions.skipped: 1", "mentions.price: 2", "mentions.warning: 1",
                "mentions.tip: 1", "rejected.quote_not_in_input: 1", "skipped.no_transcript_no_comments: 1",
                "tokens.input: 1000", "tokens.output: 500", "cost_usd_total: 0.002000");
    }

    @Test
    void aConfigurationLevelGatewayErrorAbortsTheRunAndRecordsNothing() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        seedVideo("v2", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith(r -> {
            throw new LlmException("Genway answered HTTP 401: No API key found in request", true);
        });

        assertThatThrownBy(() -> extraction.execute(opts(5))).hasMessageContaining("aborted")
                .hasMessageContaining("401");

        assertThat(count("SELECT count(*) FROM video_extraction WHERE status = 'failed'")).isZero();
        assertThat(fake.requests()).hasSize(1); // the second video was not even tried
        fake.respondWith(exampleOutput(1)); // once fixed, a plain re-run picks both videos up (no --force needed)
        assertThat(extraction.execute(opts(5)).ok()).isEqualTo(2);
    }

    @Test
    void idempotencyKeysAreDeterministicPerAttemptAndFreshForForcedRuns() throws Exception {
        seedVideo("v1", CHO_DEM_CAPTION, CHO_DEM_VTT);
        fake.respondWith("not json", exampleOutput(1)); // first call invalid -> a repair call follows
        extraction.execute(opts(5));
        String first = fake.requests().get(0).idempotencyKey();
        String repair = fake.requests().get(1).idempotencyKey();
        assertThat(repair).as("the repair call must not be answered with the first call's response").isNotEqualTo(first);

        // same (video, prompt version, model, attempt) after the result is gone -> the same key
        jdbc.sql("DELETE FROM mention").update();
        jdbc.sql("DELETE FROM video_extraction").update();
        fake.reset();
        fake.respondWith(exampleOutput(1));
        extraction.execute(opts(5));
        assertThat(fake.requests().get(0).idempotencyKey()).isEqualTo(first);

        // --force must be a new request, because Genway answers a repeated key with the original request
        extraction.execute(new Options("v1", "test-model", 5, List.of(), true, null));
        assertThat(fake.requests().get(1).idempotencyKey()).isNotEqualTo(first);
    }
}
