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

    RunRef startComments(List<String> videoUrls, int maxPerVideo);
}
