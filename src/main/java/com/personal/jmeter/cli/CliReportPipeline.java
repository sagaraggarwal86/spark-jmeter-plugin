package com.personal.jmeter.cli;

import com.personal.jmeter.ai.*;
import com.personal.jmeter.listener.TransactionFilter;
import com.personal.jmeter.listener.TablePopulator;
import com.personal.jmeter.parser.DelimiterResolver;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Headless pipeline: parse JTL → build prompt → call AI → render HTML.
 *
 * <p>Reuses the project's existing parser, prompt builder, AI service,
 * and renderer without any Swing dependency. Progress messages are written
 * to {@code System.err} so {@code stdout} stays clean for piping.</p>
 */
final class CliReportPipeline {

    /**
     * Immutable result returned by {@link #execute()}.
     * Carries the output HTML path and the extracted AI verdict.
     *
     * @param outputPath absolute path of the generated HTML report
     * @param verdict    extracted verdict: "PASS", "FAIL", or "UNDECISIVE"
     */
    record PipelineResult(String outputPath, String verdict) {}

    private final CliArgs args;
    private final PrintStream progress;

    CliReportPipeline(CliArgs args) {
        this.args     = args;
        this.progress = System.err;
    }

    // ─────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────

    /**
     * Executes the full pipeline.
     *
     * @return {@link PipelineResult} containing the absolute path of the generated
     *         HTML report and the extracted AI verdict ("PASS", "FAIL", or "UNDECISIVE")
     * @throws IOException on parse, AI, or write failure
     */
    PipelineResult execute() throws IOException {

        // Step 1 — Parse JTL
        progress("Parsing JTL file: " + args.inputFile());
        JTLParser.FilterOptions opts = buildFilterOptions();
        JTLParser.ParseResult result = new JTLParser().parse(args.inputFile(), opts);
        progress("Parsed %d transaction types, %d total samples.",
                Math.max(0, result.results.size() - 1),
                result.results.containsKey(JTLParser.TOTAL_LABEL)
                        ? result.results.get(JTLParser.TOTAL_LABEL).getCount() : 0);

        // Step 2 — Build table rows
        List<String[]> tableRows = buildTableRows(result, opts.percentile);
        progress("Built %d table rows.", tableRows.size());

        // Step 3 — Resolve shared time/user context (used by both prompt and render config)
        TimeContext timeCtx = new TimeContext(
                result.formattedStartTime(),
                result.formattedEndTime(),
                result.formattedDuration(),
                args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "");

        // Step 4 — Resolve AI provider
        progress("Loading provider configuration from: " + args.configFile());
        AiProviderConfig provider = resolveProvider();
        progress("Provider: %s (model: %s)", provider.displayName, provider.model);

        // Step 5 — Validate and ping
        progress("Validating API key and pinging %s...", provider.displayName);
        String pingError = AiProviderRegistry.validateAndPing(provider);
        if (pingError != null) {
            throw new AiProviderException("Provider validation failed for " + provider.displayName
                    + ":\n" + pingError);
        }
        progress("Ping successful.");

        // Step 6 — Load prompt and build content
        progress("Building analysis prompt...");
        String systemPrompt = PromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Bundled prompt resource not found. Plugin JAR may be corrupt.");
        }
        PromptContent prompt = buildPromptContent(result, systemPrompt, timeCtx);

        // Step 7 — Call AI
        progress("Calling %s (this may take 30-60 seconds)...", provider.displayName);
        AiReportService service = new AiReportService(provider);
        final String markdown;
        try {
            markdown = service.generateReport(prompt);
        } catch (AiServiceException ex) {
            // Evict the ping cache when the provider rejects the request with an auth error
            // (HTTP 401 = key rejected, HTTP 403 = access denied / quota exceeded).
            // This forces a fresh live ping on the next run instead of hitting the stale
            // cached-success entry — which would otherwise bypass the ping indefinitely.
            if (ex.getMessage().contains("HTTP 401") || ex.getMessage().contains("HTTP 403")) {
                progress("Auth failure from provider — evicting ping cache for next run. provider=%s",
                        provider.providerKey);
                AiProviderRegistry.evictPingCache(provider);
            }
            throw ex;
        }
        progress("AI response received (%d characters).", markdown.length());

        // Step 8 — Extract verdict and strip machine verdict line before rendering
        String verdict       = MarkdownUtils.extractVerdict(markdown);
        String verdictSource = MarkdownUtils.verdictSource(markdown);
        String strippedMarkdown = MarkdownUtils.stripVerdictLine(markdown);
        progress("Verdict: %s (source: %s)", verdict, verdictSource);

        // Step 9 — Render HTML
        progress("Rendering HTML report...");
        HtmlReportRenderer.RenderConfig config = buildRenderConfig(result, timeCtx, provider);
        String outputPath = new HtmlReportRenderer().renderToFile(
                strippedMarkdown, args.outputFile(), config, tableRows, result.timeBuckets);
        progress("Report saved to: " + outputPath);

