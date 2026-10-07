package vn.machtrip.pipeline.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal WebVTT reader: cue start time plus cue text. */
public final class Vtt {

    public record Cue(double startSec, String text) {
    }

    private static final Pattern TIMING = Pattern.compile(
            "^\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})(?:[.,](\\d{1,3}))?\\s*-->");
    private static final Pattern INLINE_TAG = Pattern.compile("<[^>]*>");

    private Vtt() {
    }

    public static List<Cue> parse(String vtt) {
        List<Cue> cues = new ArrayList<>();
        if (vtt == null) {
            return cues;
        }
        Double start = null;
        StringBuilder text = new StringBuilder();
        for (String line : (vtt + "\n\n").split("\\R", -1)) {
            Matcher m = TIMING.matcher(line);
            if (m.find()) {
                flush(cues, start, text);
                start = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1))) * 3600.0
                        + Integer.parseInt(m.group(2)) * 60.0 + Integer.parseInt(m.group(3))
                        + (m.group(4) == null ? 0 : Double.parseDouble("0." + m.group(4)));
            } else if (line.isBlank()) {
                flush(cues, start, text);
                start = null;
            } else if (start != null) {
                text.append(text.isEmpty() ? "" : " ").append(INLINE_TAG.matcher(line).replaceAll("").trim());
            }
        }
        return cues;
    }

    private static void flush(List<Cue> cues, Double start, StringBuilder text) {
        if (start != null && !text.toString().isBlank()) {
            cues.add(new Cue(start, text.toString().replaceAll("\\s+", " ").trim()));
        }
        text.setLength(0);
    }
}
