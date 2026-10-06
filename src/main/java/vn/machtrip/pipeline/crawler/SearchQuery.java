package vn.machtrip.pipeline.crawler;

import java.util.List;

/** One or more hashtags searched together in a single Apify run (the actor's {@code hashtags} input is an array). */
public record SearchQuery(List<String> hashtags, int limit) {
    public SearchQuery {
        if (hashtags == null || hashtags.isEmpty()) {
            throw new IllegalArgumentException("hashtags must not be empty");
        }
        if (hashtags.stream().anyMatch(h -> h == null || h.isBlank())) {
            throw new IllegalArgumentException("hashtags must not contain a blank value");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        hashtags = List.copyOf(hashtags);
    }
}
