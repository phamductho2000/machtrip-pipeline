package vn.machtrip.pipeline.ingest;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import vn.machtrip.pipeline.config.PipelineProperties;

/** Filters from config/filters.yml, applied before any costly step (audio download, comments run). */
@Component
public class Filters {

    private final PipelineProperties.Filters cfg;

    public Filters(PipelineProperties props) {
        this.cfg = props.filters();
    }

    public boolean passes(JsonNode item) {
        return passes(item.path("videoMeta").path("duration").asInt(0), item.path("textLanguage").asText(null),
                item.path("isAd").asBoolean(false));
    }

    public boolean passes(int durationSec, String language, boolean isAd) {
        return durationSec >= cfg.minDurationSec()
                && language != null && allowedLanguages().contains(language.toLowerCase())
                && !(cfg.skipAds() && isAd);
    }

    public List<String> allowedLanguages() {
        return cfg.allowedLanguages().stream().map(String::toLowerCase).toList();
    }

    public int minDurationSec() {
        return cfg.minDurationSec();
    }

    public boolean skipAds() {
        return cfg.skipAds();
    }

    public int skipRecrawlDays() {
        return cfg.skipRecrawlDays();
    }
}
