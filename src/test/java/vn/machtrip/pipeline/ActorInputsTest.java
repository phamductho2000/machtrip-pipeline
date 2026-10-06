package vn.machtrip.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.machtrip.pipeline.config.ActorInputs;
import vn.machtrip.pipeline.config.PipelineProperties;

class ActorInputsTest {

    private static final String COMMENTS = "{\"postURLs\":\"${videoUrls}\",\"commentsPerPost\":\"${maxPerVideo}\"}";
    private static final String SEARCH = "{\"_note\":\"TODO ignored\",\"hashtags\":[\"${hashtag}\"],\"resultsPerPage\":\"${limit}\"}";

    @TempDir Path dir;

    private ActorInputs load(String search, String comments) throws IOException {
        Files.createDirectories(dir.resolve("actors"));
        Files.writeString(dir.resolve("actors/search.json"), search);
        Files.writeString(dir.resolve("actors/comments.json"), comments);
        return new ActorInputs(new PipelineProperties(dir.toString(), "", "", null, null, null, null, null, null),
                new ObjectMapper());
    }

    @Test
    void placeholdersAreFilledWithTypedValuesAndNotesAreStripped() throws IOException {
        ActorInputs in = load(SEARCH, COMMENTS);

        var search = in.search("#reviewdalat", 100);
        assertThat(search.toString()).isEqualTo("{\"hashtags\":[\"reviewdalat\"],\"resultsPerPage\":100}");
        var comments = in.comments(List.of("https://t/1", "https://t/2"), 50);
        assertThat(comments.toString())
                .isEqualTo("{\"postURLs\":[\"https://t/1\",\"https://t/2\"],\"commentsPerPost\":50}");
    }

    @Test
    void searchInputMustEnforceTheItemLimitInsideTheActorInput() {
        assertThatThrownBy(() -> load("{\"hashtags\":[\"${hashtag}\"]}", COMMENTS))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("limit");
    }

    @Test
    void unfilledTodoFieldsAndUnknownPlaceholdersFailAtStart() {
        assertThatThrownBy(() -> load("{\"hashtags\":[\"${hashtag}\"],\"resultsPerPage\":\"${limit}\",\"x\":\"TODO\"}",
                COMMENTS)).hasMessageContaining("TODO");
        assertThatThrownBy(() -> load("{\"hashtags\":[\"${hashtag}\"],\"resultsPerPage\":\"${limit}\",\"x\":\"${nope}\"}",
                COMMENTS)).hasMessageContaining("nope");
        assertThatThrownBy(() -> load("not json", COMMENTS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shippedConfigFilesAreValid() {
        ActorInputs in = new ActorInputs(
                new PipelineProperties("config", "", "", null, null, null, null, null, null), new ObjectMapper());

        assertThat(in.search("reviewdalat", 5).path("resultsPerPage").asInt()).isEqualTo(5);
        assertThat(in.sample("comments").path("postURLs")).hasSize(1);
    }
}
