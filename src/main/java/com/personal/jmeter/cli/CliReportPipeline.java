package com.personal.jmeter.cli;

import com.personal.jmeter.ai.*;
import com.personal.jmeter.listener.TransactionFilter;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Headless pipeline: parse JTL → build prompt → call AI → render HTML.
 *
 * <p>Reuses the project's existing parser, prompt builder, AI service,
 * and renderer without any Swing dependency. Progress messages are written
 * to {@code System.err} so {@code stdout} stays clean for piping.</p>
 */
final class CliReportPipeline {

    private static final String TOTAL_LABEL = "TOTAL";
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");
    private static final DecimalFormat FMT_INT    = new DecimalFormat("#");
    private static final DecimalFormat FMT_ONE_DP = new DecimalFormat("0.0");
    private static final DecimalFormat FMT_TWO_DP = new DecimalFormat("0.00");

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
     * @return the absolute path of the generated HTML report
     * @throws IOException on parse, AI, or write failure
     */
    String execute() throws IOException {

        // Step 1 — Parse JTL
        progress("Parsing JTL file: " + args.inputFile());
        JTLParser.FilterOptions opts = buildFilterOptions();
        JTLParser.ParseResult result = new JTLParser().parse(args.inputFile(), opts);
        progress("Parsed %d transaction types, %d total samples.",
                Math.max(0, result.results.size() - 1),
                result.results.containsKey(TOTAL_LABEL)
                        ? result.results.get(TOTAL_LABEL).getCount() : 0);

        // Step 2 — Build table rows
        List<String[]> tableRows = buildTableRows(result, opts.percentile);
        progress("Built %d table rows.", tableRows.size());

        // Step 3 — Resolve AI provider
        progress("Loading provider configuration from: " + args.configFile());
        AiProviderConfig provider = resolveProvider();
        progress("Provider: %s (model: %s)", provider.displayName, provider.model);

        // Step 4 — Validate and ping
        progress("Validating API key and pinging %s...", provider.displayName);
        String pingError = AiProviderRegistry.validateAndPing(provider);
        if (pingError != null) {
            throw new IOException("Provider validation failed for " + provider.displayName
                    + ":\n" + pingError);
        }
        progress("Ping successful.");

        // Step 5 — Load prompt and build content
        progress("Building analysis prompt...");
        String systemPrompt = PromptLoader.load();
        if (systemPrompt == null) {
            throw new IOException("Bundled prompt resource not found. Plugin JAR may be corrupt.");
        }
        PromptContent prompt = buildPromptContent(result, systemPrompt);

        // Step 6 — Call AI
        progress("Calling %s (this may take 30-60 seconds)...", provider.displayName);
        AiReportService service = new AiReportService(provider);
        String markdown = service.generateReport(prompt);
        progress("AI response received (%d characters).", markdown.length());

        // Step 7 — Render HTML
        progress("Rendering HTML report...");
        HtmlReportRenderer.RenderConfig config = buildRenderConfig(result);
        String outputPath = new HtmlReportRenderer().renderToFile(
                markdown, args.outputFile(), config, tableRows, result.timeBuckets);
        progress("Report saved to: " + outputPath);

        return outputPath;
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
        return opts;
    }

    // ─────────────────────────────────────────────────────────────
    // Table row building (mirrors TablePopulator.buildRow)
    // ─────────────────────────────────────────────────────────────

    private List<String[]> buildTableRows(JTLParser.ParseResult result, int percentile) {
        double pFraction = percentile / 100.0;
        List<String[]> rows = new ArrayList<>();

        for (SamplingStatCalculator calc : result.results.values()) {
            if (calc.getCount() == 0) continue;
            String label = calc.getLabel();
            if (TOTAL_LABEL.equals(label)) continue;

            // Apply search filter if specified
            if (!args.search().isBlank()
                    && !TransactionFilter.matches(label, args.search(), args.regex())) {
                continue;
            }

            rows.add(buildRow(calc, pFraction));
        }
        return rows;
    }

    private String[] buildRow(SamplingStatCalculator calc, double pFraction) {
        long total  = calc.getCount();
        long failed = Math.round(calc.getErrorPercentage() * total);
        return new String[]{
                calc.getLabel(),
                String.valueOf(total),
                String.valueOf(total - failed),
                String.valueOf(failed),
                FMT_INT.format(calc.getMean()),
                String.valueOf(calc.getMin().intValue()),
                String.valueOf(calc.getMax().intValue()),
                FMT_INT.format(calc.getPercentPoint(pFraction).doubleValue()),
                FMT_ONE_DP.format(calc.getStandardDeviation()),
                FMT_TWO_DP.format(calc.getErrorPercentage() * 100.0) + "%",
                String.format("%.1f/sec", calc.getRate())
        };
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
                .orElseThrow(() -> new IOException(
                        "Provider '" + args.provider() + "' not found in " + args.configFile()
                                + ".\nConfigured providers: "
                                + (providers.isEmpty() ? "(none)"
                                : String.join(", ", providers.stream()
                                .map(p -> p.providerKey).toList()))));
    }

    // ─────────────────────────────────────────────────────────────
    // Prompt content
    // ─────────────────────────────────────────────────────────────

    private PromptContent buildPromptContent(JTLParser.ParseResult result, String systemPrompt) {
        String startTime  = result.startTimeMs > 0 ? formatMs(result.startTimeMs) : "";
        String endTime    = result.endTimeMs   > 0 ? formatMs(result.endTimeMs)   : "";
        String duration   = result.durationMs  > 0 ? formatDuration(result.durationMs) : "";
        String users      = args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "";

        String slaErrorPct = args.hasErrorSla()
                ? args.errorSla() + "%" : "Not configured";
        String slaRtMs = args.hasRtSla()
                ? args.rtSla() + "ms" : "Not configured";
        String slaRtMetric = "percentile".equals(args.rtMetric())
                ? "P" + args.percentile() + " (ms)" : "Avg (ms)";

        PromptRequest request = new PromptRequest(
                users,
                args.scenarioName(),
                args.description(),
                startTime,
                endTime,
                duration,
                "",  // threadGroupName — not available from CLI
                args.percentile(),
                slaErrorPct,
                slaRtMs,
                slaRtMetric);

        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
                result.avgLatencyMs, result.avgConnectMs, result.latencyPresent);

        return new PromptBuilder(systemPrompt)
                .build(result.results, args.percentile(), request,
                        result.errorTypeSummary, latency);
    }

    // ─────────────────────────────────────────────────────────────
    // Render config
    // ─────────────────────────────────────────────────────────────

    private HtmlReportRenderer.RenderConfig buildRenderConfig(JTLParser.ParseResult result) {
        String startTime = result.startTimeMs > 0 ? formatMs(result.startTimeMs) : "";
        String endTime   = result.endTimeMs   > 0 ? formatMs(result.endTimeMs)   : "";
        String duration  = result.durationMs  > 0 ? formatDuration(result.durationMs) : "";
        String users     = args.virtualUsers() > 0 ? String.valueOf(args.virtualUsers()) : "";

        return new HtmlReportRenderer.RenderConfig(
                users,
                args.scenarioName(),
                args.description(),
                "",  // threadGroupName
                startTime,
                endTime,
                duration,
                args.percentile());
    }

    // ─────────────────────────────────────────────────────────────
    // Formatting helpers
    // ─────────────────────────────────────────────────────────────

    private static String formatMs(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DISPLAY_TIME);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return String.format("%dh %dm %ds", s / 3600, (s % 3600) / 60, s % 60);
    }

    private void progress(String format, Object... params) {
        progress.println("[CLI] " + String.format(format, params));
    }
}
