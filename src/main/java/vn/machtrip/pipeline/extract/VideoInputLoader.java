package vn.machtrip.pipeline.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.extract.ExtractionInputBuilder.Comment;
import vn.machtrip.pipeline.extract.ExtractionInputBuilder.VideoData;

/**
 * Reads what stage 1 collected for a video: columns of pipeline.video, the trusted subtitle, comments, and the caption.
 * The caption is not a column of pipeline.video; it is read from the raw provider JSON that stage 1 keeps
 * (pipeline.raw_item.raw_path), one pass per raw file.
 */
@Component
public class VideoInputLoader {

    private static final Logger log = LoggerFactory.getLogger(VideoInputLoader.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public VideoInputLoader(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Same order as {@code ids}; ids that do not exist are omitted. */
    public List<VideoData> load(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, String> vtt = new HashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (video_id) video_id, vtt_text FROM video_subtitle
                WHERE trusted AND vtt_text IS NOT NULL AND video_id IN (:ids)
                ORDER BY video_id, fetched_at DESC""").param("ids", ids)
                .query((rs, n) -> vtt.put(rs.getString(1), rs.getString(2))).list();

        Map<String, List<Comment>> comments = new HashMap<>();
        jdbc.sql("""
                SELECT id, video_id, text, coalesce(like_count, 0) FROM comment
                WHERE video_id IN (:ids) AND btrim(coalesce(text, '')) <> ''""").param("ids", ids)
                .query((rs, n) -> comments.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                        .add(new Comment(rs.getString(1), rs.getLong(4), rs.getString(3)))).list();

        Map<String, String> captions = captions(ids);

        Map<String, VideoData> byId = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT tiktok_id, hashtags, location_name, location_address, location_city FROM video
                WHERE tiktok_id IN (:ids)""").param("ids", ids).query((rs, n) -> {
            String id = rs.getString(1);
            String[] tags = (String[]) rs.getArray(2).getArray();
            byId.put(id, new VideoData(id, captions.get(id), List.of(tags),
                    locationTag(rs.getString(3), rs.getString(4), rs.getString(5)), vtt.get(id),
                    comments.getOrDefault(id, List.of())));
            return id;
        }).list();
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private static String locationTag(String name, String address, String city) {
        List<String> parts = new ArrayList<>();
        if (name != null) {
            parts.add("name: " + name);
        }
        if (address != null) {
            parts.add("address: " + address);
        }
        if (city != null) {
            parts.add("city: " + city);
        }
        return String.join(" | ", parts);
    }

    private Map<String, String> captions(List<String> ids) {
        Map<String, List<String>> idsByPath = new HashMap<>();
        jdbc.sql("""
                SELECT DISTINCT ON (item_id) item_id, raw_path FROM raw_item
                WHERE item_id IN (:ids) ORDER BY item_id, fetched_at DESC""").param("ids", ids)
                .query((rs, n) -> idsByPath.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(rs.getString(1)))
                .list();
        Map<String, String> out = new HashMap<>();
        idsByPath.forEach((path, wanted) -> {
            try {
                scan(path, Set.copyOf(wanted), out);
            } catch (IOException | RuntimeException e) {
                log.warn("Cannot read captions from raw file ({}); continuing without them", e.getClass().getSimpleName());
            }
        });
        return out;
    }

    /** One streaming pass over a raw file (gzip array of items, or a plain JSON export recorded as "file:<path>"). */
    private void scan(String rawPath, Set<String> wanted, Map<String, String> out) throws IOException {
        boolean plain = rawPath.startsWith("file:");
        Path file = Path.of(plain ? rawPath.substring("file:".length()) : rawPath);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = plain ? raw : new GZIPInputStream(raw);
             JsonParser parser = mapper.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                return;
            }
            int found = 0;
            while (parser.nextToken() == JsonToken.START_OBJECT && found < wanted.size()) {
                JsonNode item = mapper.readTree(parser);
                String id = item.path("id").asText(null);
                if (id != null && wanted.contains(id) && item.hasNonNull("text")) {
                    out.put(id, item.get("text").asText());
                    found++;
                }
            }
        }
    }

    /** Candidates for (promptVersion, model): not yet ok/failed (or all when forced), with usable input, not in the ASR queue. */
    public List<String> candidates(String promptVersion, String model, boolean force, List<String> only, int limit) {
        return jdbc.sql("""
                SELECT v.tiktok_id FROM video v
                WHERE (:all OR v.tiktok_id IN (:only))
                  AND NOT EXISTS (SELECT 1 FROM video_asr_queue q WHERE q.video_id = v.tiktok_id)
                  AND (""" + HAS_INPUT + """
                  )
                  AND (:force OR NOT EXISTS (SELECT 1 FROM video_extraction e WHERE e.video_id = v.tiktok_id
                         AND e.prompt_version = :pv AND e.model = :model AND e.status IN ('ok', 'failed')))
                ORDER BY v.play_count DESC NULLS LAST, v.tiktok_id
                LIMIT :limit""")
                .param("all", only.isEmpty()).param("only", only.isEmpty() ? List.of("") : only).param("force", force).param("pv", promptVersion)
                .param("model", model).param("limit", limit).query(String.class).list();
    }

    /** Records a skipped extraction (with the reason) for every video that has nothing to extract from. */
    public int recordSkipped(String promptVersion, String model, List<String> only) {
        return jdbc.sql("""
                INSERT INTO video_extraction (video_id, prompt_version, model, status, skip_reason)
                SELECT v.tiktok_id, :pv, :model, 'skipped', 'no_transcript_no_comments' FROM video v
                WHERE (:all OR v.tiktok_id IN (:only))
                  AND NOT EXISTS (SELECT 1 FROM video_asr_queue q WHERE q.video_id = v.tiktok_id)
                  AND NOT (""" + HAS_INPUT + """
                  )
                ON CONFLICT (video_id, prompt_version, model) DO NOTHING""")
                .param("pv", promptVersion).param("model", model).param("all", only.isEmpty()).param("only", only.isEmpty() ? List.of("") : only)
                .update();
    }

    /** Number of videos recordSkipped would record, for dry runs. */
    public int countSkippable(String promptVersion, String model, List<String> only) {
        return jdbc.sql("""
                SELECT count(*) FROM video v
                WHERE (:all OR v.tiktok_id IN (:only))
                  AND NOT EXISTS (SELECT 1 FROM video_asr_queue q WHERE q.video_id = v.tiktok_id)
                  AND NOT (""" + HAS_INPUT + """
                  )
                  AND NOT EXISTS (SELECT 1 FROM video_extraction e WHERE e.video_id = v.tiktok_id
                         AND e.prompt_version = :pv AND e.model = :model)""")
                .param("pv", promptVersion).param("model", model).param("all", only.isEmpty()).param("only", only.isEmpty() ? List.of("") : only)
                .query(Integer.class).single();
    }

    private static final String HAS_INPUT = """
            EXISTS (SELECT 1 FROM video_subtitle s WHERE s.video_id = v.tiktok_id AND s.trusted AND s.vtt_text IS NOT NULL)
            OR EXISTS (SELECT 1 FROM comment c WHERE c.video_id = v.tiktok_id AND btrim(coalesce(c.text, '')) <> '')""";
}
