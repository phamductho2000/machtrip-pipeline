package vn.machtrip.pipeline.ingest;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Turns one search-run dataset item into rows: video upsert, subtitles, ASR queue, audio. Idempotent per item. */
@Component
public class VideoIngestor {

    private static final Logger log = LoggerFactory.getLogger(VideoIngestor.class);

    private record Subtitle(String language, String url, String source, String version) {
        /** Only Vietnamese ASR output produced by whisper_lid is trusted; big_caption and MT are not. */
        boolean trusted() {
            return "ASR".equals(source) && version != null && version.endsWith("whisper_lid");
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Hashing hashing;
    private final Filters filters;
    private final CdnDownloader cdn;
    private final RawStore rawStore;

    public VideoIngestor(JdbcClient jdbc, ObjectMapper mapper, Hashing hashing, Filters filters, CdnDownloader cdn,
                         RawStore rawStore) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hashing = hashing;
        this.filters = filters;
        this.cdn = cdn;
        this.rawStore = rawStore;
    }

    /**
     * @param download false for offline ingest of an export: no CDN calls at all (the links are long expired).
     * @return the tiktok id, or null when the item had no id and was skipped
     */
    public String ingest(long crawlRunId, JsonNode item, String rawPath, boolean download) {
        String id = text(item, "id");
        if (id == null) {
            log.warn("Skipping dataset item without id");
            return null;
        }
        upsertVideo(id, item);
        jdbc.sql("""
                INSERT INTO raw_item (crawl_run_id, item_id, raw_path) VALUES (:run, :id, :path)
                ON CONFLICT (crawl_run_id, item_id) DO NOTHING""")
                .param("run", crawlRunId).param("id", id).param("path", rawPath).update();

        boolean needsAsr = !handleSubtitles(id, item, download);
        if (needsAsr && download && filters.passes(item)) {
            downloadAudio(id, item);
        }
        return id;
    }

    private void upsertVideo(String id, JsonNode item) {
        JsonNode loc = item.path("locationMeta");
        List<String> hashtags = new ArrayList<>();
        item.path("hashtags").forEach(h -> {
            String name = text(h, "name");
            if (name != null) {
                hashtags.add(name);
            }
        });
        String authorId = text(item.path("authorMeta"), "id");
        if (authorId == null) {
            authorId = text(item.path("authorMeta"), "name");
        }
        jdbc.sql("""
                INSERT INTO video (tiktok_id, web_video_url, author_hash, created_at, language, duration_sec,
                    play_count, like_count, save_count, comment_count, share_count, is_ad, is_sponsored,
                    hashtags, location_name, location_address, location_city, location_country_code, location_id)
                VALUES (:id, :url, :author, :created, :lang, :duration,
                    :plays, :likes, :saves, :comments, :shares, :ad, :sponsored,
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:hashtags AS jsonb))),
                    :locName, :locAddress, :locCity, :locCountry, :locId)
                ON CONFLICT (tiktok_id) DO UPDATE SET
                    web_video_url = EXCLUDED.web_video_url, author_hash = EXCLUDED.author_hash,
                    created_at = EXCLUDED.created_at, language = EXCLUDED.language,
                    duration_sec = EXCLUDED.duration_sec, play_count = EXCLUDED.play_count,
                    like_count = EXCLUDED.like_count, save_count = EXCLUDED.save_count,
                    comment_count = EXCLUDED.comment_count, share_count = EXCLUDED.share_count,
                    is_ad = EXCLUDED.is_ad, is_sponsored = EXCLUDED.is_sponsored, hashtags = EXCLUDED.hashtags,
                    location_name = EXCLUDED.location_name, location_address = EXCLUDED.location_address,
                    location_city = EXCLUDED.location_city, location_country_code = EXCLUDED.location_country_code,
                    location_id = EXCLUDED.location_id, last_seen_at = now()""")
                .param("id", id)
                .param("url", text(item, "webVideoUrl"))
                .param("author", authorId == null ? null : hashing.authorHash(authorId))
                .param("created", timestamp(text(item, "createTimeISO")))
                .param("lang", text(item, "textLanguage"))
                .param("duration", intOrNull(item.path("videoMeta").path("duration")))
                .param("plays", longOrNull(item.path("playCount")))
                .param("likes", longOrNull(item.path("diggCount")))
                .param("saves", longOrNull(item.path("collectCount")))
                .param("comments", longOrNull(item.path("commentCount")))
                .param("shares", longOrNull(item.path("shareCount")))
                .param("ad", item.path("isAd").asBoolean(false))
                .param("sponsored", item.path("isSponsored").asBoolean(false))
                .param("hashtags", hashtags.isEmpty() ? "[]" : mapper.valueToTree(hashtags).toString())
                .param("locName", text(loc, "locationName"))
                .param("locAddress", text(loc, "address"))
                .param("locCity", text(loc, "city"))
                .param("locCountry", text(loc, "countryCode"))
                .param("locId", text(loc, "locationId"))
                .update();
    }

