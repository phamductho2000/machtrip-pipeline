package vn.machtrip.pipeline.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import vn.machtrip.pipeline.extract.ExtractProperties;
import vn.machtrip.pipeline.extract.ExtractionReporting;
import vn.machtrip.pipeline.extract.ExtractionService;
import vn.machtrip.pipeline.ingest.IngestService;
import vn.machtrip.pipeline.ingest.StatsService;
import vn.machtrip.pipeline.job.Worker;

/** picocli root command and all sub commands. Each sub command is a thin wrapper over a service. */
@Component
@Command(name = "machtrip-pipeline", mixinStandardHelpOptions = true, description = "Mach Trip pipeline: stage 1 crawl, stage 2 extraction",
        subcommands = {Cli.Search.class, Cli.Comments.class, Cli.Resume.class, Cli.Work.class, Cli.Reconcile.class,
                Cli.ServeWebhook.class, Cli.ValidateInput.class, Cli.Ingest.class, Cli.Stats.class,
                Cli.Extract.class, Cli.ExtractReport.class, Cli.ExtractEval.class, Cli.ExtractCompare.class,
                Cli.ExtractStats.class})
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
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
    @Scope("prototype")
    @Command(name = "stats", description = "Print counts of what is in schema pipeline")
    static class Stats extends Cmd {
        @Autowired StatsService stats;

        @Override
        public Integer call() {
            stats.snapshot().forEach((k, v) -> out().println(k + ": " + v));
            return 0;
        }
    }

    // ---- stage 2: extraction -------------------------------------------------------------------------------------

    @Component
    @Scope("prototype")
    @Command(name = "extract", description = "Extract mentions from collected videos with an LLM (via Genway)")
    static class Extract extends Cmd {
        @Option(names = "--limit", required = true, description = "max videos to process in this run") int limit;
        @Option(names = "--video", description = "only this tiktok id") String video;
        @Option(names = "--dry-run", description = "print exact inputs and estimated cost, make no network call")
        boolean dryRun;
        @Option(names = "--prompt-version", description = "default: extract.prompt-version (v1)") String promptVersion;
        @Option(names = "--model", description = "model name as Genway accepts it; default: extract.model") String model;
        @Option(names = "--force", description = "redo videos already extracted for this prompt version and model")
        boolean force;
        @Autowired ExtractionService extraction;
        @Autowired ExtractProperties props;

        @Override
        public Integer call() {
            String pv = promptVersion != null ? promptVersion : props.promptVersion();
            String m = extraction.resolveModel(model);
            var opts = new ExtractionService.Options(pv, dryRun && m == null ? "<model not set>" : m, limit,
                    video == null ? List.of() : List.of(video), force, null);
            if (!dryRun) {
                var s = extraction.execute(opts);
                out().println("Extraction run: " + s.selected() + " selected, " + s.ok() + " ok, " + s.failed()
                        + " failed, " + s.skipped() + " newly skipped (no input), cost $" + s.costUsd().toPlainString()
                        + (s.stoppedByCostCap() ? "\nSTOPPED: the next call would exceed the cost cap." : ""));
                return s.failed() > 0 ? 2 : 0;
            }
            var prompt = extraction.prompt(pv);
            var plans = extraction.plan(opts);
            out().println("DRY RUN: model=" + opts.model() + " prompt=" + pv + " videos=" + plans.size()
                    + " (would also record " + extraction.skippable(opts)
                    + " video(s) as skipped: no transcript, no comments)");
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            for (var p : plans) {
                out().println("\n=== video " + p.videoId() + " ===");
                out().println("system prompt sha256: " + prompt.hash());
                out().println("estimated input tokens: " + p.estInputTokens() + " (chars/" + props.charsPerToken()
                        + "), max output tokens: " + props.maxOutputTokens() + ", estimated cost (worst case): "
                        + (p.estCostUsd() == null ? "n/a (extract.pricing not set)" : "$" + p.estCostUsd().toPlainString()));
                if (p.input().truncated()) {
                    out().println("note: transcript truncated at the character cap");
                }
                out().println("--- user content (exactly what would be sent) ---");
                out().println(p.input().userContent());
                out().println("--- end ---");
                total = p.estCostUsd() == null ? total : total.add(p.estCostUsd());
            }
            out().println("\nTotal estimated worst-case cost: $" + total.toPlainString() + " (cap per run: $"
                    + props.maxCostUsdPerRun().toPlainString() + ")");
            out().println("DRY RUN: no network call was made.");
            return 0;
        }
    }

    @Component
    @Scope("prototype")
    @Command(name = "extract-report", description = "Write a Markdown review file of extraction results")
    static class ExtractReport extends Cmd {
        @Option(names = "--limit", defaultValue = "10", description = "latest N extracted videos") int limit;
        @Option(names = "--model", description = "only this model") String model;
        @Option(names = "--out", defaultValue = "reports", description = "output directory") Path outDir;
        @Autowired ExtractionReporting reporting;

        @Override
        public Integer call() {
            out().println("Wrote " + reporting.writeReport(limit, model, outDir));
            return 0;
        }
    }

    @Component
    @Scope("prototype")
    @Command(name = "extract-eval", description = "Precision/recall of extracted places against a hand-labeled gold file")
    static class ExtractEval extends Cmd {
        @Option(names = "--gold", required = true) Path gold;
        @Option(names = "--model", description = "default: extract.model") String model;
        @Option(names = "--prompt-version") String promptVersion;
        @Autowired ExtractionReporting reporting;
        @Autowired ExtractionService extraction;
        @Autowired ExtractProperties props;

        @Override
        public Integer call() {
            String m = extraction.resolveModel(model);
            if (m == null) {
                throw new IllegalStateException("No model: set extract.model or pass --model");
            }
            var g = reporting.readGold(gold);
            if (g.isEmpty()) {
                out().println("The gold file has no labeled videos yet (see gold/README.md).");
                return 1;
            }
            var r = reporting.evaluate(g, m, promptVersion != null ? promptVersion : props.promptVersion(), null);
            out().println("model " + m + ": " + r.videos() + " of " + g.size() + " gold videos have an extraction");
            out().println("places predicted " + r.predicted() + ", gold " + r.gold() + ", correct " + r.truePositives());
            out().println("precision " + pct(r.precision()) + ", recall " + pct(r.recall()));
            return 0;
        }
    }

    @Component
    @Scope("prototype")
    @Command(name = "extract-compare", description = "Run the same videos through several models and compare them")
    static class ExtractCompare extends Cmd {
        @Option(names = "--models", required = true, split = ",", description = "model names as Genway accepts them")
        List<String> models;
        @Option(names = "--gold", required = true) Path gold;
        @Option(names = "--limit", required = true, description = "max gold videos to run") int limit;
        @Option(names = "--prompt-version") String promptVersion;
        @Autowired ExtractionReporting reporting;
        @Autowired ExtractionService extraction;
        @Autowired ExtractProperties props;

        @Override
        public Integer call() {
            var g = reporting.readGold(gold);
            if (g.isEmpty()) {
                out().println("The gold file has no labeled videos yet (see gold/README.md).");
                return 1;
            }
            String pv = promptVersion != null ? promptVersion : props.promptVersion();
            List<String> videos = new ArrayList<>(g.keySet()).subList(0, Math.min(limit, g.size()));
            List<ExtractionReporting.Metrics> rows = new ArrayList<>();
            for (String m : models) {
                // each model is a separate run with its own cost cap; videos a model already has are not re-run
                var s = extraction.execute(new ExtractionService.Options(pv, m, videos.size(), videos, false, null));
                out().println(m + ": " + s.ok() + " ok, " + s.failed() + " failed"
                        + (s.stoppedByCostCap() ? ", STOPPED by the cost cap" : ""));
                rows.add(reporting.evaluate(g, m, pv, Set.copyOf(videos)));
            }
            out().println();
            out().println(String.format("%-32s %9s %9s %10s %16s %10s", "model", "precision", "recall",
                    "rejected%", "tokens in/out", "cost USD"));
            rows.forEach(r -> out().println(String.format("%-32s %9s %9s %10s %16s %10s", r.model(),
                    pct(r.precision()), pct(r.recall()), pct(r.rejectedRate()), r.inputTokens() + "/" + r.outputTokens(),
                    r.costUsd().toPlainString())));
            return 0;
        }
    }

    @Component
    @Scope("prototype")
    @Command(name = "extract-stats", description = "Counts of extractions, mentions, rejections and cost")
    static class ExtractStats extends Cmd {
        @Autowired ExtractionReporting reporting;

        @Override
        public Integer call() {
            reporting.stats().forEach((k, v) -> out().println(k + ": " + v));
            return 0;
        }
    }

    private static String pct(Double d) {
        return d == null ? "n/a" : String.format("%.1f%%", d * 100);
    }

    @Configuration(proxyBeanMethods = false)
    static class CliConfig {
        /** Prototype, like every sub command bean: each execution gets fresh instances, so option values never leak between runs. */
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
