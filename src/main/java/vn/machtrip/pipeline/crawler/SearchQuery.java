package vn.machtrip.pipeline.crawler;

public record SearchQuery(String hashtag, int limit) {
    public SearchQuery {
        if (hashtag == null || hashtag.isBlank()) {
            throw new IllegalArgumentException("hashtag must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
    }
}
