package vn.machtrip.pipeline.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Builds the user message for one video. All text that comes from the video (caption, transcript, comments, location)
 * is untrusted: angle brackets are replaced so it can never close or open one of our delimiter tags. The texts kept in
 * {@link ExtractionInput} are exactly what the model sees, because evidence quotes are verified against them.
 */
@Component
public class ExtractionInputBuilder {

    public record Comment(String id, long likes, String text) {
    }

    public record VideoData(String videoId, String caption, List<String> hashtags, String locationTag, String vtt,
                            List<Comment> comments) {
    }

    /**
     * @param userContent the exact message sent to the model
     * @param truncated   true if the transcript was cut at the character cap
     * @param commentTexts included comments by id, as shown to the model
     */
    public record ExtractionInput(String userContent, boolean truncated, String captionText, String locationText,
                                  String transcriptText, Map<String, String> commentTexts) {
        public int chars() {
            return userContent.length();
        }
    }

    private final ExtractProperties props;

    public ExtractionInputBuilder(ExtractProperties props) {
        this.props = props;
    }

    public ExtractionInput build(VideoData v) {
        StringBuilder out = new StringBuilder();
        String caption = clean(v.caption());
        if (!caption.isEmpty()) {
            out.append("<caption>\n").append(caption).append("\n</caption>\n");
        }
        List<String> tags = v.hashtags() == null ? List.of() : v.hashtags().stream().map(ExtractionInputBuilder::clean)
                .filter(h -> !h.isEmpty()).map(h -> "#" + h.replaceFirst("^#", "")).toList();
        if (!tags.isEmpty()) {
            out.append("<hashtags>").append(String.join(" ", tags)).append("</hashtags>\n");
        }
        String location = clean(v.locationTag());
        if (!location.isEmpty()) {
            out.append("<location_tag>").append(location).append("</location_tag>\n");
        }

        boolean truncated = false;
        StringBuilder transcriptText = new StringBuilder();
        List<Vtt.Cue> cues = Vtt.parse(v.vtt());
        if (!cues.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            for (Vtt.Cue cue : cues) {
                String text = clean(cue.text());
                if (text.isEmpty()) {
                    continue;
                }
                String line = stamp(cue.startSec()) + " " + text + "\n";
                if (lines.length() + line.length() > props.maxTranscriptChars()) {
                    truncated = true;
                    break;
                }
                lines.append(line);
                transcriptText.append(transcriptText.isEmpty() ? "" : " ").append(text);
            }
            if (!lines.isEmpty()) {
                out.append("<transcript>\n").append(lines).append("</transcript>\n");
            }
        }

        Map<String, String> commentTexts = new LinkedHashMap<>();
        List<Comment> top = new ArrayList<>(v.comments() == null ? List.of() : v.comments());
        top.sort(Comparator.comparingLong(Comment::likes).reversed().thenComparing(Comment::id));
        for (Comment c : top) {
            if (commentTexts.size() >= props.maxComments()) {
                break;
            }
            String text = clean(c.text());
            if (text.length() > props.maxCommentChars()) {
                text = text.substring(0, props.maxCommentChars()).trim();
            }
            String id = c.id().replaceAll("[^A-Za-z0-9_-]", "");
            if (text.isEmpty() || id.isEmpty() || commentTexts.containsKey(id)) {
                continue;
            }
            commentTexts.put(id, text);
            out.append("<comment id=\"").append(id).append("\" likes=\"").append(Math.max(0, c.likes())).append("\">")
                    .append(text).append("</comment>\n");
        }
        return new ExtractionInput(out.toString().stripTrailing(), truncated, caption, location,
                transcriptText.toString(), commentTexts);
    }

    /** Neutralizes delimiter syntax and control characters; collapses whitespace to single spaces. */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s, Normalizer.Form.NFC)
                .replace('<', '‹').replace('>', '›')
                .replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stamp(double sec) {
        int total = (int) sec;
        return String.format("[%02d:%02d]", total / 60, total % 60);
    }
}