    /** @return true when the video has a trusted subtitle (the ASR queue is then not needed) */
    private boolean handleSubtitles(String id, JsonNode item, boolean download) {
        List<Subtitle> vietnamese = new ArrayList<>();
        item.path("videoMeta").path("subtitleLinks").forEach(s -> {
            String lang = text(s, "language");
            String source = text(s, "source");
            if (lang != null && lang.toLowerCase().startsWith("vi") && !"MT".equals(source)) {
                vietnamese.add(new Subtitle(lang, text(s, "downloadLink"), source == null ? "" : source,
                        text(s, "version") == null ? "" : text(s, "version")));
            }
        });

        boolean anyTrusted = false;
        boolean gotText = false;
        for (Subtitle s : vietnamese) {
            String vtt = null;
            if (s.trusted()) {
                anyTrusted = true;
                if (download && s.url() != null && !hasText(id, s)) {
                    vtt = cdn.text(s.url()).orElse(null);
                }
                gotText |= vtt != null || hasText(id, s);
            }
            jdbc.sql("""
                    INSERT INTO video_subtitle (video_id, language, source, version, trusted, vtt_text)
                    VALUES (:id, :lang, :source, :version, :trusted, :vtt)
                    ON CONFLICT (video_id, language, source, version) DO UPDATE SET
                        trusted = EXCLUDED.trusted,
                        vtt_text = COALESCE(EXCLUDED.vtt_text, video_subtitle.vtt_text),
                        fetched_at = CASE WHEN EXCLUDED.vtt_text IS NULL THEN video_subtitle.fetched_at ELSE now() END""")
                    .param("id", id).param("lang", s.language()).param("source", s.source())
                    .param("version", s.version()).param("trusted", s.trusted()).param("vtt", vtt).update();
        }

        // Online only: a trusted link that could not be downloaded (expired, 403) leaves no usable text -> ASR.
        boolean usable = anyTrusted && (!download || gotText);
        if (usable) {
            jdbc.sql("DELETE FROM video_asr_queue WHERE video_id = :id AND status = 'pending'")
                    .param("id", id).update();
        } else {
            String reason = !anyTrusted && !vietnamese.isEmpty() ? "untrusted_version" : "no_subtitle";
            jdbc.sql("""
                    INSERT INTO video_asr_queue (video_id, reason) VALUES (:id, :reason)
                    ON CONFLICT (video_id) DO NOTHING""").param("id", id).param("reason", reason).update();
        }
        return usable;
    }

    private boolean hasText(String id, Subtitle s) {
        return jdbc.sql("""
                SELECT count(*) FROM video_subtitle WHERE video_id = :id AND language = :lang AND source = :source
                AND version = :version AND vtt_text IS NOT NULL""")
                .param("id", id).param("lang", s.language()).param("source", s.source())
                .param("version", s.version()).query(Long.class).single() > 0;
    }

    private void downloadAudio(String id, JsonNode item) {
        JsonNode music = item.path("musicMeta");
        String playUrl = text(music, "playUrl");
        if (!music.path("musicOriginal").asBoolean(false) || playUrl == null) {
            return;
        }
        boolean have = jdbc.sql("SELECT count(*) FROM video_audio WHERE video_id = :id").param("id", id)
                .query(Long.class).single() > 0;
        if (have) {
            return;
        }
        Optional<Path> file = cdn.file(playUrl, rawStore.audioDir(), id);
        file.ifPresent(p -> jdbc.sql("""
                INSERT INTO video_audio (video_id, storage_path) VALUES (:id, :path)
                ON CONFLICT (video_id) DO NOTHING""").param("id", id).param("path", p.toString()).update());
    }

    // ---- JSON helpers --------------------------------------------------------------------------------------------

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private static Long longOrNull(JsonNode v) {
        return v.isNumber() ? v.asLong() : null;
    }

    private static Integer intOrNull(JsonNode v) {
        return v.isNumber() ? v.asInt() : null;
    }

    private static OffsetDateTime timestamp(String iso) {
        try {
            return iso == null ? null : OffsetDateTime.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
