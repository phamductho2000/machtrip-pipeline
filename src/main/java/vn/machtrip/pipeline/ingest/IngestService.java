package vn.machtrip.pipeline.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import vn.machtrip.pipeline.crawler.CrawlerProvider;
import vn.machtrip.pipeline.crawler.RunRef;
import vn.machtrip.pipeline.job.JobQueue.Job;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record Summary(int items, int videos) {
    }

    private final JdbcClient jdbc;
    private final CrawlerProvider provider;
    private final VideoIngestor videos;
    private final CommentIngestor comments;
    private final RawStore rawStore;
    private final ObjectMapper mapper;

    public IngestService(JdbcClient jdbc, CrawlerProvider provider, VideoIngestor videos, CommentIngestor comments,
                         RawStore rawStore, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.videos = videos;
        this.comments = comments;
        this.rawStore = rawStore;
        this.mapper = mapper;
    }

    /** Worker entry: ingest the dataset of a finished run. Safe to repeat (everything is an upsert). */
    public Summary runJob(Job job) {
        record Run(long id, String datasetId) {
        }
        Run run = jdbc.sql("SELECT id, dataset_id FROM crawl_run WHERE apify_run_id = :id")
                .param("id", job.apifyRunId())
                .query((rs, n) -> new Run(rs.getLong("id"), rs.getString("dataset_id"))).optional()
                .orElseThrow(() -> new IllegalStateException("crawl_run not found for job " + job.id()));
        if (run.datasetId() == null) {
            throw new IllegalStateException("crawl_run " + run.id() + " has no dataset id");
        }
        Iterator<JsonNode> items = provider.iterItems(RunRef.of(job.apifyRunId(), run.datasetId()));
        Summary s = process(run.id(), job.apifyRunId(), job.kind(), items, true, null);
        log.info("Job {} ({}) ingested {} items", job.id(), job.kind(), s.items());
        return s;
    }

    /** `ingest --file`: offline load of an Apify dataset export. No network, no downloads. */
    public Summary ingestFile(Path file, String kind) {
        String runId = "file-" + sha256(file).substring(0, 16);
        long crawlRunId = jdbc.sql("""
                INSERT INTO crawl_run (kind, provider, actor_id, input, apify_run_id, status, max_total_charge_usd,
                                       finished_at)
                VALUES (:kind, 'file', 'file', CAST(:input AS jsonb), :runId, 'SUCCEEDED', 0, now())
                ON CONFLICT (apify_run_id) DO UPDATE SET status = crawl_run.status
                RETURNING id""")
                .param("kind", kind)
                .param("input", mapper.createObjectNode().put("file", file.getFileName().toString()).toString())
                .param("runId", runId).query(Long.class).single();
        try (JsonParser parser = mapper.createParser(Files.newInputStream(file))) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException(file + " is not a JSON array (expected an Apify dataset export)");
            }
            Iterator<JsonNode> items = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    try {
                        return parser.nextToken() == JsonToken.START_OBJECT;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public JsonNode next() {
                    try {
                        return mapper.readTree(parser);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
            // raw_path points at the real export, so later stages (extraction) can read fields not kept in columns
            return process(crawlRunId, runId, kind, items, false, "file:" + file.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Summary process(long crawlRunId, String runName, String kind, Iterator<JsonNode> items, boolean live,
                           String offlineRawRef) {
        int count = 0;
        int ingested = 0;
        if ("search".equals(kind)) {
            if (live) {
                try (RawStore.Writer raw = rawStore.open(runName)) {
                    while (items.hasNext()) {
                        JsonNode item = items.next();
                        raw.add(item);
                        count++;
                        ingested += videos.ingest(crawlRunId, item, raw.path(), true) != null ? 1 : 0;
                    }
                    raw.commit();
                }
            } else {
                while (items.hasNext()) {
                    count++;
                    ingested += videos.ingest(crawlRunId, items.next(), offlineRawRef, false) != null ? 1 : 0;
                }
            }
        } else {
            // The comments run's own dataset holds video items, not comments: Apify puts the actual comments in a
            // separate dataset per commentsDatasetUrl (see config/actors/comments.json). Schema of THIS dataset is
            // still probed/reported below since it is not a fixed contract either.
            SchemaProbe probe = new SchemaProbe();
            Set<String> commentDatasetUrls = new LinkedHashSet<>();
            try (RawStore.Writer raw = live ? rawStore.open(runName) : null) {
                while (items.hasNext()) {
                    JsonNode item = items.next();
                    probe.add(item);
                    String commentsUrl = VideoIngestor.text(item, "commentsDatasetUrl");
                    if (commentsUrl != null) {
                        commentDatasetUrls.add(commentsUrl);
                    }
                    if (raw != null) {
                        raw.add(item);
                        jdbc.sql("""
                                INSERT INTO raw_item (crawl_run_id, item_id, raw_path) VALUES (:run, :id, :path)
                                ON CONFLICT (crawl_run_id, item_id) DO NOTHING""")
                                .param("run", crawlRunId).param("id", "row-" + count).param("path", raw.path())
                                .update();
                    }
                    count++;
                }
                if (raw != null) {
                    raw.commit();
                }
            }
            String report = probe.render();
            writeSchemaReport(runName, report);
            log.info("Comments output schema (field paths and types only):\n{}", report);

            // Only for a real worker run: ingestFile (offline/--file) never touches the network.
            if (live) {
                for (String url : commentDatasetUrls) {
                    Iterator<JsonNode> commentItems = provider.iterItemsFromUrl(url);
                    while (commentItems.hasNext()) {
                        if (comments.ingest(commentItems.next())) {
                            ingested++;
                        }
                    }
                }
                if (!commentDatasetUrls.isEmpty()) {
                    log.info("Fetched {} comment dataset(s) from commentsDatasetUrl, ingested {} comments",
                            commentDatasetUrls.size(), ingested);
                }
            }
        }
        jdbc.sql("UPDATE crawl_run SET item_count = :n WHERE id = :id").param("n", count).param("id", crawlRunId)
                .update();
        return new Summary(count, ingested);
    }

    private void writeSchemaReport(String runName, String report) {
        try {
            Path dir = rawStore.root();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(runName + ".schema.txt"), report);
        } catch (IOException e) {
            log.warn("Could not write schema report: {}", e.getMessage());
        }
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
