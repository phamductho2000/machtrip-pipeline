package vn.machtrip.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import vn.machtrip.pipeline.api.ApiKeyFilter;

@AutoConfigureMockMvc
class PipelineApiIT extends AbstractIT {

    private static final String API_KEY = "test-api-key-xyz";

    @DynamicPropertySource
    static void apiKeyProperty(DynamicPropertyRegistry r) {
        r.add("pipeline.api.key", () -> API_KEY);
    }

    @Autowired MockMvc mvc;

    @Test
    void missingOrWrongApiKeyIsRejectedBeforeTouchingAnything() throws Exception {
        mvc.perform(get("/api/v1/stats")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/stats").header(ApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/work").header(ApiKeyFilter.HEADER, "not-the-key"))
                .andExpect(status().isForbidden());

        assertThat(WM.getAllServeEvents()).isEmpty();
    }

    @Test
    void statsReturnsCountsWithAValidKey() throws Exception {
        mvc.perform(get("/api/v1/stats").header(ApiKeyFilter.HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void searchDryRunViaApiMakesNoNetworkCall() throws Exception {
        mvc.perform(post("/api/v1/runs/search").header(ApiKeyFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hashtags\":[\"reviewdalat\",\"avbc\"],\"limit\":100,\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.request").value(org.hamcrest.Matchers.containsString("reviewdalat")))
                .andExpect(jsonPath("$.request").value(org.hamcrest.Matchers.containsString("avbc")));

        assertThat(WM.getAllServeEvents()).isEmpty();
        assertThat(count("SELECT count(*) FROM crawl_run")).isZero();
    }

    @Test
    void searchWithoutLimitIsRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/runs/search").header(ApiKeyFilter.HEADER, API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hashtags\":[\"reviewdalat\"],\"dryRun\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("limit")));
    }

    @Test
    void workAndReconcileOnAnEmptyQueueReturnZero() throws Exception {
        mvc.perform(post("/api/v1/work").header(ApiKeyFilter.HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(0));
        mvc.perform(post("/api/v1/reconcile").header(ApiKeyFilter.HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciled").value(0));
    }

    @Test
    void resumeOfAnUnknownCrawlRunIs400WithAClearMessage() throws Exception {
        mvc.perform(post("/api/v1/runs/999999/resume").header(ApiKeyFilter.HEADER, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("999999")));
    }
}
