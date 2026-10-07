package vn.machtrip.pipeline.extract;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.machtrip.pipeline.extract.ExtractionInputBuilder.ExtractionInput;
import vn.machtrip.pipeline.extract.ExtractionInputBuilder.VideoData;

/** Read-only views over stage 2 results: stats, the human review report, and gold-set evaluation. */
@Service
public class ExtractionReporting {

    /** Precision/recall are null when their denominator is 0 (nothing predicted / nothing in the gold set). */
    public record Metrics(String model, int videos, int predicted, int gold, int truePositives, Double precision,
                          Double recall, int accepted, int rejected, long inputTokens, long outputTokens,
                          BigDecimal costUsd) {
        public Double rejectedRate() {
            return accepted + rejected == 0 ? null : rejected / (double) (accepted + rejected);
        }
    }

    private record Row(long id, String videoId, String model, String promptVersion, String status, Integer in,
                       Integer out, BigDecimal cost, boolean truncated) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final VideoInputLoader loader;
    private final ExtractionInputBuilder builder;

    public ExtractionReporting(JdbcClient jdbc, ObjectMapper mapper, VideoInputLoader loader,
                               ExtractionInputBuilder builder) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.loader = loader;
        this.builder = builder;
    }

    // ---- extract-stats ------------------------------------------------------------------------------------------

    public Map<String, String> stats() {
        Map<String, String> m = new LinkedHashMap<>();
        pairs("SELECT status, count(*) FROM video_extraction GROUP BY status ORDER BY status", "extractions.", m);
        pairs("SELECT kind, count(*) FROM mention GROUP BY kind ORDER BY kind", "mentions.", m);
        m.put("mentions.total", String.valueOf(jdbc.sql("SELECT count(*) FROM mention").query(Long.class).single()));
        pairs("SELECT reason, count(*) FROM mention_rejected GROUP BY reason ORDER BY reason", "rejected.", m);
        pairs("SELECT skip_reason, count(*) FROM video_extraction WHERE status = 'skipped' GROUP BY skip_reason "
                + "ORDER BY 1", "skipped.", m);
        m.put("tokens.input", String.valueOf(jdbc.sql("SELECT coalesce(sum(input_tokens), 0) FROM video_extraction")
                .query(Long.class).single()));
        m.put("tokens.output", String.valueOf(jdbc.sql("SELECT coalesce(sum(output_tokens), 0) FROM video_extraction")
                .query(Long.class).single()));
        m.put("cost_usd_total", jdbc.sql("SELECT coalesce(sum(cost_usd), 0)::text FROM video_extraction")
                .query(String.class).single());
        return m;
    }

    private void pairs(String sql, String prefix, Map<String, String> out) {
        jdbc.sql(sql).query((rs, n) -> out.put(prefix + rs.getString(1), String.valueOf(rs.getLong(2)))).list();
    }

    // ---- extract-report -----------------------------------------------------------------------------------------

    public Path writeReport(int limit, String model, Path outDir) {
        List<Row> rows = jdbc.sql("""
                SELECT id, video_id, model, prompt_version, status, input_tokens, output_tokens, cost_usd, truncated
                FROM video_extraction WHERE status = 'ok' AND (:model = '' OR model = :model)
                ORDER BY created_at DESC, id DESC LIMIT :limit""")
                .param("model", model == null ? "" : model).param("limit", limit)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Integer) rs.getObject(6), (Integer) rs.getObject(7), rs.getBigDecimal(8),
                        rs.getBoolean(9))).list();
        Map<String, VideoData> data = new HashMap<>();
        loader.load(rows.stream().map(Row::videoId).distinct().toList()).forEach(v -> data.put(v.videoId(), v));

        StringBuilder md = new StringBuilder();
        int accepted = 0;
        int rejected = 0;
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        StringBuilder body = new StringBuilder();
        int n = 0;
        for (Row r : rows) {
            n++;
            body.append("\n## ").append(n).append(". Video ").append(r.videoId()).append("\n\n");
            body.append("model `").append(r.model()).append("`, prompt `").append(r.promptVersion()).append("`")
                    .append(r.truncated() ? ", **transcript truncated**" : "").append("\n\n");
            VideoData v = data.get(r.videoId());
            if (v != null) {
                excerpts(body, v);
            }
            List<String[]> acc = new ArrayList<>();
            jdbc.sql("""
                    SELECT kind, name_raw, name_confidence, price_text, price_vnd_min, price_vnd_max, evidence::text,
                           text, sponsored_signal FROM mention WHERE extraction_id = :id ORDER BY id""")
                    .param("id", r.id()).query((rs, i) -> acc.add(new String[]{rs.getString(1), rs.getString(2),
                            rs.getString(3), price(rs.getString(4), (Long) rs.getObject(5), (Long) rs.getObject(6)),
                            evidence(rs.getString(7)), rs.getString(8), String.valueOf(rs.getBoolean(9))})).list();
            body.append("### Accepted mentions (").append(acc.size()).append(")\n\n");
            if (acc.isEmpty()) {
                body.append("_none_\n\n");
            } else {
                body.append("| kind | name_raw | conf | price | evidence | text |\n|---|---|---|---|---|---|\n");
                acc.forEach(a -> body.append("| ").append(cell(a[0])).append(a[6].equals("true") ? " (sponsored)" : "")
                        .append(" | ").append(cell(a[1])).append(" | ").append(cell(a[2])).append(" | ")
                        .append(cell(a[3])).append(" | ").append(cell(a[4])).append(" | ").append(cell(a[5]))
                        .append(" |\n"));
                body.append('\n');
            }
            List<String[]> rej = new ArrayList<>();
            jdbc.sql("SELECT reason, payload::text FROM mention_rejected WHERE extraction_id = :id ORDER BY id")
                    .param("id", r.id()).query((rs, i) -> rej.add(new String[]{rs.getString(1), rs.getString(2)}))
                    .list();
            body.append("### Rejected mentions (").append(rej.size()).append(")\n\n");
            if (rej.isEmpty()) {
                body.append("_none_\n\n");
            } else {
                body.append("| reason | kind | name_raw | claimed evidence |\n|---|---|---|---|\n");
                for (String[] x : rej) {
                    JsonNode p = json(x[1]);
                    body.append("| ").append(cell(x[0])).append(" | ").append(cell(p.path("kind").asText("")))
                            .append(" | ").append(cell(p.path("name_raw").asText(""))).append(" | ")
                            .append(cell(evidence(p.path("evidence").toString()))).append(" |\n");
                }
                body.append('\n');
            }
            accepted += acc.size();
            rejected += rej.size();
            in += r.in() == null ? 0 : r.in();
            out += r.out() == null ? 0 : r.out();
            cost = cost.add(r.cost() == null ? BigDecimal.ZERO : r.cost());
        }
        md.append("# Extraction review\n\nGenerated ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append(model == null ? " (all models)" : " for model `" + model + "`").append(".\n\n")
                .append("**Totals**: ").append(rows.size()).append(" videos, ").append(accepted)
                .append(" accepted mentions, ").append(rejected).append(" rejected mentions, ").append(in)
                .append(" input tokens, ").append(out).append(" output tokens, cost $").append(cost.toPlainString())
                .append("\n\nEvery accepted mention carries a quote that was verified to exist in the input. Rejected "
                        + "mentions are what the model claimed but the code could not verify.\n")
                .append(body);
        try {
            Files.createDirectories(outDir);
            Path file = outDir.resolve("extract_report_"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
            Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void excerpts(StringBuilder b, VideoData v) {
        ExtractionInput in = builder.build(v);
        b.append("**Caption:** ").append(in.captionText().isEmpty() ? "_not available_" : in.captionText()).append("\n\n");
        if (!in.locationText().isEmpty()) {
            b.append("**Location tag:** ").append(in.locationText()).append("\n\n");
        }
        if (!in.transcriptText().isEmpty()) {
            String t = in.transcriptText();
            b.append("**Transcript (start):**\n\n> ").append(t.length() > 600 ? t.substring(0, 600) + " ..." : t)
                    .append("\n\n");
        }
        if (!in.commentTexts().isEmpty()) {
            b.append("**Top comments:**\n\n");
            in.commentTexts().entrySet().stream().limit(3).forEach(e -> b.append("- `").append(e.getKey()).append("` ")
                    .append(e.getValue().length() > 200 ? e.getValue().substring(0, 200) + " ..." : e.getValue())
                    .append('\n'));
            b.append('\n');
        }
    }

    private static String price(String text, Long min, Long max) {
        if (text == null) {
            return "";
        }
        return min == null ? text : text + " (" + min + (max != null && !max.equals(min) ? "-" + max : "") + " VND)";
    }

    private String evidence(String json) {
        JsonNode e = json(json);
        String where = switch (e.path("source").asText("")) {
            case "transcript" -> "transcript" + (e.path("t_start_sec").isNumber()
                    ? String.format(" [%02d:%02d]", (int) e.path("t_start_sec").asDouble() / 60,
                    (int) e.path("t_start_sec").asDouble() % 60) : "");
            case "comment" -> "comment " + e.path("comment_id").asText("?");
            default -> e.path("source").asText("?");
        };
        return where + ": \"" + e.path("quote").asText("") + "\"";
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    // ---- extract-eval / extract-compare -------------------------------------------------------------------------

    /** Gold file: JSONL, {"video_id": "...", "places": ["..."]}; blank lines ignored. */
    public Map<String, Set<String>> readGold(Path file) {
        Map<String, Set<String>> gold = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                Set<String> places = new LinkedHashSet<>();
                n.path("places").forEach(p -> places.add(normName(p.asText())));
                gold.put(n.path("video_id").asText(), places);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return gold;
    }

    /** Scores the model's ok extractions of the gold videos. {@code videoIds} restricts the videos (null = all gold). */
    public Metrics evaluate(Map<String, Set<String>> gold, String model, String promptVersion,
                            Set<String> videoIds) {
        int videos = 0;
        int predicted = 0;
        int goldCount = 0;
        int tp = 0;
        int accepted = 0;
        int rejected = 0;
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<String, Set<String>> g : gold.entrySet()) {
            if (videoIds != null && !videoIds.contains(g.getKey())) {
                continue;
            }
            var ex = jdbc.sql("""
                    SELECT id, coalesce(input_tokens, 0), coalesce(output_tokens, 0), coalesce(cost_usd, 0)
                    FROM video_extraction WHERE video_id = :v AND model = :m AND prompt_version = :pv AND status = 'ok'""")
                    .param("v", g.getKey()).param("m", model).param("pv", promptVersion)
                    .query((rs, n) -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4)})
                    .optional();
            if (ex.isEmpty()) {
                continue;
            }
            Object[] e = ex.get();
            Set<String> pred = new LinkedHashSet<>();
            jdbc.sql("SELECT name_raw FROM mention WHERE extraction_id = :id AND kind = 'place' AND name_raw IS NOT NULL")
                    .param("id", e[0]).query((rs, n) -> pred.add(normName(rs.getString(1)))).list();
            videos++;
            predicted += pred.size();
            goldCount += g.getValue().size();
            tp += (int) pred.stream().filter(g.getValue()::contains).count();
            accepted += jdbc.sql("SELECT count(*) FROM mention WHERE extraction_id = :id").param("id", e[0])
                    .query(Integer.class).single();
            rejected += jdbc.sql("SELECT count(*) FROM mention_rejected WHERE extraction_id = :id").param("id", e[0])
                    .query(Integer.class).single();
            in += (Long) e[1];
            out += (Long) e[2];
            cost = cost.add((BigDecimal) e[3]);
        }
        return new Metrics(model, videos, predicted, goldCount, tp, predicted == 0 ? null : tp / (double) predicted,
                goldCount == 0 ? null : tp / (double) goldCount, accepted, rejected, in, out, cost);
    }

    /** Lowercase, diacritics stripped (comparison only), punctuation collapsed. */
    static String normName(String s) {
        String t = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
        return t.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
