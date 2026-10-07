package vn.machtrip.pipeline.extract;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the Genway LLM gateway (env GENWAY_BASE_URL, GENWAY_API_KEY).
 *
 * @param systemPromptAs where the system prompt goes in the text envelope: {@code message} (a {@code role: system}
 *                       chat message, the default) or {@code field} ({@code input.system}). docs/genway-llm-api.md
 *                       does not say; switch it if the first real call shows the model rejects the default.
 * @param thinking       {@code disabled} or {@code enabled}: sent as {@code input.thinking = {"type": ...}}. Empty (the
 *                       default) sends nothing. With thinking on, the model's reasoning counts against
 *                       {@code max_tokens}, which can leave no room for the answer; set {@code disabled} then.
 *                       The doc lists the field only for the deepseek models, so whether a Claude model honours it
 *                       is confirmed by a real call.
 * @param kongApiKey     key of the Kong gateway in front of Genway (env KONG_API_KEY), sent in the {@code apikey}
 *                       header when set. Not part of docs/genway-llm-api.md: found out against the real gateway,
 *                       which answers 401 "No API key found in request" without it.
 */
@ConfigurationProperties("genway")
public record GenwayProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("90s") Duration readTimeout,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("2") int maxConnectRetries,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("message") String systemPromptAs,
        @DefaultValue("") String kongApiKey,
        @DefaultValue("") String thinking) {

    @Override
    public String toString() {
        return "GenwayProperties[baseUrl=" + baseUrl + ", apiKey=***, kongApiKey=***]";
    }
}
