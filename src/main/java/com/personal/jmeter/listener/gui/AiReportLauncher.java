package com.personal.jmeter.listener.gui;

import com.personal.jmeter.ai.prompt.PromptBuilder;
import com.personal.jmeter.ai.prompt.PromptContent;
import com.personal.jmeter.ai.prompt.PromptLoader;
import com.personal.jmeter.ai.prompt.PromptRequest;
import com.personal.jmeter.ai.provider.AiProviderConfig;
import com.personal.jmeter.ai.provider.AiProviderRegistry;
import com.personal.jmeter.ai.provider.AiReportService;
import com.personal.jmeter.ai.report.AiReportCoordinator;
import com.personal.jmeter.ai.report.HtmlReportRenderer;
import com.personal.jmeter.listener.core.ScenarioMetadata;
import com.personal.jmeter.listener.core.SlaConfig;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter; // CHANGED
import java.awt.event.WindowEvent;   // CHANGED
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;    // CHANGED
import java.util.concurrent.atomic.AtomicReference;  // CHANGED

/**
 * Handles the AI report generation workflow initiated from the panel's button.
 *
 * <p>Extracted from {@code AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: provider validation, prompt
 * loading, context building, progress dialog management, and coordinator wiring.</p>
 *
 * <p>All data needed to build the report is supplied via constructor injection
 * and a {@link DataProvider} callback, keeping this class independently testable.</p>
 *
 * <p>Closing the progress dialog cancels the in-flight background task: the shared
 * {@code cancelled} flag is set, the background thread is interrupted, and the dialog
 * is disposed with the trigger button re-enabled. No error dialog is shown on
 * cancellation.</p>
 */
public final class AiReportLauncher {

    private static final Logger log = LoggerFactory.getLogger(AiReportLauncher.class);

    private static final int PROGRESS_DIALOG_WIDTH = 340;
    private static final int PROGRESS_DIALOG_HEIGHT = 90;

    private final JComponent parent;
    private final ExecutorService executor;
    private final DataProvider dataProvider;

    /**
     * Constructs the launcher.
     *
     * @param parent       the Swing parent for dialogs; must not be null
     * @param executor     background thread executor; must not be null
     * @param dataProvider live data callback; must not be null
     */
    public AiReportLauncher(JComponent parent, ExecutorService executor, DataProvider dataProvider) {
        this.parent = parent;
        this.executor = executor;
        this.dataProvider = dataProvider;
    }

    /**
     * Returns the JMeter home directory, or {@code null} if not set.
     * Uses the {@code JMETER_HOME} system property or environment variable.
     * Package-accessible so {@link AggregateReportPanel} can use it for provider loading.
     */
    static java.io.File resolveJmeterHomeStatic() {
        return resolveJmeterHome();
    }

