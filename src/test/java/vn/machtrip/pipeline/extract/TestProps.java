package vn.machtrip.pipeline.extract;

import java.math.BigDecimal;
import java.util.List;

/** Hand-built ExtractProperties for unit tests (no Spring). */
final class TestProps {

    static final List<String> TAGS = List.of("cloud-hunting", "cafe-view", "photo-spot", "street-food");

    private TestProps() {
    }

    static ExtractProperties extract(int maxComments, int maxCommentChars, int maxTranscriptChars) {
        return new ExtractProperties("test-model",
                new ExtractProperties.Pricing(new BigDecimal("0.000001"), new BigDecimal("0.000002")),
                new BigDecimal("0.50"), 50, 2, maxComments, maxCommentChars, maxTranscriptChars, 3, 1000, false, "v1",
                TAGS);
    }

    static ExtractProperties extract() {
        return extract(30, 400, 24_000);
    }
}
