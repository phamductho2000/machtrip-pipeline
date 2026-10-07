package vn.machtrip.pipeline.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import vn.machtrip.pipeline.extract.ExtractionInputBuilder.ExtractionInput;
import vn.machtrip.pipeline.extract.ExtractionInputBuilder.VideoData;
import vn.machtrip.pipeline.extract.MentionValidator.Rejected;
import vn.machtrip.pipeline.extract.PromptLoader.Prompt;
import vn.machtrip.pipeline.ingest.RawStore;

/** Stage 2: LLM extraction of mentions for videos that stage 1 collected. */
@Service
public class ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);

    /** @param videoIds restrict to these videos (empty = no restriction)
     *  @param maxCostUsd per-run cap; null = {@code extract.max-cost-usd-per-run} */
    public record Options(String promptVersion, String model, int limit, List<String> videoIds,
                          boolean force, BigDecimal maxCostUsd) {
    }

    public record Plan(String videoId, ExtractionInput input, int estInputTokens, BigDecimal estCostUsd) {
    }

    public record Summary(int selected, int ok, int failed, int skipped, boolean stoppedByCostCap, BigDecimal costUsd) {
    }

    private record Usage(int in, int out, boolean estimated, BigDecimal cost) {
    }

    private final ExtractProperties props;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final ExtractionInputBuilder builder;
    private final MentionValidator validator;
    private final VideoInputLoader loader;
    private final RawStore rawStore;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public ExtractionService(ExtractProperties props, LlmClient llm, PromptLoader prompts,
                             ExtractionInputBuilder builder, MentionValidator validator, VideoInputLoader loader,
                             RawStore rawStore, JdbcClient jdbc, TransactionTemplate tx, ObjectMapper mapper) {
        this.props = props;
        this.llm = llm;
        this.prompts = prompts;
        this.builder = builder;
        this.validator = validator;
        this.loader = loader;
        this.rawStore = rawStore;
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
    }

    /** Model name for a run: the explicit one, else {@code extract.model}; null if neither is set. */
    public String resolveModel(String explicit) {
        return explicit != null && !explicit.isBlank() ? explicit : props.model();
    }

    public Prompt prompt(String version) {
        return prompts.load(version);
    }

    /** Videos that would be processed, with the exact input and the estimated cost. Reads the DB only. */
    public List<Plan> plan(Options o) {
        Prompt p = prompts.load(o.promptVersion());
        List<String> ids = loader.candidates(o.promptVersion(), o.model(), o.force(), o.videoIds(),
                Math.min(o.limit(), props.maxVideosPerRun()));
        List<Plan> plans = new ArrayList<>();
        for (VideoData v : loader.load(ids)) {
            ExtractionInput in = builder.build(v);
            int tokens = tokens(p.system().length() + in.chars());
            plans.add(new Plan(v.videoId(), in, tokens, worstCase(tokens)));
        }
        return plans;
    }

    public int skippable(Options o) {
        return loader.countSkippable(o.promptVersion(), o.model(), o.videoIds());
    }

    public Summary execute(Options o) {
        if (o.model() == null || o.model().isBlank()) {
            throw new IllegalStateException("No model: set extract.model (TODO in application.yml) or pass --model");
        }
        if (!props.pricing().configured()) {
            throw new IllegalStateException("extract.pricing.input-per-token / output-per-token are not set (TODO); "
                    + "refusing to run because the cost cap cannot be enforced without prices");
        }
        Prompt prompt = prompts.load(o.promptVersion());
        int skipped = loader.recordSkipped(o.promptVersion(), o.model(), o.videoIds());
        List<Plan> plans = plan(o);
        CostGuard guard = new CostGuard(o.maxCostUsd() != null ? o.maxCostUsd() : props.maxCostUsdPerRun());
        // Genway answers a repeated Idempotency-Key with the ORIGINAL request, so a forced redo needs fresh keys
        String nonce = o.force() ? java.util.UUID.randomUUID().toString() : "";
        java.util.concurrent.atomic.AtomicReference<String> fatal = new java.util.concurrent.atomic.AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, props.concurrency()));
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (Plan plan : plans) {
                futures.add(pool.submit(() -> processOne(plan, o, prompt, guard, nonce, fatal)));
            }
            int ok = 0;
            int failed = 0;
            for (Future<String> f : futures) {
                String status = f.get();
                if ("ok".equals(status)) {
                    ok++;
                } else if ("failed".equals(status)) {
                    failed++;
                }
            }
            if (fatal.get() != null) {
                throw new IllegalStateException("Extraction aborted, nothing was recorded for the affected videos: "
                        + fatal.get());
            }
            return new Summary(plans.size(), ok, failed, skipped, guard.stopped(), guard.spent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("extraction worker crashed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    /** @return "ok", "failed", "stopped" (cost cap before the first call) or "aborted" (run-fatal error) */
    private String processOne(Plan plan, Options o, Prompt prompt, CostGuard guard, String nonce,
                              java.util.concurrent.atomic.AtomicReference<String> fatal) {
        if (fatal.get() != null) {
            return "aborted";
        }
        String user = plan.input().userContent();
        List<ObjectNode> raw = new ArrayList<>();
        int inTokens = 0;
        int outTokens = 0;
        boolean estimated = false;
        BigDecimal cost = BigDecimal.ZERO;
        JsonNode parsed = null;
        String error = null;

        for (int attempt = 1; attempt <= 2 && parsed == null; attempt++) {
            LlmRequest req = new LlmRequest(o.model(), prompt.system(), user,
                    props.structuredOutput() ? prompt.schemaText() : null, props.maxOutputTokens(),
                    idempotencyKey(plan.videoId(), o, attempt, nonce));
            BigDecimal reserved = worstCase(tokens(req.system().length() + req.user().length()));
            if (!guard.reserve(reserved)) {
                if (attempt == 1) {
                    return "stopped";
                }
                error = "cost cap reached before the repair attempt";
                break;
            }
            LlmResult result;
            try {
                result = llm.complete(req);
            } catch (LlmException e) {
                guard.settle(reserved, BigDecimal.ZERO);
                if (e.isRunFatal()) {
                    fatal.compareAndSet(null, e.getMessage());
                    return "aborted";
                }
                error = e.getMessage();
                break;
            }
            Usage u = usage(req, result);
            guard.settle(reserved, u.cost());
            inTokens += u.in();
            outTokens += u.out();
            estimated |= u.estimated();
            cost = cost.add(u.cost());
            raw.add(rawEntry(attempt, result));

            List<String> errors = new ArrayList<>();
            JsonNode root = parse(result.text(), errors);
            if (root != null) {
                schema(prompt).validate(root).stream().map(ValidationMessage::getMessage).limit(10).forEach(errors::add);
            }
            if (errors.isEmpty()) {
                parsed = root;
            } else {
                error = "invalid output after " + attempt + " attempt(s): " + String.join("; ", errors);
                user = plan.input().userContent() + "\n\n<previous_response>\n"
                        + result.text().substring(0, Math.min(result.text().length(), 20_000)).replace('<', '‹')
                        + "\n</previous_response>\n<validation_errors>\n" + String.join("\n", errors)
                        + "\n</validation_errors>\nYour previous response was invalid. Return ONLY the corrected JSON object.";
            }
        }

        String rawPath = raw.isEmpty() ? null : writeRaw(plan.videoId(), o, raw);
        if (parsed == null) {
            save(plan, o, "failed", error, inTokens, outTokens, estimated, cost, rawPath, List.of(), List.of());
            log.warn("Extraction failed for video {}", plan.videoId());
            return "failed";
        }
        MentionValidator.Result result = validator.validate(parsed, plan.input());
        save(plan, o, "ok", null, inTokens, outTokens, estimated, cost, rawPath, result.accepted(), result.rejected());
        log.info("Extracted video {}: {} mentions accepted, {} rejected", plan.videoId(), result.accepted().size(),
                result.rejected().size());
        return "ok";
    }

    /**
     * Deterministic per (video, prompt version, model, attempt), prefixed with the service name as the Genway doc
     * recommends. The attempt keeps the repair call from being answered with the first call's response.
     */
    static String idempotencyKey(String videoId, Options o, int attempt, String nonce) {
        return "machtrip-pipeline-extract-" + PromptLoader.sha256(
                videoId + "|" + o.promptVersion() + "|" + o.model() + "|" + attempt + "|" + nonce);
    }

    private JsonNode parse(String text, List<String> errors) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = mapper.readTree(t);
            if (node == null || node.isMissingNode()) {
                errors.add("response is empty, not valid JSON");
                return null;
            }
            return node;
        } catch (IOException e) {
            errors.add("response is not valid JSON");
            return null;
        }
    }

    private JsonSchema schema(Prompt p) {
        return schemas.computeIfAbsent(p.version(), v -> JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012).getSchema(p.schema()));
    }

    private Usage usage(LlmRequest req, LlmResult r) {
        boolean estimated = r.inputTokens() == null || r.outputTokens() == null;
        int in = r.inputTokens() != null ? r.inputTokens() : tokens(req.system().length() + req.user().length());
        int out = r.outputTokens() != null ? r.outputTokens() : tokens(r.text() == null ? 0 : r.text().length());
        BigDecimal cost = BigDecimal.valueOf(in).multiply(props.pricing().inputPerToken())
                .add(BigDecimal.valueOf(out).multiply(props.pricing().outputPerToken()));
        return new Usage(in, out, estimated, cost);
    }

    private int tokens(int chars) {
        return (int) Math.ceil(chars / (double) Math.max(1, props.charsPerToken()));
    }

    /** Worst case for one call: the given input tokens plus a full max_output_tokens answer. Null if prices are unset. */
    private BigDecimal worstCase(int inputTokens) {
        if (!props.pricing().configured()) {
            return null;
        }
        return BigDecimal.valueOf(inputTokens).multiply(props.pricing().inputPerToken())
                .add(BigDecimal.valueOf(props.maxOutputTokens()).multiply(props.pricing().outputPerToken()));
    }

    private ObjectNode rawEntry(int attempt, LlmResult r) {
        ObjectNode n = mapper.createObjectNode().put("attempt", attempt).put("model", r.model())
                .put("text", r.text());
        n.put("input_tokens", r.inputTokens()).put("output_tokens", r.outputTokens());
        return n;
    }

    private String writeRaw(String videoId, Options o, List<ObjectNode> entries) {
        Path dir = rawStore.root().resolve("extractions");
        Path file = dir.resolve(videoId + "." + o.promptVersion() + "." + o.model().replaceAll("[^A-Za-z0-9._-]", "_")
                + ".json.gz");
        ArrayNode arr = mapper.createArrayNode();
        entries.forEach(arr::add);
        try {
            Files.createDirectories(dir);
            try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
                mapper.writeValue(out, arr);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file.toString();
    }

    private void save(Plan plan, Options o, String status, String error, int in, int out, boolean estimated,
                      BigDecimal cost, String rawPath, List<ObjectNode> accepted, List<Rejected> rejected) {
        tx.executeWithoutResult(t -> {
            long id = jdbc.sql("""
                    INSERT INTO video_extraction (video_id, prompt_version, model, status, skip_reason, error,
                        input_tokens, output_tokens, tokens_estimated, cost_usd, truncated, raw_response_path)
                    VALUES (:video, :pv, :model, :status, NULL, :error, :in, :out, :est, :cost, :trunc, :raw)
                    ON CONFLICT (video_id, prompt_version, model) DO UPDATE SET
                        status = EXCLUDED.status, skip_reason = NULL, error = EXCLUDED.error,
                        input_tokens = EXCLUDED.input_tokens, output_tokens = EXCLUDED.output_tokens,
                        tokens_estimated = EXCLUDED.tokens_estimated, cost_usd = EXCLUDED.cost_usd,
                        truncated = EXCLUDED.truncated, raw_response_path = EXCLUDED.raw_response_path,
                        created_at = now()
                    RETURNING id""")
                    .param("video", plan.videoId()).param("pv", o.promptVersion()).param("model", o.model())
                    .param("status", status).param("error", error).param("in", in).param("out", out)
                    .param("est", estimated).param("cost", cost).param("trunc", plan.input().truncated())
                    .param("raw", rawPath).query(Long.class).single();
            jdbc.sql("DELETE FROM mention WHERE extraction_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM mention_rejected WHERE extraction_id = :id").param("id", id).update();
            for (ObjectNode m : accepted) {
                insertMention(id, plan.videoId(), m);
            }
            for (Rejected r : rejected) {
                jdbc.sql("INSERT INTO mention_rejected (extraction_id, reason, payload) "
                        + "VALUES (:id, :reason, CAST(:payload AS jsonb))")
                        .param("id", id).param("reason", r.reason()).param("payload", r.payload().toString()).update();
            }
        });
    }

    private void insertMention(long extractionId, String videoId, ObjectNode m) {
        jdbc.sql("""
                INSERT INTO mention (extraction_id, video_id, kind, name_raw, name_confidence, category, text,
                    price_text, price_vnd_min, price_vnd_max, sentiment, sponsored_signal, energy, tags, best_for,
                    evidence)
                VALUES (:eid, :vid, :kind, :name, :conf, :cat, :text, :ptext, :pmin, :pmax, :sent, :sponsored,
                    :energy, ARRAY(SELECT jsonb_array_elements_text(CAST(:tags AS jsonb))),
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:bestFor AS jsonb))), CAST(:evidence AS jsonb))""")
                .param("eid", extractionId).param("vid", videoId).param("kind", m.path("kind").asText())
                .param("name", textOrNull(m, "name_raw")).param("conf", m.path("name_confidence").asText())
                .param("cat", textOrNull(m, "category")).param("text", m.path("text").asText())
                .param("ptext", textOrNull(m, "price_text"))
                .param("pmin", m.path("price_vnd_min").isIntegralNumber() ? m.path("price_vnd_min").asLong() : null)
                .param("pmax", m.path("price_vnd_max").isIntegralNumber() ? m.path("price_vnd_max").asLong() : null)
                .param("sent", m.path("sentiment").asText()).param("sponsored", m.path("sponsored_signal").asBoolean())
                .param("energy", m.path("energy").isNumber() ? m.path("energy").asDouble() : null)
                .param("tags", m.path("tags").toString()).param("bestFor", m.path("best_for").toString())
                .param("evidence", m.path("evidence").toString()).update();
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.path(field).isTextual() ? n.path(field).asText() : null;
    }

}
