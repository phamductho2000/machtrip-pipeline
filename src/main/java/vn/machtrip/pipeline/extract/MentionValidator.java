package vn.machtrip.pipeline.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.extract.ExtractionInputBuilder.ExtractionInput;

/**
 * Deterministic checks on a schema-valid model answer. The model can hallucinate, so a mention is accepted only if its
 * evidence quote really occurs in the input text of its source; everything else is cleaned or dropped by code.
 */
@Component
public class MentionValidator {

    public record Rejected(String reason, JsonNode payload) {
    }

    public record Result(List<ObjectNode> accepted, List<Rejected> rejected) {
    }

    private final ExtractProperties props;
    private final ObjectMapper mapper;

    public MentionValidator(ExtractProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public Result validate(JsonNode root, ExtractionInput input) {
        Set<String> allowedTags = new LinkedHashSet<>(props.tags());
        List<ObjectNode> accepted = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (JsonNode node : root.path("mentions")) {
            ObjectNode m = ((ObjectNode) node).deepCopy();
            JsonNode ev = m.path("evidence");
            String reason = checkEvidence(ev, input);
            if (reason != null) {
                rejected.add(new Rejected(reason, node));
                continue;
            }
            m.set("tags", filterTags(m.path("tags"), allowedTags));
            if (!m.path("energy").isNumber() || !"place".equals(m.path("kind").asText())
                    || m.path("energy").asDouble() < 0 || m.path("energy").asDouble() > 1) {
                m.putNull("energy");
            }
            if (m.path("name_raw").isTextual() && m.path("name_raw").asText().isBlank()) {
                m.putNull("name_raw");
            }
            Long min = m.path("price_vnd_min").isIntegralNumber() ? m.path("price_vnd_min").asLong() : null;
            Long max = m.path("price_vnd_max").isIntegralNumber() ? m.path("price_vnd_max").asLong() : null;
            String priceText = m.path("price_text").isTextual() ? m.path("price_text").asText() : null;
            if (!PriceCheck.consistent(priceText, min, max)) {
                m.putNull("price_vnd_min");
                m.putNull("price_vnd_max");
            }
            accepted.add(m);
        }
        return new Result(accepted, rejected);
    }

    private String checkEvidence(JsonNode ev, ExtractionInput input) {
        String quote = ev.path("quote").asText("");
        String source = ev.path("source").asText("");
        String haystack = switch (source) {
            case "transcript" -> input.transcriptText();
            case "caption" -> input.captionText();
            case "location_tag" -> input.locationText();
            case "comment" -> input.commentTexts().get(ev.path("comment_id").asText(""));
            default -> null;
        };
        if ("comment".equals(source) && haystack == null) {
            return "unknown_comment_id";
        }
        if (haystack == null || norm(quote).isEmpty() || !norm(haystack).contains(norm(quote))) {
            return "quote_not_in_input";
        }
        return null;
    }

    private JsonNode filterTags(JsonNode tags, Set<String> allowed) {
        Set<String> kept = new LinkedHashSet<>();
        tags.forEach(t -> {
            String tag = t.asText("").trim().toLowerCase(Locale.ROOT);
            if (allowed.contains(tag)) {
                kept.add(tag);
            }
        });
        return mapper.valueToTree(kept);
    }

    /** Whitespace-collapsed, lower-cased, NFC. Diacritics are deliberately kept: they are part of the evidence. */
    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }
}
