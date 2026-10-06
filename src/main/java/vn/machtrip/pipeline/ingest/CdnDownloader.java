package vn.machtrip.pipeline.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Downloads short-lived TikTok CDN links (audio ~6h, subtitles ~48h). Any failure (403/404/expired/network) is logged
 * and swallowed: a dead link must never crash a run. Logs show the host only, never the signed URL.
 */
@Component
public class CdnDownloader {

    private static final Logger log = LoggerFactory.getLogger(CdnDownloader.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String REFERER = "https://www.tiktok.com/";

    private volatile RestClient client;

    private RestClient client() {
        RestClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(10))
                                    .followRedirects(HttpClient.Redirect.NORMAL).build());
                    factory.setReadTimeout(Duration.ofSeconds(60));
                    client = RestClient.builder().requestFactory(factory)
                            .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                            .defaultHeader(HttpHeaders.REFERER, REFERER).build();
                }
                c = client;
            }
        }
        return c;
    }

    public Optional<String> text(String url) {
        return fetch(url, (res, contentType) -> new String(res.readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Streams the file to {@code dir/baseName.<ext>} (ext from Content-Type) and returns the final path. */
    public Optional<Path> file(String url, Path dir, String baseName) {
        return fetch(url, (res, contentType) -> {
            Files.createDirectories(dir);
            Path target = dir.resolve(baseName + "." + extension(contentType));
            Path tmp = dir.resolve(baseName + ".part");
            Files.copy(res, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        });
    }

    private interface Reader<T> {
        T read(InputStream body, String contentType) throws IOException;
    }

    private <T> Optional<T> fetch(String url, Reader<T> reader) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed CDN url");
            return Optional.empty();
        }
        String host = String.valueOf(uri.getHost());
        try {
            return client().get().uri(uri).exchange((req, res) -> {
                if (!res.getStatusCode().is2xxSuccessful()) {
                    log.warn("CDN download skipped (host {}): HTTP {}", host, res.getStatusCode().value());
                    return Optional.<T>empty();
                }
                return Optional.of(reader.read(res.getBody(), res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)));
            }, true);
        } catch (RuntimeException e) {
            log.warn("CDN download failed (host {}): {}", host, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static String extension(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("mpeg") || ct.contains("mp3")) {
            return "mp3";
        }
        if (ct.contains("mp4") || ct.contains("m4a")) {
            return "m4a";
        }
        if (ct.contains("aac")) {
            return "aac";
        }
        if (ct.contains("wav")) {
            return "wav";
        }
        return "bin";
    }
}
