package vn.machtrip.pipeline.extract;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the extraction stage ({@code extract.*}; the tag list comes from config/tags.yml).
 *
 * @param model TODO(user): a model name Genway accepts. Unset on purpose; real runs refuse to start without it.
 * @param structuredOutput send the JSON schema to Genway as structured output; off until docs/genway-llm-api.md says
 *                         it is supported
 */
@ConfigurationProperties("extract")
public record ExtractProperties(
        String model,
        @DefaultValue Pricing pricing,
        @DefaultValue("0.50") BigDecimal maxCostUsdPerRun,
        @DefaultValue("50") int maxVideosPerRun,
        @DefaultValue("2") int concurrency,
        @DefaultValue("30") int maxComments,
        @DefaultValue("400") int maxCommentChars,
        @DefaultValue("24000") int maxTranscriptChars,
        @DefaultValue("3") int charsPerToken,
        @DefaultValue("4096") int maxOutputTokens,
        @DefaultValue("false") boolean structuredOutput,
        @DefaultValue("v1") String promptVersion,
        @DefaultValue List<String> tags) {

    /** USD per token. TODO(user): unset on purpose, because a cost cap without prices would silently not cap. */
    public record Pricing(BigDecimal inputPerToken, BigDecimal outputPerToken) {
        public boolean configured() {
            return inputPerToken != null && outputPerToken != null;
        }
    }
}
