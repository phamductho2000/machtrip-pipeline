package vn.machtrip.pipeline;

import java.util.TimeZone;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import picocli.CommandLine;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {

    /**
     * CLI commands run without a web server (equivalent to --spring.main.web-application-type=none);
     * only {@code serve-webhook} starts one.
     */
    public static void main(String[] args) {
        // The pgjdbc driver sends the JVM default zone as a startup parameter. On a host whose OS timezone is
        // "SE Asia Standard Time" the JVM resolves it to the legacy IANA alias "Asia/Saigon", which some Postgres
        // builds reject outright ("invalid value for parameter TimeZone"), killing every connection before any SQL
        // runs. Nothing here is timezone-sensitive (DB uses `now()`/timestamptz server-side), so force UTC globally.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        boolean serve = args.length > 0 && args[0].equals("serve-webhook");
        SpringApplication app = new SpringApplication(Application.class);
        app.setWebApplicationType(serve ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        ConfigurableApplicationContext ctx = app.run(args);
        if (!serve || ctx.getBean(Runner.class).getExitCode() != 0) {
            System.exit(SpringApplication.exit(ctx));
        }
    }

    @Component
    @ConditionalOnProperty(name = "pipeline.cli.enabled", havingValue = "true", matchIfMissing = true)
    static class Runner implements CommandLineRunner, ExitCodeGenerator {

        private final ObjectProvider<CommandLine> commandLines;
        private int exitCode;

        Runner(ObjectProvider<CommandLine> commandLines) {
            this.commandLines = commandLines;
        }

        @Override
        public void run(String... args) {
            exitCode = commandLines.getObject().execute(args);
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
