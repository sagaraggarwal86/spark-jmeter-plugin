package com.personal.jmeter.ai.report;

import com.personal.jmeter.ai.prompt.PromptContent;
import com.personal.jmeter.ai.provider.AiProviderConfig;
import com.personal.jmeter.ai.provider.AiProviderRegistry;
import com.personal.jmeter.ai.provider.AiReportService;
import com.personal.jmeter.ai.provider.AiServiceException;
import com.personal.jmeter.parser.JTLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Orchestrates the AI performance report workflow on a background thread.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Call the AI provider API via {@link AiReportService}.</li>
 *   <li>Render the HTML report via {@link HtmlReportRenderer}.</li>
 *   <li>Update the Swing progress dialog and re-enable the trigger button on the EDT.</li>
 * </ol>
 *
 * <p>The analysis prompt ({@link PromptContent}) is built by the caller on the Swing
 * EDT — the same thread that wrote the {@code SamplingStatCalculator} values — before
 * being passed to {@link #start}. This guarantees JMM visibility with no cross-thread
 * access to mutable objects.</p>
 *
 * <p>All dependencies are constructor-injected, making this class independently
 * unit testable without a database, file-system, or live network connection.</p>
 * @since 4.6.0
 */
public class AiReportCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AiReportCoordinator.class);

    private final AiReportService aiService;
    private final HtmlReportRenderer renderer;
    private final ExecutorService executor;

    /**
     * Constructs the coordinator with all required collaborators.
     *
     * @param aiService the AI API client; must not be null
     * @param renderer  the HTML report renderer; must not be null
     * @param executor  the background executor for the AI call; must not be null
     */
    public AiReportCoordinator(AiReportService aiService,
                               HtmlReportRenderer renderer,
                               ExecutorService executor) {
        this.aiService = Objects.requireNonNull(aiService, "aiService must not be null");
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    private static void openInBrowser(String htmlPath) {
        if (!Desktop.isDesktopSupported()) {
            log.info("openInBrowser: Desktop API not supported on this platform — skipping.");
            return;
        }
        try {
            Desktop.getDesktop().browse(new File(htmlPath).toURI());
        } catch (IOException | UnsupportedOperationException ex) {
            log.warn("openInBrowser: could not open default browser. reason={}", ex.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private workflow
    // ─────────────────────────────────────────────────────────────

    private static void setProgress(JLabel label, String text) {
        SwingUtilities.invokeLater(() -> label.setText(text));
    }

    /**
     * Shows a save-file dialog on the EDT so the user chooses where to save the report.
     * Blocks the calling background thread until the user responds.
     *
     * @param suggestedName suggested filename (no directory component)
     * @param startDir      initial directory for the dialog
     * @return user-chosen absolute path, or {@code null} if the user cancelled
     * @throws IOException if the EDT invocation is interrupted
     */
    private static String promptForSavePath(String suggestedName, File startDir)
            throws IOException {
        final String[] result = {null};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser fc = new JFileChooser(startDir);
                fc.setDialogTitle("Save AI Performance Report");
                fc.setSelectedFile(new File(suggestedName));
                fc.setFileFilter(new FileNameExtensionFilter("HTML Files (*.html)", "html"));
                if (fc.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                    File chosen = fc.getSelectedFile();
                    if (!chosen.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".html")) {
                        chosen = new File(chosen.getAbsolutePath() + ".html");
                    }
                    result[0] = chosen.getAbsolutePath();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Save dialog interrupted", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            String msg = (cause != null) ? cause.getMessage() : e.getMessage();
            throw new IOException("Save dialog failed: " + msg, e);
        }
        return result[0];
    }

    /**
     * Builds a suggested filename for the AI report (no directory component).
     *
     * <p>Format: {@code JAAR_<AIName>_Report_<yyyyMMddHHmmss>.html}<br>
     * Example: {@code JAAR_Groq_Report_20260315143022.html}</p>
     *
     * @param providerDisplayName display name of the AI provider (e.g. "Groq (Free)")
     * @return suggested filename
     */
    private static String deriveSuggestedFileName(String providerDisplayName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        // Strip parenthetical tier suffix — "Groq (Free)" → "Groq", "OpenAI (Paid)" → "OpenAI"
        String baseName = (providerDisplayName != null)
                ? providerDisplayName.replaceAll("\\s*\\(.*\\)\\s*$", "").trim()
                : "";
        String providerPart = sanitizeSegment(baseName);
        if (providerPart.isEmpty()) providerPart = "AI";
        return "JAAR_" + providerPart + "_Report_" + timestamp + ".html";
    }

    private static String sanitizeSegment(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw.trim()
                .replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    // ─────────────────────────────────────────────────────────────
    // Save dialog and path helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Submits the AI report workflow to the background executor.
     * Progress is reported via {@code progressLabel}; {@code triggerBtn} is
     * re-enabled and {@code progressDialog} is disposed when the task completes.
     *
     * <p>{@code prompt} must be built by the caller on the Swing EDT before invoking
     * this method. The resulting {@link PromptContent} is an immutable record
     * (two {@code final} Strings) and is safe to hand off to the background executor.</p>
     *
     * @param prompt         pre-built analysis prompt; must not be null
     * @param context        immutable snapshot of all data needed by the workflow
     * @param progressDialog modal-less progress dialog shown while the task runs
     * @param progressLabel  label inside the dialog updated with status messages
     * @param triggerBtn     the button that started the workflow (re-enabled on completion)
     */
    public void start(PromptContent prompt,
                      ReportContext context,
                      JDialog progressDialog,
                      JLabel progressLabel,
                      JButton triggerBtn) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(progressDialog, "progressDialog must not be null");
        Objects.requireNonNull(progressLabel, "progressLabel must not be null");
        Objects.requireNonNull(triggerBtn, "triggerBtn must not be null");

        executor.submit(() -> executeReport(prompt, context, progressDialog, progressLabel, triggerBtn));
    }

    private void executeReport(PromptContent prompt,
                               ReportContext ctx,
                               JDialog progressDialog,
                               JLabel progressLabel,
                               JButton triggerBtn) {
        try {
            setProgress(progressLabel, "Calling " + ctx.providerDisplayName + " (this may take ~30 seconds)...");
            String markdown = aiService.generateReport(prompt);

            // Normalise section headings — inject any structurally missing headings
            // (e.g. Cerebras consistently omits ## Executive Summary).
            // Must run before stripVerdictLine so heading injection does not
            // interfere with token stripping.
            markdown = MarkdownSectionNormaliser.normalise(markdown);

            // Strip the machine verdict token (e.g. "VERDICT:FAIL") before rendering.
            // This token is a CLI exit-code signal and must never appear as visible
            // text in the HTML report. In CLI mode CliReportPipeline does this via
            // MarkdownUtils; here we mirror the same step for the UI path.
            String verdict = MarkdownUtils.extractVerdict(markdown);
            String verdictSource = MarkdownUtils.verdictSource(markdown);
            log.info("executeReport: verdict={} source={} provider={}",
                    verdict, verdictSource, ctx.providerConfig.providerKey);
            String strippedMarkdown = MarkdownUtils.stripVerdictLine(markdown);

            setProgress(progressLabel, "Rendering HTML report...");
            String htmlPath = renderReport(ctx, strippedMarkdown);

            SwingUtilities.invokeLater(() -> onSuccess(htmlPath, progressDialog, triggerBtn));

        } catch (IOException ex) {
            // Evict the ping cache when the provider rejects the request with an auth error
            // (HTTP 401 = key rejected, HTTP 403 = access denied / quota exceeded).
            // This forces a fresh live ping on the next attempt instead of hitting the stale
            // cached-success entry — which would otherwise bypass the ping indefinitely.
            if (ex instanceof AiServiceException
                    && (ex.getMessage().contains("HTTP 401") || ex.getMessage().contains("HTTP 403"))) {
                log.warn("executeReport: auth failure from provider — evicting ping cache. provider={}",
                        ctx.providerConfig.providerKey);
                AiProviderRegistry.evictPingCache(ctx.providerConfig);
            }
            log.error("executeReport: AI report generation failed. reason={}", ex.getMessage(), ex);
            SwingUtilities.invokeLater(() -> onFailure(ex, progressDialog, triggerBtn));
        } catch (RuntimeException ex) {
            log.error("executeReport: unexpected error during report generation. reason={}", ex.getMessage(), ex);
            SwingUtilities.invokeLater(() -> onFailure(
                    new IOException("Unexpected error during report generation. "
                            + "Check the log for details. reason=" + ex.getMessage(), ex),
                    progressDialog, triggerBtn));
        }
    }

    private String renderReport(ReportContext ctx, String markdown) throws IOException {
        String suggestedName = deriveSuggestedFileName(ctx.providerDisplayName);
        File startDir = Path.of(ctx.jtlPath).toAbsolutePath().getParent() != null
                ? Path.of(ctx.jtlPath).toAbsolutePath().getParent().toFile()
                : new File(System.getProperty("user.dir"));

        String outPath = promptForSavePath(suggestedName, startDir);
        if (outPath == null) {
            throw new IOException("Report save cancelled by user.");
        }

        return renderer.renderToFile(markdown, outPath, ctx.config, ctx.tableRows, ctx.timeBuckets);
    }

    private void onSuccess(String htmlPath, JDialog progressDialog, JButton triggerBtn) {
        progressDialog.dispose();
        triggerBtn.setEnabled(true);
        openInBrowser(htmlPath);
    }

    private void onFailure(IOException ex, JDialog progressDialog, JButton triggerBtn) {
        progressDialog.dispose();
        triggerBtn.setEnabled(true);
        JOptionPane.showMessageDialog(triggerBtn.getParent(),
                "Report generation failed:\n\n" + ex.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ─────────────────────────────────────────────────────────────
    // Immutable context record (value object)
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of all data the AI report workflow needs.
     * Built on the Swing EDT; handed to the background thread safely because
     * all fields are final and all collections are unmodifiable copies.
     */
    public static final class ReportContext {
        /**
         * Visible table rows as strings (TOTAL excluded).
         */
        public final List<String[]> tableRows;
        /**
         * Ordered list of time buckets for the charts section.
         */
        public final List<JTLParser.TimeBucket> timeBuckets;
        /**
         * Render metadata for the HTML template.
         */
        public final HtmlReportRenderer.RenderConfig config;
        /**
         * Absolute path to the source JTL file.
         */
        public final String jtlPath;
        /**
         * Human-readable name of the selected AI provider (e.g. "Groq (Free)").
         */
        public final String providerDisplayName;
        /**
         * The AI provider configuration used for this report.
         * Held so {@code executeReport} can evict the ping cache when the provider
         * rejects the request with a 401/403 after a cached-ping bypass.
         */
        public final AiProviderConfig providerConfig;

        /**
         * Constructs the report context.
         *
         * @param tableRows           visible table rows as strings (TOTAL excluded)
         * @param timeBuckets         ordered list of time buckets
         * @param config              render metadata for the HTML template
         * @param jtlPath             absolute path to the source JTL file
         * @param providerDisplayName human-readable AI provider name; null → "AI Provider"
         * @param providerConfig      AI provider config for ping-cache eviction; must not be null
         */
        public ReportContext(List<String[]> tableRows,
                             List<JTLParser.TimeBucket> timeBuckets,
                             HtmlReportRenderer.RenderConfig config,
                             String jtlPath,
                             String providerDisplayName,
                             AiProviderConfig providerConfig) {
            this.tableRows = Objects.requireNonNull(tableRows, "tableRows must not be null");
            this.timeBuckets = Objects.requireNonNull(timeBuckets, "timeBuckets must not be null");
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.jtlPath = Objects.requireNonNull(jtlPath, "jtlPath must not be null");
            this.providerConfig = Objects.requireNonNull(providerConfig, "providerConfig must not be null");
            this.providerDisplayName = providerDisplayName != null ? providerDisplayName : "AI Provider";
        }
    }
}