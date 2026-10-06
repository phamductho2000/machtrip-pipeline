package vn.machtrip.pipeline.ingest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns one item of a comments-run's {@code commentsDatasetUrl} sub-dataset into a {@code pipeline.comment} row.
 * Idempotent per item (upsert on comment id). The video a comment belongs to is identified by the numeric id at
 * the end of its {@code submittedVideoUrl}/{@code videoWebUrl}, not by any field this project's own crawl assigned,
 * so a video the comments actor itself redirected to (or one we never crawled) is skipped rather than failing the
 * whole job.
 */
@Component
public class CommentIngestor {

    private static final Logger log = LoggerFactory.getLogger(CommentIngestor.class);
    private static final Pattern VIDEO_ID = Pattern.compile("/video/(\\d+)");

    private final JdbcClient jdbc;
    private final Hashing hashing;

    public CommentIngestor(JdbcClient jdbc, Hashing hashing) {
        this.jdbc = jdbc;
        this.hashing = hashing;
    }

    /** @return true if the comment was inserted/updated, false if it had no id or no matching video. */
    public boolean ingest(JsonNode item) {
        String id = text(item, "cid");
        if (id == null) {
            log.warn("Skipping comment item without cid");
            return false;
        }
        String videoId = videoId(text(item, "submittedVideoUrl"));
        if (videoId == null) {
            videoId = videoId(text(item, "videoWebUrl"));
        }
        if (videoId == null) {
            log.warn("Skipping comment {}: could not resolve a video id from its URL", id);
            return false;
        }
        String authorId = text(item, "uid");
        if (authorId == null) {
            authorId = text(item, "uniqueId");
        }
        try {
            jdbc.sql("""
                    INSERT INTO comment (id, video_id, author_hash, text, like_count, reply_count, created_at, parent_id)
                    VALUES (:id, :videoId, :author, :text, :likes, :replies, :created, :parentId)
                    ON CONFLICT (id) DO UPDATE SET
                        text = EXCLUDED.text, like_count = EXCLUDED.like_count, reply_count = EXCLUDED.reply_count""")
                    .param("id", id).param("videoId", videoId)
                    .param("author", authorId == null ? null : hashing.authorHash(authorId))
                    .param("text", text(item, "text")).param("likes", longOrNull(item.path("diggCount")))
                    .param("replies", longOrNull(item.path("replyCommentTotal")))
                    .param("created", timestamp(text(item, "createTimeISO"), item.path("createTime")))
                    .param("parentId", text(item, "repliesToId")).update();
            return true;
        } catch (DataIntegrityViolationException e) {
            // Most likely: videoId was parsed but that video is not (yet) in pipeline.video.
            log.warn("Skipping comment {} for video {}: {}", id, videoId, e.getMostSpecificCause().getMessage());
            return false;
        }
    }

    private static String videoId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String text(JsonNode node, String field) {
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

    /** Prefers the ISO string; falls back to the epoch-seconds field some actor builds send instead. */
    private static OffsetDateTime timestamp(String iso, JsonNode epochSeconds) {
        if (iso != null) {
            try {
                return OffsetDateTime.parse(iso);
            } catch (RuntimeException ignored) {
                // fall through to the epoch field
            }
        }
        if (epochSeconds.isNumber()) {
            return Instant.ofEpochSecond(epochSeconds.asLong()).atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
