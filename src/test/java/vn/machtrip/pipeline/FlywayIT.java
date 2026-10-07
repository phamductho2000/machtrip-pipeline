package vn.machtrip.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The container is pre-seeded like the backend's database (see sim-backend.sql) before Flyway ever runs. */
class FlywayIT extends AbstractIT {

    @Test
    void migrationsLiveInSchemaPipelineWithTheirOwnHistoryTable() {
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'pipeline' ORDER BY 1""")
                .query(String.class).list();
        assertThat(tables).contains("flyway_schema_history", "crawl_run", "job", "raw_item", "video",
                "video_subtitle", "video_audio", "video_asr_queue", "comment", "video_extraction", "mention",
                "mention_rejected");
        assertThat(count("SELECT count(*) FROM pipeline.flyway_schema_history WHERE success AND version = '1'"))
                .isEqualTo(1);
    }

    @Test
    void noOtherSchemaWasTouched() {
        // the backend's footprint is exactly what the init script created
        assertThat(jdbc.sql("""
                SELECT table_schema || '.' || table_name FROM information_schema.tables
                WHERE table_schema NOT IN ('pipeline', 'pg_catalog', 'information_schema') ORDER BY 1""")
                .query(String.class).list())
                .containsExactly("backend_other.thing", "public.app_user", "public.flyway_schema_history");
        assertThat(jdbc.sql("""
                SELECT schema_name FROM information_schema.schemata
                WHERE schema_name NOT LIKE 'pg\\_%' AND schema_name <> 'information_schema' ORDER BY 1""")
                .query(String.class).list()).containsExactly("backend_other", "pipeline", "public");

        // the backend's own Flyway history and data are intact
        assertThat(count("SELECT count(*) FROM public.flyway_schema_history")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT description FROM public.flyway_schema_history").query(String.class).single())
                .isEqualTo("backend init");
        assertThat(count("SELECT count(*) FROM public.app_user")).isEqualTo(1);
        assertThat(count("SELECT x FROM backend_other.thing")).isEqualTo(42);
    }
}
