package vn.machtrip.pipeline.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Loads a versioned prompt (prompts/extract_vN.md) and its output schema, injecting the closed tag list. */
@Component
public class PromptLoader {

    public record Prompt(String version, String system, String schemaText, JsonNode schema, String hash) {
    }

    private static final Pattern VERSION = Pattern.compile("[a-z0-9_]{1,20}");

    private final ObjectMapper mapper;
    private final ExtractProperties props;

    public PromptLoader(ObjectMapper mapper, ExtractProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public Prompt load(String version) {
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("invalid prompt version: " + version);
        }
        if (props.tags().isEmpty()) {
            throw new IllegalStateException("No tags configured: config/tags.yml must define extract.tags");
        }
        String system = read("prompts/extract_" + version + ".md").replace("{{TAGS}}", String.join(", ", props.tags()));
        String schemaText = read("prompts/extract_" + version + ".schema.json");
        try {
            return new Prompt(version, system, schemaText, mapper.readTree(schemaText), sha256(system));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Prompt resource not found: " + path, e);
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
