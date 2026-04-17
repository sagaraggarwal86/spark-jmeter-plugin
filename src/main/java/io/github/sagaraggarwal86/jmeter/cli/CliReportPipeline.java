package io.github.sagaraggarwal86.jmeter.cli;

import io.github.sagaraggarwal86.jmeter.ai.prompt.PromptBuilder;
import io.github.sagaraggarwal86.jmeter.ai.prompt.PromptContent;
import io.github.sagaraggarwal86.jmeter.ai.prompt.PromptLoader;
import io.github.sagaraggarwal86.jmeter.ai.prompt.PromptRequest;
import io.github.sagaraggarwal86.jmeter.ai.provider.AiProviderConfig;
import io.github.sagaraggarwal86.jmeter.ai.provider.AiProviderException;
import io.github.sagaraggarwal86.jmeter.ai.provider.AiProviderRegistry;
import io.github.sagaraggarwal86.jmeter.ai.provider.AiReportService;
import io.github.sagaraggarwal86.jmeter.ai.report.HtmlReportRenderer;
import io.github.sagaraggarwal86.jmeter.ai.report.MarkdownUtils;
import io.github.sagaraggarwal86.jmeter.listener.core.SlaEvaluator;
import io.github.sagaraggarwal86.jmeter.listener.core.TablePopulator;
import io.github.sagaraggarwal86.jmeter.listener.core.TransactionFilter;
import io.github.sagaraggarwal86.jmeter.parser.DelimiterResolver;
import io.github.sagaraggarwal86.jmeter.parser.JTLParser;
import io.github.sagaraggarwal86.jmeter.parser.JtlParseException;
import io.github.sagaraggarwal86.jmeter.parser.TimestampFormatResolver;
import io.github.sagaraggarwal86.jmeter.report.DataReportBuilder;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Headless pipeline: parse JTL → build prompt → call AI → render HTML.
 *
 * <p>Reuses the project's existing parser, prompt builder, AI service,
 * and renderer without any Swing dependency. Progress messages are written
 * to {@code System.err} so {@code stdout} stays clean for piping.</p>
 */
final class CliReportPipeline {

    private final CliArgs args;
    private final PrintStream progress;

    CliReportPipeline(CliArgs args) {
        this.args = args;
        this.progress = System.err;
    }

    /**
     * Executes the full pipeline.
     *
     * @return {@link PipelineResult} containing the absolute path of the generated
     * HTML report and the extracted AI verdict ("PASS", "FAIL", or "UNDECISIVE")
     * @throws IOException on parse, AI, or write failure
     */
    PipelineResult execute() throws IOException {

        // Step 1 — Parse JTL
        progress("Parsing JTL file: " + args.inputFile());
        JTLParser.FilterOptions opts = buildFilterOptions();
        JTLParser.ParseResult result = new JTLParser().parse(args.inputFile(), opts);
        long totalSamples = result.results.containsKey(JTLParser.TOTAL_LABEL)
            ? result.results.get(JTLParser.TOTAL_LABEL).getCount() : 0;
        progress("Parsed %d transaction types, %d total samples.",
            Math.max(0, result.results.size() - 1), totalSamples);
        if (totalSamples == 0) {
            throw new JtlParseException(
                "No samples matched the filter criteria. Check --start-offset, --end-offset, "
                    + "--search, and --exclude settings.");
        }

        // Step 2 — Build table rows
        List<String[]> tableRows = buildTableRows(result, opts.percentile);
        progress("Built %d table rows.", tableRows.size());

        // Step 3 — Resolve shared time/user context (used by both prompt and render config)
        TimeContext timeCtx = new TimeContext(
            result.formattedStartTime(),
            result.formattedEndTime(),
            result.formattedDuration(),
            args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "");

        // ── Branch: SLA-only vs AI+SLA ──────────────────────────────
        if (!args.hasProvider()) {
            return executeSlaOnly(result, tableRows, timeCtx);
        }
        return executeWithAi(result, tableRows, timeCtx);
    }

