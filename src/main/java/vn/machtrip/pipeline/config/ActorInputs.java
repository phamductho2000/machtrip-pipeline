package vn.machtrip.pipeline.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Actor input templates from config/actors/{search,comments}.json, loaded and validated at start.
 * String values of the form "${name}" are replaced by the typed value; top-level keys starting with "_" are notes
 * and are removed before the input is sent.
 */
@Component
public class ActorInputs {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)}");
    private static final Set<String> SEARCH_VARS = Set.of("hashtags", "limit");
    private static final Set<String> COMMENTS_VARS = Set.of("videoUrls", "maxPerVideo");

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ObjectNode search;
    private final ObjectNode comments;

    public ActorInputs(PipelineProperties props, ObjectMapper mapper) {
        Path dir = Path.of(props.configDir(), "actors");
        // the item limit MUST be enforced inside the actor input, so the limit placeholder is mandatory
        this.search = load(mapper, dir.resolve("search.json"), SEARCH_VARS, SEARCH_VARS);
        this.comments = load(mapper, dir.resolve("comments.json"), COMMENTS_VARS, COMMENTS_VARS);
    }

    public ObjectNode search(List<String> hashtags, int limit) {
        ArrayNode tags = F.arrayNode();
        hashtags.forEach(h -> tags.add(h.replaceFirst("^#", "")));
        return resolveTop(search, Map.of("hashtags", tags, "limit", F.numberNode(limit)));
    }

    public ObjectNode comments(List<String> videoUrls, int maxPerVideo) {
        ArrayNode urls = F.arrayNode();
        videoUrls.forEach(urls::add);
        return resolveTop(comments, Map.of("videoUrls", urls, "maxPerVideo", F.numberNode(maxPerVideo)));
    }

    /** Sample inputs for validate-input; values are throwaway. */
    public ObjectNode sample(String kind) {
        return switch (kind) {
            case "search" -> search(List.of("reviewdalat"), 1);
            case "comments" -> comments(List.of("https://www.tiktok.com/@example/video/1234567890123456789"), 1);
            default -> throw new IllegalArgumentException("kind must be search or comments");
        };
    }

    private static ObjectNode load(ObjectMapper mapper, Path file, Set<String> required, Set<String> allowed) {
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read actor input config " + file + ": " + e.getMessage(), e);
        }
        if (!(root instanceof ObjectNode obj)) {
            throw new IllegalStateException(file + " must contain a JSON object");
        }
        Set<String> found = new HashSet<>();
        obj.fields().forEachRemaining(e -> {
            if (!e.getKey().startsWith("_")) {
                collect(e.getValue(), found, file + ":" + e.getKey());
            }
        });
        if (!found.containsAll(required)) {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(found);
            throw new IllegalStateException(file + " is missing placeholders " + missing);
        }
        if (!allowed.containsAll(found)) {
            Set<String> unknown = new HashSet<>(found);
            unknown.removeAll(allowed);
            throw new IllegalStateException(file + " uses unknown placeholders " + unknown);
        }
        return obj;
    }

    private static void collect(JsonNode node, Set<String> found, String where) {
        if (node.isTextual()) {
            if (node.asText().startsWith("TODO")) {
                throw new IllegalStateException(where + " is still a TODO; fill it in or remove the field");
            }
            Matcher m = PLACEHOLDER.matcher(node.asText());
            while (m.find()) {
                found.add(m.group(1));
            }
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, found, where));
        }
    }

    private static ObjectNode resolveTop(ObjectNode template, Map<String, JsonNode> vars) {
        ObjectNode out = F.objectNode();
        template.fields().forEachRemaining(e -> {
            if (!e.getKey().startsWith("_")) {
                out.set(e.getKey(), resolve(e.getValue(), vars));
            }
        });
        return out;
    }

    private static JsonNode resolve(JsonNode node, Map<String, JsonNode> vars) {
        if (node.isTextual()) {
            Matcher whole = Pattern.compile("^\\$\\{(\\w+)}$").matcher(node.asText());
            if (whole.matches()) {
                return vars.get(whole.group(1)).deepCopy();
            }
            Matcher m = PLACEHOLDER.matcher(node.asText());
            return F.textNode(m.replaceAll(r -> Matcher.quoteReplacement(vars.get(r.group(1)).asText())));
        }
        if (node.isObject()) {
            ObjectNode copy = F.objectNode();
            node.fields().forEachRemaining(e -> copy.set(e.getKey(), resolve(e.getValue(), vars)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = F.arrayNode();
            node.forEach(child -> copy.add(resolve(child, vars)));
            return copy;
        }
        return node;
    }
}
