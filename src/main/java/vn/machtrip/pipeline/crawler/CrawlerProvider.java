package vn.machtrip.pipeline.crawler;

import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Boundary to the crawl provider; everything provider specific lives behind it. */
public interface CrawlerProvider {

    RunRef startSearch(SearchQuery query);

    RunStatus getRun(RunRef run);

    /** Streams dataset items page by page; never materialises the whole dataset. */
    Iterator<JsonNode> iterItems(RunRef run);

    /**
     * Streams items from an arbitrary dataset URL (e.g. Apify's signed {@code commentsDatasetUrl}: a comments run's
     * default dataset holds video items, each pointing at a separate dataset that actually holds the comments).
     * Paginated the same way as {@link #iterItems(RunRef)}.
     */
    Iterator<JsonNode> iterItemsFromUrl(String datasetUrl);

    RunRef startComments(List<String> videoUrls, int maxPerVideo);
}