    /**
     * Non-AI path: evaluate SLA thresholds and/or compute classification,
     * then render a data-only HTML report via {@link DataReportBuilder}.
     * When no SLAs are configured, falls back to classification-based verdict.
     */
    private PipelineResult executeSlaOnly(JTLParser.ParseResult result,
                                          List<String[]> tableRows,
                                          TimeContext timeCtx) throws IOException {

        boolean hasSla = args.hasTpsSla() || args.hasErrorSla() || args.hasRtSla();
        boolean useAvg = "avg".equals(rtMetricValue());

        // ── Classification (always computed for data-only report) ─────────
        double pFraction = args.percentile() / 100.0;
        Map<String, Object> globalStats = PromptBuilder.buildGlobalStats(
            result.results, args.percentile(), pFraction,
            new PromptBuilder.LatencyContext(
                result.avgLatencyMs, result.avgConnectMs, result.latencyPresent));
        Map<String, Object> classification = PromptBuilder.buildClassificationSummary(
            globalStats, result.timeBuckets);

        // ── SLA evaluation (only when thresholds configured) ─────────────
        String slaVerdictHtml = null;
        String verdict;

        if (hasSla) {
            progress("SLA-only mode — no AI provider configured.");
            SlaEvaluator.SlaResult slaResult = SlaEvaluator.evaluate(
                tableRows, tpsSlaValue(), errorSlaValue(), rtSlaValue(), useAvg);
            verdict = slaResult.verdict();
            slaVerdictHtml = SlaEvaluator.buildVerdictHtml(slaResult,
                tpsSlaValue(), errorSlaValue(), rtSlaValue(), useAvg, args.percentile())
                + DataReportBuilder.buildSlaBreachDetails(
                tableRows, tpsSlaValue(), errorSlaValue(), rtSlaValue(), rtMetricValue());
            progress("SLA Verdict: %s", verdict);
        } else {
            progress("Analysis mode — classification-based verdict (no SLA, no AI).");
            Map<String, Object> verdictResult = PromptBuilder.buildOverallVerdictSummary(
                null, null, null, classification, globalStats);
            verdict = String.valueOf(verdictResult.getOrDefault("verdict", "PASS"));
            progress("Verdict: %s (source: CLASSIFICATION)", verdict);
        }

        String classLabel = String.valueOf(classification.getOrDefault("label", "THROUGHPUT-BOUND"));
        progress("Classification: %s", classLabel);

        // ── Build data-only report ──────────────────────────────────────
        String modeName = hasSla ? "SLA Evaluation Mode" : "Classification Analysis Mode";
        HtmlReportRenderer.RenderConfig config = buildRenderConfig(result, timeCtx, modeName);

        List<String[]> contentSections = DataReportBuilder.buildSections(
            classification, globalStats, slaVerdictHtml,
            tableRows, args.percentile(), rtMetricValue());

        progress("Rendering HTML report (%s)...", hasSla ? "SLA + classification" : "classification-based");
        String outputPath = new HtmlReportRenderer().renderDataReport(
            args.outputFile(), config, tableRows, result.timeBuckets, verdict, contentSections);
        progress("Report saved to: " + outputPath);
        return new PipelineResult(outputPath, verdict);
    }

