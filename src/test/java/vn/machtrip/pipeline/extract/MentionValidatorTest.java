package vn.machtrip.pipeline.extract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import vn.machtrip.pipeline.extract.ExtractionInputBuilder.Comment;
import vn.machtrip.pipeline.extract.ExtractionInputBuilder.ExtractionInput;
import vn.machtrip.pipeline.extract.ExtractionInputBuilder.VideoData;

class MentionValidatorTest {

    private static final String VTT = """
            WEBVTT

            00:00:02.000 --> 00:00:05.000
            hôm nay mình đến Sương Mù Coffee nằm trên đồi thông

            00:00:09.000 --> 00:00:12.000
            ly cà phê muối 45k
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final MentionValidator validator = new MentionValidator(TestProps.extract(), mapper);
    private final ExtractionInput input = new ExtractionInputBuilder(TestProps.extract()).build(new VideoData("1",
            "Cafe view đồi thông", List.of(), "name: Sương Mù Coffee | city: Đà Lạt", VTT,
            List.of(new Comment("c101", 87, "Quán này đóng cửa từ tháng 8 rồi"))));

    private ObjectNode mention(String source, String commentId, String quote) {
        ObjectNode m = mapper.createObjectNode();
        m.put("kind", "place").put("name_raw", "Sương Mù Coffee").put("name_confidence", "high").put("category", "eat")
                .put("text", "Quán cà phê").put("price_text", "45k").put("price_vnd_min", 45000)
                .put("price_vnd_max", 45000).put("sentiment", "positive").put("sponsored_signal", false)
                .put("energy", 0.2);
        m.putArray("tags").add("cafe-view");
        m.putArray("best_for").add("couple");
        ObjectNode ev = m.putObject("evidence");
        ev.put("source", source).put("quote", quote).putNull("t_start_sec");
        if (commentId == null) {
            ev.putNull("comment_id");
        } else {
            ev.put("comment_id", commentId);
        }
        return m;
    }

    private MentionValidator.Result run(ObjectNode... mentions) {
        ObjectNode root = mapper.createObjectNode();
        var arr = root.putArray("mentions");
        for (ObjectNode m : mentions) {
            arr.add(m);
        }
        return validator.validate(root, input);
    }

    @Test
    void mentionWhoseQuoteIsNotInTheInputIsDroppedAndRecorded() {
        var r = run(mention("transcript", null, "Sương Mù Coffee nằm trên đồi thông"),
                mention("transcript", null, "Quán Trăng Non phục vụ rất ngon"));

        assertThat(r.accepted()).hasSize(1);
        assertThat(r.rejected()).hasSize(1);
        assertThat(r.rejected().get(0).reason()).isEqualTo("quote_not_in_input");
        assertThat(r.rejected().get(0).payload().path("evidence").path("quote").asText())
                .isEqualTo("Quán Trăng Non phục vụ rất ngon");
    }

    @Test
    void quoteMatchingIgnoresCaseAndWhitespaceButNotDiacritics() {
        var ok = run(mention("transcript", null, "  SƯƠNG   mù coffee\nnằm trên đồi thông "));
        var noDiacritics = run(mention("transcript", null, "Suong Mu Coffee nam tren doi thong"));

        assertThat(ok.accepted()).hasSize(1);
        assertThat(noDiacritics.rejected()).extracting(MentionValidator.Rejected::reason)
                .containsExactly("quote_not_in_input");
    }

    @Test
    void quoteIsCheckedAgainstTheNamedSourceOnly() {
        var r = run(
                mention("caption", null, "Cafe view đồi thông"),
                mention("location_tag", null, "Sương Mù Coffee"),
                mention("comment", "c101", "Quán này đóng cửa từ tháng 8"),
                mention("caption", null, "Sương Mù Coffee nằm trên đồi thông"), // it is in the transcript, not the caption
                mention("comment", "c101", "Cafe view đồi thông"), // it is in the caption, not that comment
                mention("comment", "c999", "Quán này đóng cửa"));

        assertThat(r.accepted()).hasSize(3);
        assertThat(r.rejected()).extracting(MentionValidator.Rejected::reason)
                .containsExactly("quote_not_in_input", "quote_not_in_input", "unknown_comment_id");
    }

    @Test
    void tagsOutsideTheClosedListAreRemoved() {
        ObjectNode m = mention("transcript", null, "Sương Mù Coffee");
        m.putArray("tags").add("cafe-view").add("Cloud-Hunting").add("made-up-tag").add("cafe-view");

        var r = run(m);

        assertThat(r.accepted().get(0).path("tags")).extracting(JsonNode::asText)
                .containsExactly("cafe-view", "cloud-hunting");
    }

    @Test
    void energyIsOnlyKeptForPlacesAndOnlyInsideZeroToOne() {
        ObjectNode high = mention("transcript", null, "Sương Mù Coffee");
        high.put("energy", 1.7);
        ObjectNode tip = mention("transcript", null, "Sương Mù Coffee");
        tip.put("kind", "tip").put("energy", 0.5);

        var r = run(mention("transcript", null, "Sương Mù Coffee"), high, tip);

        assertThat(r.accepted().get(0).path("energy").asDouble()).isEqualTo(0.2);
        assertThat(r.accepted().get(1).path("energy").isNull()).isTrue();
        assertThat(r.accepted().get(2).path("energy").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "50k;50000;true", "400.000;400000;true", "2 củ;2000000;true", "1tr5;1500000;true",
            "1,5 triệu;1500000;true", "2 củ rưỡi;2500000;true", "tầm 50-70k;70000;true", "45k;450000;false",
            "50k;5000;false", "giá rẻ;50000;false"})
    void priceNumbersMustMatchTheDigitsOfThePriceText(String text, long value, boolean consistent) {
        ObjectNode m = mention("transcript", null, "Sương Mù Coffee");
        m.put("price_text", text).put("price_vnd_min", value).put("price_vnd_max", value);

        var accepted = run(m).accepted().get(0);

        assertThat(accepted.path("price_text").asText()).isEqualTo(text); // the original text is always kept
        assertThat(accepted.path("price_vnd_min").isNull()).isEqualTo(!consistent);
        assertThat(accepted.path("price_vnd_max").isNull()).isEqualTo(!consistent);
    }

    @Test
    void priceNumbersWithoutPriceTextAreNulled() {
        ObjectNode m = mention("transcript", null, "Sương Mù Coffee");
        m.putNull("price_text");

        assertThat(run(m).accepted().get(0).path("price_vnd_min").isNull()).isTrue();
    }
}
