package vn.machtrip.pipeline.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StatsService {

    private final JdbcClient jdbc;

    public StatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ordered name -> value pairs, printed one per line by the `stats` command. */
    public Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("crawl_runs", count("SELECT count(*) FROM crawl_run"));
        m.put("crawl_cost_usd", jdbc.sql("SELECT coalesce(sum(cost_usd), 0)::text FROM crawl_run")
                .query(String.class).single());
        m.put("videos", count("SELECT count(*) FROM video"));
        m.put("videos_with_location_name", count("SELECT count(*) FROM video WHERE location_name IS NOT NULL"));
        m.put("videos_with_trusted_subtitle",
                count("SELECT count(DISTINCT video_id) FROM video_subtitle WHERE trusted"));
        m.put("videos_with_trusted_subtitle_text",
                count("SELECT count(DISTINCT video_id) FROM video_subtitle WHERE trusted AND vtt_text IS NOT NULL"));
        m.put("videos_with_untrusted_subtitle", count("""
                SELECT count(DISTINCT video_id) FROM video_subtitle s WHERE NOT trusted
                AND NOT EXISTS (SELECT 1 FROM video_subtitle t WHERE t.video_id = s.video_id AND t.trusted)"""));
        m.put("asr_queue", count("SELECT count(*) FROM video_asr_queue"));
        jdbc.sql("SELECT reason, count(*) FROM video_asr_queue GROUP BY reason ORDER BY reason")
                .query((rs, n) -> m.put("asr_queue." + rs.getString(1), String.valueOf(rs.getLong(2)))).list();
        m.put("audio_files", count("SELECT count(*) FROM video_audio"));
        m.put("comments", count("SELECT count(*) FROM comment"));
        for (String status : new String[]{"pending", "running", "done", "failed"}) {
            m.put("jobs." + status, count("SELECT count(*) FROM job WHERE status = '" + status + "'"));
        }
        return m;
    }

    private String count(String sql) {
        return String.valueOf(jdbc.sql(sql).query(Long.class).single());
    }
}
