package vn.machtrip.pipeline.ingest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.config.PipelineProperties;

/** Raw provider JSON, gzip, one file per run (RAW_STORE_DIR/{apifyRunId}.json.gz, a JSON array). */
@Component
public class RawStore {

    private final Path root;
    private final ObjectMapper mapper;

    public RawStore(PipelineProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.rawStoreDir());
        this.mapper = mapper;
    }

    public Path root() {
        return root;
    }

    public Path audioDir() {
        return root.resolve("audio");
    }

    public Writer open(String runId) {
        try {
            Files.createDirectories(root);
            return new Writer(root.resolve(runId + ".json.gz"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes to a temp file; {@link #commit()} publishes it atomically, so a crashed job never leaves a partial file. */
    public final class Writer implements AutoCloseable {
        private final Path target;
        private final Path tmp;
        private final OutputStream out;
        private final JsonGenerator gen;
        private boolean committed;

        private Writer(Path target) throws IOException {
            this.target = target;
            this.tmp = target.resolveSibling(target.getFileName() + ".tmp");
            this.out = new GZIPOutputStream(Files.newOutputStream(tmp));
            this.gen = mapper.getFactory().createGenerator(out);
            gen.writeStartArray();
        }

        public void add(JsonNode item) {
            try {
                gen.writeTree(item);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public String commit() {
            try {
                gen.writeEndArray();
                gen.close();
                out.close();
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                committed = true;
                return target.toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Path the file will have once committed. */
        public String path() {
            return target.toString();
        }

        @Override
        public void close() {
            if (!committed) {
                try {
                    gen.close();
                    out.close();
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup of our own temp file
                }
            }
        }
    }
}