        return new PipelineResult(outputPath, verdict);
    }

    // ─────────────────────────────────────────────────────────────
    // Filter options
    // ─────────────────────────────────────────────────────────────

    private JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset         = args.startOffset();
        opts.endOffset           = args.endOffset();
        opts.percentile          = args.percentile();
        opts.chartIntervalSeconds = args.chartInterval();
        opts.delimiter           = DelimiterResolver.resolve(resolveJmeterHome());
        return opts;
    }

    /**
     * Resolves JMETER_HOME for delimiter detection.
     * Derives from the config file's parent directory structure:
     * {@code --config /path/to/jmeter/bin/ai-reporter.properties} → {@code /path/to/jmeter}.
     * Falls back to the {@code JMETER_HOME} environment variable.
     *
     * @return JMeter home directory, or {@code null} if not determinable
     */
    private java.io.File resolveJmeterHome() {
        // Derive from config file path: config is expected at $JMETER_HOME/bin/ai-reporter.properties
        java.io.File configFile = new java.io.File(args.configFile());
        java.io.File binDir = configFile.getAbsoluteFile().getParentFile();
        if (binDir != null && "bin".equalsIgnoreCase(binDir.getName())) {
            java.io.File home = binDir.getParentFile();
            if (home != null && home.isDirectory()) return home;
        }
        // Fallback: environment variable
        String env = System.getenv("JMETER_HOME");
        if (env != null && !env.isBlank()) {
            java.io.File f = new java.io.File(env);
            if (f.isDirectory()) return f;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Table row building (delegates to TablePopulator — single source of truth)
    // ─────────────────────────────────────────────────────────────

    private List<String[]> buildTableRows(JTLParser.ParseResult result, int percentile) {
        double pFraction = percentile / 100.0;
        List<String[]> rows = new ArrayList<>();

        for (SamplingStatCalculator calc : result.results.values()) {
            if (calc.getCount() == 0) continue;
            String label = calc.getLabel();
            if (JTLParser.TOTAL_LABEL.equals(label)) continue;

            // Apply search filter if specified
            if (!args.search().isBlank()) {
                boolean matches = TransactionFilter.matches(label, args.search(), args.regex());
                // Include mode: skip non-matching. Exclude mode: skip matching.
                if (args.exclude() ? matches : !matches) continue;
            }

            rows.add(TablePopulator.buildRowAsStrings(calc, pFraction));
        }
        return rows;
    }

    // ─────────────────────────────────────────────────────────────
    // Provider resolution
    // ─────────────────────────────────────────────────────────────

    private AiProviderConfig resolveProvider() throws IOException {
        List<AiProviderConfig> providers =
                AiProviderRegistry.loadConfiguredProviders(Path.of(args.configFile()));

        return providers.stream()
                .filter(p -> p.providerKey.equalsIgnoreCase(args.provider()))
                .findFirst()
                .orElseThrow(() -> new AiProviderException(
                        "Provider '" + args.provider() + "' not found in " + args.configFile()
                                + ".\nConfigured providers: "
                                + (providers.isEmpty() ? "(none)"
                                : String.join(", ", providers.stream()
                                .map(p -> p.providerKey).toList()))));
    }

    // ─────────────────────────────────────────────────────────────
    // Prompt content
    // ─────────────────────────────────────────────────────────────

    private PromptContent buildPromptContent(JTLParser.ParseResult result,
                                             String systemPrompt,
                                             TimeContext timeCtx) {
        String slaErrorPct = args.hasErrorSla()
                ? args.errorSla() + "%" : "Not configured";
        String slaRtMs = args.hasRtSla()
                ? args.rtSla() + " ms" : "Not configured";
        String slaRtMetric = "percentile".equals(args.rtMetric())
                ? "P" + args.percentile() + " (ms)" : "Avg (ms)";

        PromptRequest request = new PromptRequest(
                timeCtx.users(),
                args.scenarioName(),
                args.description(),
                timeCtx.startTime(),
                timeCtx.endTime(),
                timeCtx.duration(),
                "",  // threadGroupName — not available from CLI
                args.percentile(),
                slaErrorPct,
                slaRtMs,
                slaRtMetric);

        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
                result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);

        return new PromptBuilder(systemPrompt)
                .build(result.results, args.percentile(), request,
                        result.errorTypeSummary, latency,
                        result.timeBuckets);
    }

    // ─────────────────────────────────────────────────────────────
    // Render config
    // ─────────────────────────────────────────────────────────────

    private HtmlReportRenderer.RenderConfig buildRenderConfig(JTLParser.ParseResult result,
                                                              TimeContext timeCtx,
                                                              AiProviderConfig provider) {
        double errorSla = args.hasErrorSla() ? (double) args.errorSla() : -1.0;
        long   rtSla    = args.hasRtSla()    ? (long)   args.rtSla()    : -1L;
        String rtMetric = "percentile".equals(args.rtMetric()) ? "pnn" : "avg";
        return new HtmlReportRenderer.RenderConfig(
                timeCtx.users(),
                args.scenarioName(),
                args.description(),
                "",  // threadGroupName
                timeCtx.startTime(),
                timeCtx.endTime(),
                timeCtx.duration(),
                args.percentile(),
                provider.displayName,
                errorSla, rtSla, rtMetric);
    }

    // ─────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────

    private void progress(String format, Object... params) {
        progress.println("[CLI] " + String.format(format, params));
    }

    // ─────────────────────────────────────────────────────────────
    // Value types
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of formatted time and user-count values shared between
     * {@link #buildPromptContent} and {@link #buildRenderConfig}.
     * Computed once in {@link #execute()} to avoid duplicating the same four
     * derivations across two methods.
     */
    private record TimeContext(String startTime, String endTime,
                               String duration,  String users) {}
}