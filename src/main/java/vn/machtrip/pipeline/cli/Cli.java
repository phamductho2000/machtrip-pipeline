package vn.machtrip.pipeline.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import vn.machtrip.pipeline.config.ActorInputs;
import vn.machtrip.pipeline.config.PipelineProperties;
import vn.machtrip.pipeline.crawler.ApifyProvider;
import vn.machtrip.pipeline.crawler.CrawlService;
import vn.machtrip.pipeline.crawler.SearchQuery;
import vn.machtrip.pipeline.ingest.IngestService;
import vn.machtrip.pipeline.ingest.StatsService;
import vn.machtrip.pipeline.job.Worker;

/** picocli root command and all sub commands. Each sub command is a thin wrapper over a service. */
@Component
@Command(name = "machtrip-pipeline", mixinStandardHelpOptions = true, description = "Mach Trip pipeline, stage 1: crawl",
        subcommands = {Cli.Search.class, Cli.Comments.class, Cli.Resume.class, Cli.Work.class, Cli.Reconcile.class,
                Cli.ServeWebhook.class, Cli.ValidateInput.class, Cli.Ingest.class, Cli.Stats.class})
public class Cli implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    abstract static class Cmd implements Callable<Integer> {
        @Spec
        CommandSpec spec;

        PrintWriter out() {
            return spec.commandLine().getOut();
        }
    }

    @Component
    @Command(name = "search", description = "Start an Apify search run for one or more hashtags")
    static class Search extends Cmd {
        @Option(names = "--hashtag", required = true, split = ",",
                description = "repeatable (--hashtag a --hashtag b) or comma-separated (--hashtag a,b)")
        List<String> hashtags;
        @Option(names = "--limit", required = true, description = "max videos per hashtag for this run") int limit;
        @Option(names = "--dry-run", description = "print the exact request, make no network call") boolean dryRun;
        @Autowired ApifyProvider apify;
        @Autowired CrawlService crawl;

        @Override
        public Integer call() {
            SearchQuery q = new SearchQuery(hashtags, limit);
            if (dryRun) {
                out().println(apify.describe(apify.searchRequest(q)));
                out().println("DRY RUN: no network call was made.");
                return 0;
            }
            long id = crawl.startSearch(q);
            out().println("Started crawl_run " + id + ". Completion arrives by webhook (or run `reconcile`); "
                    + "then run `work` to ingest.");
            return 0;
        }
    }

    @Component
    @Command(name = "comments", description = "Start a comments run for filtered videos already in the database")
    static class Comments extends Cmd {
        @Option(names = "--limit", required = true, description = "max videos to crawl comments for") int limit;
        @Option(names = "--dry-run", description = "print the exact request, make no network call") boolean dryRun;
        @Autowired ApifyProvider apify;
        @Autowired CrawlService crawl;
        @Autowired PipelineProperties props;

        @Override
        public Integer call() {
            if (!dryRun) {
                Optional<Long> id = crawl.startComments(limit);
                out().println(id.map(i -> "Started crawl_run " + i)
                        .orElse("No video passes the filters (or all were crawled recently); nothing started."));
                return 0;
            }
            var picked = crawl.selectForComments(crawl.maxVideos(limit));
            if (picked.isEmpty()) {
                out().println("DRY RUN: no video passes the filters; nothing would be started.");
                return 0;
            }
            int per = props.comments().perVideoCap();
            out().println(apify.describe(apify.commentsRequest(
                    picked.stream().map(CrawlService.Candidate::url).toList(), per, picked.size() * per)));
            out().println("DRY RUN: " + picked.size() + " videos selected; no network call was made.");
            return 0;
        }
    }

    @Component
    @Command(name = "resume", description = "Re-check a crawl_run with Apify and queue its ingest job")
    static class Resume extends Cmd {
        @Parameters(index = "0", description = "crawl_run id") long crawlRunId;
        @Autowired CrawlService crawl;

        @Override
        public Integer call() {
            out().println(crawl.resume(crawlRunId));
            return 0;
        }
    }

    @Component
    @Command(name = "work", description = "Worker loop: ingest finished runs from pipeline.job")
    static class Work extends Cmd {
        @Option(names = "--once", description = "drain the queue, then exit") boolean once;
        @Autowired Worker worker;

        @Override
        public Integer call() {
            if (!once) {
                Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
            }
            out().println("Processed " + worker.run(once) + " job(s).");
            return 0;
        }
    }

    @Component
    @Command(name = "reconcile", description = "Polling fallback for runs whose webhook never arrived")
    static class Reconcile extends Cmd {
        @Autowired CrawlService crawl;

        @Override
        public Integer call() {
            out().println("Reconciled " + crawl.reconcile() + " run(s).");
            return 0;
        }
    }

    @Component
    @Command(name = "serve-webhook", description = "Start the web server for POST /webhooks/apify/{secret}")
    static class ServeWebhook extends Cmd {
        @Autowired ApplicationContext ctx;
        @Autowired PipelineProperties props;

        @Override
        public Integer call() {
            if (!(ctx instanceof WebApplicationContext)) {
                spec.commandLine().getErr().println("serve-webhook needs the web server; start it through the jar "
                        + "entry point (java -jar machtrip-pipeline.jar serve-webhook).");
                return 1;
            }
            if (props.webhook().secret().isBlank()) {
                spec.commandLine().getErr().println("WEBHOOK_SECRET is not set; refusing to serve the webhook.");
                return 1;
            }
            out().println("Webhook server starting (listening address/port from server.address / server.port).");
            return 0;
        }
    }

    @Component
    @Command(name = "validate-input", description = "Validate config/actors/<kind>.json with Apify (real API call)")
    static class ValidateInput extends Cmd {
        enum Kind { search, comments }

        @Option(names = "--kind", required = true, description = "search | comments") Kind kind;
        @Autowired ApifyProvider apify;
        @Autowired ActorInputs inputs;

        @Override
        public Integer call() {
            var result = apify.validateInput(inputs.sample(kind.name()));
            out().println(result.valid() ? "VALID: " + result.message() : "INVALID: " + result.message());
            return result.valid() ? 0 : 1;
        }
    }

    @Component
    @Command(name = "ingest", description = "Load an Apify dataset export (JSON array) offline; no network")
    static class Ingest extends Cmd {
        @Option(names = "--file", required = true) Path file;
        @Option(names = "--kind", defaultValue = "search", description = "search | comments") String kind;
        @Autowired IngestService ingest;

        @Override
        public Integer call() {
            var s = ingest.ingestFile(file, kind);
            out().println("Ingested " + s.items() + " items (" + s.videos() + " videos).");
            return 0;
        }
    }

    @Component
    @Command(name = "stats", description = "Print counts of what is in schema pipeline")
    static class Stats extends Cmd {
        @Autowired StatsService stats;

        @Override
        public Integer call() {
            stats.snapshot().forEach((k, v) -> out().println(k + ": " + v));
            return 0;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CliConfig {
        /** Prototype: every execution gets a fresh CommandLine, so option values never leak between runs. */
        @Bean
        @Scope("prototype")
        CommandLine commandLine(CommandLine.IFactory factory, Cli root) {
            CommandLine cl = new CommandLine(root, factory);
            cl.setExecutionExceptionHandler((ex, c, parseResult) -> {
                c.getErr().println("ERROR: " + ex.getMessage());
                return 1;
            });
            return cl;
        }
    }
}