    /**
     * Returns the JMeter home directory, or {@code null} if not set.
     * Uses the {@code JMETER_HOME} system property or environment variable.
     */
    private static java.io.File resolveJmeterHome() {
        String home = System.getProperty("jmeter.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("JMETER_HOME");
        }
        if (home == null || home.isBlank()) return null;
        java.io.File f = new java.io.File(home);
        return f.isDirectory() ? f : null;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Validates provider selection and loads the prompt on the EDT, then immediately
     * disables the trigger button and shows the progress dialog before submitting work
     * to the background executor. API key validation and the live ping are performed
     * off the EDT so the UI remains responsive throughout.
     * Re-enables the button and disposes the dialog on both success, failure, and cancellation.
     *
     * <p>Closing the progress dialog cancels the in-flight task: the background thread
     * is interrupted and no error dialog is shown.</p>
     *
     * @param triggerBtn the button that initiated the workflow (re-enabled on completion)
     */
    void launch(JButton triggerBtn) {
        if (dataProvider.getCachedResults().isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "No data available. Please load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        AiProviderConfig providerConfig = dataProvider.getSelectedProvider();
        if (providerConfig == null) {
            JOptionPane.showMessageDialog(parent,
                    "<html>No AI provider configured.<br><br>"
                            + "Add your API key to:<br>"
                            + "&nbsp;&nbsp;<tt>$JMETER_HOME/bin/ai-reporter.properties</tt><br><br>"
                            + "Set at least one provider's <tt>api.key</tt> to enable this feature.</html>",
                    "No Provider Configured", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String systemPrompt = PromptLoader.load();
        if (systemPrompt == null) {
            JOptionPane.showMessageDialog(parent,
                    "<html><b>Could not load AI prompt.</b><br><br>"
                            + "The bundled prompt resource is missing or empty.<br>"
                            + "Please verify the plugin JAR is intact and reinstall if needed.</html>",
                    "Prompt Resource Missing", JOptionPane.ERROR_MESSAGE);
            return;
        }

        AiReportCoordinator.ReportContext context = buildReportContext(providerConfig);

        // Build prompt on the EDT — same thread that wrote the SamplingStatCalculator values.
        // This guarantees JMM visibility: no mutable calculator references cross the
        // thread boundary. The resulting PromptContent is an immutable record (two
        // final Strings) and is safe to hand off to the background executor.
        PromptContent prompt = buildPromptOnEdt(systemPrompt, providerConfig.displayName);

        // CHANGED: shared cancellation state — flag + background thread reference
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Thread> bgThread = new AtomicReference<>();

        JDialog progressDialog = buildProgressDialog(triggerBtn, cancelled, bgThread); // CHANGED
        JLabel progressLabel = extractProgressLabel(progressDialog);
        progressDialog.setVisible(true);
        triggerBtn.setEnabled(false);

        // CHANGED: executor removed from coordinator constructor
        AiReportCoordinator coordinator = new AiReportCoordinator(
                new AiReportService(providerConfig),
                new HtmlReportRenderer());

        executor.submit(() -> {
            bgThread.set(Thread.currentThread()); // CHANGED: register thread for interrupt

            // CHANGED: handle close clicked before thread actually started
            if (cancelled.get()) return;

            try {
                SwingUtilities.invokeLater(() -> progressLabel.setText("Validating API key..."));
                String pingError = AiProviderRegistry.validateAndPing(providerConfig);
                if (pingError != null) {
                    SwingUtilities.invokeLater(() -> {
                        progressDialog.dispose();
                        triggerBtn.setEnabled(true);
                        if (!cancelled.get()) { // CHANGED: skip error dialog on cancel
                            JOptionPane.showMessageDialog(parent,
                                    "<html><b>Cannot connect to " + providerConfig.displayName + ".</b><br><br>"
                                            + pingError.replace("\n", "<br>") + "</html>",
                                    "Provider Validation Failed", JOptionPane.ERROR_MESSAGE);
                        }
                    });
                    return;
                }
                // CHANGED: start() runs directly on this thread; passes cancelled for clean shutdown
                coordinator.start(prompt, context, progressDialog, progressLabel, triggerBtn, cancelled);
            } catch (RuntimeException ex) {
                log.error("launch: unexpected error during provider validation. reason={}", ex.getMessage(), ex);
                SwingUtilities.invokeLater(() -> {
                    progressDialog.dispose();
                    triggerBtn.setEnabled(true);
                    if (!cancelled.get()) { // CHANGED: skip error dialog on cancel
                        JOptionPane.showMessageDialog(parent,
                                "Unexpected error during report generation:\n\n" + ex.getMessage(),
                                "Unexpected Error", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        });
    }

    /**
     * Builds the AI analysis prompt on the Swing EDT.
     *
     * <p>Must be called on the EDT — the same thread that wrote the
     * {@code SamplingStatCalculator} values — to guarantee JMM visibility.
     * The returned {@link PromptContent} is an immutable record safe to
     * pass across the thread boundary to the background executor.</p>
     *
     * @param systemPrompt        loaded system prompt text
     * @param providerDisplayName display name of the selected provider
     * @return immutable prompt content ready for the AI API call
     */
    private PromptContent buildPromptOnEdt(String systemPrompt, String providerDisplayName) {
        final ScenarioMetadata metadata = dataProvider.getMetadata();
        final int percentile = dataProvider.getPercentile();
        final SlaConfig sla = dataProvider.getSlaConfig();

        final String slaErrorPct = sla.isErrorPctEnabled()
                ? sla.errorPctThreshold + "%" : "Not configured";
        final String slaRtMs = sla.isRtEnabled()
                ? sla.rtThresholdMs + " ms" : "Not configured";
        final String slaRtMetric = sla.rtMetric == SlaConfig.RtMetric.AVG
                ? "Avg (ms)" : "P" + percentile + " (ms)";

        PromptRequest request = new PromptRequest(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                dataProvider.getStartTime(), dataProvider.getEndTime(),
                dataProvider.getDuration(), metadata.threadGroupName,
                percentile, slaErrorPct, slaRtMs, slaRtMetric);

        PromptBuilder.LatencyContext latency = new PromptBuilder.LatencyContext(
                dataProvider.getAvgLatencyMs(), dataProvider.getAvgConnectMs(),
                dataProvider.isLatencyPresent());

        return new PromptBuilder(systemPrompt).build(
                dataProvider.getCachedResults(), percentile, request,
                dataProvider.getErrorTypeSummary(), latency,
                dataProvider.getCachedBuckets());
    }

    private AiReportCoordinator.ReportContext buildReportContext(AiProviderConfig providerConfig) {
        final ScenarioMetadata metadata = dataProvider.getMetadata();
        final int percentile = dataProvider.getPercentile();
        final SlaConfig sla = dataProvider.getSlaConfig();

        double errorSla = sla.isErrorPctEnabled() ? sla.errorPctThreshold : -1.0;
        long rtSla = sla.isRtEnabled() ? sla.rtThresholdMs : -1L;
        String rtMetric = sla.rtMetric == SlaConfig.RtMetric.AVG
                ? "avg" : "pnn";

        final HtmlReportRenderer.RenderConfig config = new HtmlReportRenderer.RenderConfig(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                metadata.threadGroupName,
                dataProvider.getStartTime(), dataProvider.getEndTime(),
                dataProvider.getDuration(), percentile, providerConfig.displayName,
                errorSla, rtSla, rtMetric);

        return new AiReportCoordinator.ReportContext(
                dataProvider.getVisibleTableRows(),
                List.copyOf(dataProvider.getCachedBuckets()),
                config,
                dataProvider.getLastLoadedFilePath(),
                providerConfig.displayName,
                providerConfig);
    }

    // CHANGED: accepts cancelled + bgThread to wire the close listener
    private JDialog buildProgressDialog(JButton triggerBtn,
                                        AtomicBoolean cancelled,
                                        AtomicReference<Thread> bgThread) {
        Window parentWindow = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(parentWindow, "Generating AI Report",
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        // CHANGED: close listener — sets flag, interrupts thread, cleans up UI
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancelled.set(true);
                Thread t = bgThread.get();
                if (t != null) t.interrupt();
                dialog.dispose();
                triggerBtn.setEnabled(true);
                log.info("buildProgressDialog: report generation cancelled by user.");
            }
        });

        JLabel label = new JLabel("Initialising...");
        label.setFont(AggregateReportPanel.FONT_REGULAR);
        label.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        dialog.add(label, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(PROGRESS_DIALOG_WIDTH, PROGRESS_DIALOG_HEIGHT));
        dialog.setLocationRelativeTo(parent);
        return dialog;
    }

    private JLabel extractProgressLabel(JDialog dialog) {
        return (JLabel) ((BorderLayout) dialog.getContentPane().getLayout())
                .getLayoutComponent(BorderLayout.CENTER);
    }

    /**
     * Callback interface that supplies live data from the parent panel.
     * Decouples {@link AiReportLauncher} from the panel's private fields.
     */
    public interface DataProvider {
        /**
         * Returns a snapshot of the cached aggregated results.
         */
        Map<String, SamplingStatCalculator> getCachedResults();

        /**
         * Returns the visible (filtered + sorted) table rows, TOTAL excluded.
         */
        List<String[]> getVisibleTableRows();

        /**
         * Returns the cached time buckets.
         */
        List<JTLParser.TimeBucket> getCachedBuckets();

        /**
         * Returns the absolute path of the last loaded JTL file.
         */
        String getLastLoadedFilePath();

        /**
         * Returns the current scenario metadata.
         */
        ScenarioMetadata getMetadata();

        /**
         * Returns the currently configured percentile (1–99).
         */
        int getPercentile();

        /**
         * Returns the formatted start time string.
         */
        String getStartTime();

        /**
         * Returns the formatted end time string.
         */
        String getEndTime();

        /**
         * Returns the formatted duration string.
         */
        String getDuration();

        /**
         * Returns the provider selected in the UI dropdown, or {@code null} if none.
         */
        AiProviderConfig getSelectedProvider();

        /**
         * Returns the current SLA configuration snapshot from the UI fields.
         * Never null — returns a disabled SlaConfig if no thresholds are set.
         */
        SlaConfig getSlaConfig();

        /**
         * Returns the top-5 error type summary from the last parsed JTL.
         * Empty list when no failures occurred.
         */
        List<Map<String, Object>> getErrorTypeSummary();

        /**
         * Returns the average Latency (TTFB) in ms from the last parsed JTL.
         * Zero when {@link #isLatencyPresent()} is false.
         */
        long getAvgLatencyMs();

        /**
         * Returns the average Connect time in ms from the last parsed JTL.
         * Zero when {@link #isLatencyPresent()} is false.
         */
        long getAvgConnectMs();

        /**
         * Returns {@code true} when the last parsed JTL contained at least one
         * non-zero Latency value.  Drives the direct vs inferred mode in the
         * Advanced Web Diagnostics section of the AI prompt.
         */
        boolean isLatencyPresent();
    }
}