    /**
     * Full AI+SLA path: resolve provider, call AI, render HTML with AI sections + SLA.
     */
    private PipelineResult executeWithAi(JTLParser.ParseResult result,
                                         List<String[]> tableRows,
                                         TimeContext timeCtx) throws IOException {
        // Step 4 — Resolve AI provider
        progress("Loading provider configuration from: " + args.configFile());
        AiProviderConfig provider = resolveProvider();
        progress("Provider: %s (model: %s)", provider.displayName, provider.model);

        // Step 5 — Validate and ping
        progress("Validating API key and pinging %s...", provider.displayName);
        String pingError = AiProviderRegistry.validateAndPing(provider);
        if (pingError != null) {
            progress("Provider validation failed: %s", pingError);
            progress("Falling back to data-only report (all metrics and verdict are Java-computed)...");
            return executeSlaOnly(result, tableRows, timeCtx);
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
        progress("Calling %s (this may take up to %d seconds)...", provider.displayName, provider.timeoutSeconds);
        AiReportService service = new AiReportService(provider);
        final String markdown;
        final long aiElapsedMs;
        try {
            long aiStart = System.currentTimeMillis();
            markdown = service.generateReport(prompt);
            aiElapsedMs = System.currentTimeMillis() - aiStart;
        } catch (IOException ex) {
            if (ex.getMessage() != null
                && (ex.getMessage().contains("HTTP 401") || ex.getMessage().contains("HTTP 403"))) {
                progress("Auth failure from provider — evicting ping cache for next run. provider=%s",
                    provider.providerKey);
                AiProviderRegistry.evictPingCache(provider);
            }
            // Fall back to data-only report — verdict is Java-computed, not AI-dependent
            progress("AI provider failed: %s", ex.getMessage());
            progress("Falling back to data-only report (all metrics and verdict are Java-computed)...");
            return executeSlaOnly(result, tableRows, timeCtx);
        }
        progress("AI response received: %d chars in %.1fs.", markdown.length(), aiElapsedMs / 1000.0);

        // Step 8 — Extract verdict and strip machine verdict line before rendering
        String verdict = MarkdownUtils.extractVerdict(markdown);
        String verdictSource = MarkdownUtils.verdictSource(markdown);
        String strippedMarkdown = MarkdownUtils.stripVerdictLine(markdown);
        progress("Verdict: %s (source: %s)", verdict, verdictSource);

        // Step 9 — Render HTML
        progress("Rendering HTML report...");
        HtmlReportRenderer.RenderConfig config = buildRenderConfig(result, timeCtx, provider);
        String outputPath = new HtmlReportRenderer().renderToFile(
            strippedMarkdown, args.outputFile(), config, tableRows, result.timeBuckets, verdict);
        progress("Report saved to: " + outputPath);

        return new PipelineResult(outputPath, verdict);
    }

    // ─────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────

    private JTLParser.FilterOptions buildFilterOptions() {
        java.io.File jmeterHome = resolveJmeterHome();
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset = args.startOffset();
        opts.endOffset = args.endOffset();
        opts.percentile = args.percentile();
        opts.chartIntervalSeconds = args.chartInterval();
        opts.delimiter = DelimiterResolver.resolve(jmeterHome);
        opts.timestampFormatter = TimestampFormatResolver.resolve(jmeterHome);
        return opts;
    }

    // ─────────────────────────────────────────────────────────────
    // Filter options
    // ─────────────────────────────────────────────────────────────

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
        String configPath = args.configFile();
        if (configPath != null && !configPath.isBlank()) {
            java.io.File binDir = new java.io.File(configPath).getAbsoluteFile().getParentFile();
            if (binDir != null && "bin".equalsIgnoreCase(binDir.getName())) {
                java.io.File home = binDir.getParentFile();
                if (home != null && home.isDirectory()) return home;
            }
        }
        // Fallback: environment variable
        String env = System.getenv("JMETER_HOME");
        if (env != null && !env.isBlank()) {
            java.io.File f = new java.io.File(env);
            if (f.isDirectory()) return f;
        }
        return null;
    }

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
    // Table row building (delegates to TablePopulator — single source of truth)
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
    // Provider resolution
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
        String slaTps = args.hasTpsSla()
            ? args.tpsSla() + "/sec" : "Not configured";

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
            slaRtMetric,
            slaTps);

        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
            result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);

        return new PromptBuilder(systemPrompt)
            .build(result.results, args.percentile(), request,
                result.errorTypeSummary, latency,
                result.timeBuckets);
    }

    // ─────────────────────────────────────────────────────────────
    // Prompt content
    // ─────────────────────────────────────────────────────────────

    private double tpsSlaValue() {
        return args.hasTpsSla() ? args.tpsSla() : -1.0;
    }

    private double errorSlaValue() {
        return args.hasErrorSla() ? (double) args.errorSla() : -1.0;
    }

    private long rtSlaValue() {
        return args.hasRtSla() ? (long) args.rtSla() : -1L;
    }

    private String rtMetricValue() {
        return "percentile".equals(args.rtMetric()) ? "pnn" : "avg";
    }

    /**
     * RenderConfig for full AI+SLA mode.
     */
    private HtmlReportRenderer.RenderConfig buildRenderConfig(JTLParser.ParseResult result,
                                                              TimeContext timeCtx,
                                                              AiProviderConfig provider) {
        return new HtmlReportRenderer.RenderConfig(
            timeCtx.users(), args.scenarioName(), args.description(),
            "", timeCtx.startTime(), timeCtx.endTime(), timeCtx.duration(),
            args.percentile(), provider.displayName,
            tpsSlaValue(), errorSlaValue(), rtSlaValue(), rtMetricValue(),
            result.errorTypeSummary,
            result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);
    }

    /**
     * RenderConfig for non-AI mode (SLA-only or classification-based).
     */
    private HtmlReportRenderer.RenderConfig buildRenderConfig(JTLParser.ParseResult result,
                                                              TimeContext timeCtx,
                                                              String modeName) {
        return new HtmlReportRenderer.RenderConfig(
            timeCtx.users(), args.scenarioName(), args.description(),
            "", timeCtx.startTime(), timeCtx.endTime(), timeCtx.duration(),
            args.percentile(), modeName,
            tpsSlaValue(), errorSlaValue(), rtSlaValue(), rtMetricValue(),
            result.errorTypeSummary,
            result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);
    }


    // ─────────────────────────────────────────────────────────────
    // Render config
    // ─────────────────────────────────────────────────────────────

    private void progress(String format, Object... params) {
        progress.println("[CLI] " + String.format(format, params));
    }

    // ─────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable result returned by {@link #execute()}.
     * Carries the output HTML path and the extracted AI verdict.
     *
     * @param outputPath absolute path of the generated HTML report
     * @param verdict    extracted verdict: "PASS", "FAIL", or "UNDECISIVE"
     */
    record PipelineResult(String outputPath, String verdict) {
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
                               String duration, String users) {
    }
}
