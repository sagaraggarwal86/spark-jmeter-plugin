package io.github.sagaraggarwal86.jmeter.listener.gui;

import io.github.sagaraggarwal86.jmeter.listener.core.ScenarioMetadata;
import io.github.sagaraggarwal86.jmeter.parser.JTLParser;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.util.Enumeration;

/**
 * JMeter listener plugin — JAAR (JTL AI Analysis &amp; Reporting).
 *
 * <p>This class handles JMeter-specific integration only:
 * lifecycle callbacks ({@code configure}, {@code modifyTestElement}, {@code clearGui}),
 * hooking the built-in FilePanel (via {@link FilePanelCustomizer}), and reading
 * test-plan metadata for the AI report.
 * All shared UI and business logic lives in {@link AggregateReportPanel}.</p>
 */
public class ListenerGUI extends AbstractVisualizer {

    private static final Logger log = LoggerFactory.getLogger(ListenerGUI.class);

    private final AggregateReportPanel reportPanel = new AggregateReportPanel();
    /**
     * Tracks file paths for which a "file not found" warning has already been
     * shown in this JMeter session. Prevents repeated dialogs on every tree click.
     * Entry is removed when the user successfully reloads the file via Browse.
     */
    private final java.util.Set<String> missingFileWarned = new java.util.HashSet<>();
    /**
     * Set to {@code true} inside {@link #modifyTestElement} so that the
     * subsequent {@link #clearGui()} call triggered by JMeter tree navigation
     * can be distinguished from a user-initiated Clear All action.
     */
    private boolean isTreeNavigation = false;

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    /**
     * Constructs the listener GUI and wires up JMeter integration.
     */
    public ListenerGUI() {
        super();
        initComponents();
        reportPanel.setMetadataSupplier(this::readTestPlanMetadata);
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    // ─────────────────────────────────────────────────────────────
    // AbstractVisualizer contract
    // ─────────────────────────────────────────────────────────────

    private void initComponents() {
        setLayout(new BorderLayout());

        // AbstractVisualizer's standard title / file-browse panel
        Container titlePanel = makeTitlePanel();
        add(titlePanel, BorderLayout.NORTH);

        // Inject "Help on this plugin" link into the title panel
        JLabel helpLink = new JLabel(
            "<html><a href=''>&#x2139; Help on this plugin</a></html>");
        helpLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpLink.setFont(AggregateReportPanel.FONT_REGULAR);
        helpLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(ListenerCollector.HELP_URL));
                } catch (Exception ex) {
                    log.warn("helpLink: could not open browser. reason={}", ex.getMessage());
                }
            }
        });
        titlePanel.add(helpLink);

        // Customise the built-in FilePanel: hide irrelevant controls and
        // override Browse so it opens a file chooser starting in the current
        // file's directory, then immediately loads the selected file.
        FilePanelCustomizer.hideFilePanelExtras(titlePanel);
        FilePanelCustomizer.overrideBrowseButton(
            titlePanel, this::getFile, this::setFile, this, this::checkAndLoadFile);
        FilePanelCustomizer.wireEnterKeyOnFilenameField(titlePanel, this::checkAndLoadFile);

        add(reportPanel, BorderLayout.CENTER);
    }

    /**
     * Called immediately after the user selects a file via Browse.
     * Shows an error dialog if the selected file no longer exists on disk.
     * On success, removes the path from {@link #missingFileWarned} so that
     * a future missing-file warning can fire again if needed, then delegates
     * to {@link AggregateReportPanel#loadJtlFile(String)}.
     */
    private void checkAndLoadFile() {
        String filename = getFile();
        if (filename == null || filename.trim().isEmpty()) {
            return;
        }
        if (!new File(filename.trim()).exists()) {
            JOptionPane.showMessageDialog(this,
                "File not found:\n" + filename.trim(),
                "File Not Found", JOptionPane.ERROR_MESSAGE);
            return;
        }
        missingFileWarned.remove(filename.trim());
        reportPanel.loadJtlFile(filename.trim());
    }

    @Override
    public String getLabelResource() {
        return "jaar_ai_performance_reporter";
    }

    @Override
    public String getStaticLabel() {
        return ListenerCollector.PLUGIN_NAME;
    }

    @Override
    public TestElement createTestElement() {
        ListenerCollector collector = new ListenerCollector();
        modifyTestElement(collector);
        return collector;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        isTreeNavigation = true;
        super.modifyTestElement(el);
        // ── Filter fields ────────────────────────────────────────
        el.setProperty(ListenerCollector.PROP_START_OFFSET, reportPanel.getStartOffset());
        el.setProperty(ListenerCollector.PROP_END_OFFSET, reportPanel.getEndOffset());
        el.setProperty(ListenerCollector.PROP_PERCENTILE, reportPanel.getPercentileText());
        // ── SLA fields ───────────────────────────────────────────
        el.setProperty(ListenerCollector.PROP_TPS_SLA, reportPanel.getTpsSla());
        el.setProperty(ListenerCollector.PROP_ERROR_PCT_SLA, reportPanel.getErrorPctSla());
        el.setProperty(ListenerCollector.PROP_RT_THRESHOLD_SLA, reportPanel.getRtThresholdSla());
        el.setProperty(ListenerCollector.PROP_RT_METRIC, reportPanel.getRtMetricIndex());
        // ── Chart / search fields ────────────────────────────────
        el.setProperty(ListenerCollector.PROP_CHART_INTERVAL, reportPanel.getChartInterval());
        el.setProperty(ListenerCollector.PROP_SEARCH, reportPanel.getSearch());
        el.setProperty(ListenerCollector.PROP_REGEX, reportPanel.isRegex());
        el.setProperty(ListenerCollector.PROP_FILTER_MODE, reportPanel.getFilterModeIndex());
        // ── File and column state ────────────────────────────────
        String lastFile = reportPanel.getLastLoadedFilePath();
        if (lastFile != null) el.setProperty(ListenerCollector.PROP_LAST_FILE, lastFile);
        el.setProperty(ListenerCollector.PROP_COL_VISIBILITY, reportPanel.getColumnVisibility());
    }

    @Override
    public void configure(TestElement el) {
        isTreeNavigation = false;

        reportPanel.setSuppressReload(true);
        try {
            super.configure(el);
            // ── Filter fields ────────────────────────────────────
            reportPanel.setStartOffset(
                el.getPropertyAsString(ListenerCollector.PROP_START_OFFSET, ""));
            reportPanel.setEndOffset(
                el.getPropertyAsString(ListenerCollector.PROP_END_OFFSET, ""));
            reportPanel.setPercentile(
                el.getPropertyAsString(ListenerCollector.PROP_PERCENTILE, "90"));
            // ── SLA fields ───────────────────────────────────────
            reportPanel.setTpsSla(
                el.getPropertyAsString(ListenerCollector.PROP_TPS_SLA, ""));
            reportPanel.setErrorPctSla(
                el.getPropertyAsString(ListenerCollector.PROP_ERROR_PCT_SLA, ""));
            reportPanel.setRtThresholdSla(
                el.getPropertyAsString(ListenerCollector.PROP_RT_THRESHOLD_SLA, ""));
            reportPanel.setRtMetricIndex(
                el.getPropertyAsInt(ListenerCollector.PROP_RT_METRIC, 1));
            // ── Chart / search fields ────────────────────────────
            reportPanel.setChartInterval(
                el.getPropertyAsString(ListenerCollector.PROP_CHART_INTERVAL, "0"));
            reportPanel.setSearch(
                el.getPropertyAsString(ListenerCollector.PROP_SEARCH, ""));
            reportPanel.setRegex(
                el.getPropertyAsBoolean(ListenerCollector.PROP_REGEX, false));
            reportPanel.setFilterModeIndex(
                el.getPropertyAsInt(ListenerCollector.PROP_FILTER_MODE, 0));
            // ── Column visibility ────────────────────────────────
            reportPanel.setColumnVisibility(
                el.getPropertyAsString(ListenerCollector.PROP_COL_VISIBILITY, ""));
        } finally {
            reportPanel.setSuppressReload(false);
        }

        String lastFile = el.getPropertyAsString(ListenerCollector.PROP_LAST_FILE, "");
        if (!lastFile.isBlank()) {
            if (!new File(lastFile).exists()) {
                if (!missingFileWarned.contains(lastFile)) {
                    missingFileWarned.add(lastFile);
                    JOptionPane.showMessageDialog(this,
                        "Previously loaded file not found:\n" + lastFile
                            + "\n\nPlease browse for the file again.",
                        "File Not Found", JOptionPane.WARNING_MESSAGE);
                }
            } else {
                reportPanel.loadJtlFileForRestore(lastFile);
            }
        }
    }

    @Override
    public void clearGui() {
        if (isTreeNavigation) {
            isTreeNavigation = false;
            super.clearGui();
            return;
        }
        super.clearGui();
        reportPanel.clearAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Test-plan metadata for AI report
    // ─────────────────────────────────────────────────────────────

    /**
     * No-op: this plugin processes JTL files only — it does not capture live metrics.
     *
     * @param sample ignored
     */
    @Override
    public void add(SampleResult sample) {
        // Intentionally empty
    }

    /**
     * Clears all data, SLA fields, and filter state.
     *
     * <p>Iterates every JAAR listener in the test-plan tree and resets their
     * SLA and filter properties directly on the TestElement so that the
     * cleared state survives tree navigation even when this listener is
     * not the active element.</p>
     */
    @Override
    public void clearData() {
        reportPanel.clearAll();
        clearAllListenerProperties();
    }

    /**
     * Resets SLA and filter properties on every {@link ListenerCollector}
     * in the JMeter test-plan tree.
     */
    private void clearAllListenerProperties() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null) return;
            JMeterTreeNode root = (JMeterTreeNode) gp.getTreeModel().getRoot();
            clearPropertiesRecursive(root);
        } catch (RuntimeException ex) {
            log.warn("clearAllListenerProperties: failed. reason={}", ex.getMessage());
        }
    }

    private void clearPropertiesRecursive(JMeterTreeNode node) {
        TestElement el = node.getTestElement();
        if (el instanceof ListenerCollector) {
            el.setProperty(ListenerCollector.PROP_TPS_SLA, "");
            el.setProperty(ListenerCollector.PROP_ERROR_PCT_SLA, "");
            el.setProperty(ListenerCollector.PROP_RT_THRESHOLD_SLA, "");
            el.setProperty(ListenerCollector.PROP_RT_METRIC, 1); // default: Pnn
            el.setProperty(ListenerCollector.PROP_START_OFFSET, "");
            el.setProperty(ListenerCollector.PROP_END_OFFSET, "");
            el.setProperty(ListenerCollector.PROP_PERCENTILE, "90");
            el.setProperty(ListenerCollector.PROP_SEARCH, "");
            el.setProperty(ListenerCollector.PROP_REGEX, false);
            el.setProperty(ListenerCollector.PROP_FILTER_MODE, 0); // Include
            el.setProperty(ListenerCollector.PROP_CHART_INTERVAL, "0");
            el.setProperty(ListenerCollector.PROP_LAST_FILE, "");
        }
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            clearPropertiesRecursive((JMeterTreeNode) children.nextElement());
        }
    }

    /**
     * Reads scenario name, description, virtual-user count, and first thread-group
     * name from the live JMeter test-plan tree via {@link GuiPackage}.
     * Returns {@link ScenarioMetadata#empty()} on any failure.
     *
     * @return scenario metadata; never null
     */
    private ScenarioMetadata readTestPlanMetadata() {
        try {
            GuiPackage gp = GuiPackage.getInstance();
            if (gp == null) return ScenarioMetadata.empty();

            JMeterTreeNode root = (JMeterTreeNode) gp.getTreeModel().getRoot();
            Enumeration<?> children = root.children();
            while (children.hasMoreElements()) {
                JMeterTreeNode node = (JMeterTreeNode) children.nextElement();
                TestElement el = node.getTestElement();
                if (el instanceof TestPlan) {
                    String scenarioName = trimOrEmpty(el.getName());
                    String scenarioDesc = trimOrEmpty(el.getComment());
                    int threadCount = sumThreadCounts(node);
                    String threadGrpName = readFirstThreadGroupName(node);
                    String users = threadCount > 0 ? String.valueOf(threadCount) : "";
                    return new ScenarioMetadata(scenarioName, scenarioDesc, users, threadGrpName);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("readTestPlanMetadata: could not read test plan info. reason={}", ex.getMessage());
        }
        return ScenarioMetadata.empty();
    }

    private int sumThreadCounts(JMeterTreeNode planNode) {
        int total = 0;
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            if (child.getTestElement() instanceof AbstractThreadGroup atg) {
                total += atg.getNumThreads();
            }
        }
        return total;
    }

    private String readFirstThreadGroupName(JMeterTreeNode planNode) {
        Enumeration<?> children = planNode.children();
        while (children.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) children.nextElement();
            if (child.getTestElement() instanceof AbstractThreadGroup) {
                return trimOrEmpty(child.getTestElement().getName());
            }
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // File loading (public — called from tests and JTL file monitoring)
    // ─────────────────────────────────────────────────────────────

    /**
     * Loads the given JTL file and populates the results table.
     *
     * @param filePath path to the JTL file
     * @return {@code true} on success, {@code false} on parse error
     */
    public boolean loadJTLFile(String filePath) {
        return reportPanel.loadJtlFile(filePath);
    }

    /**
     * Exposes the filter options for integration tests that need to verify
     * the parsed options without going through the full load flow.
     *
     * @return current filter options built from the UI fields
     */
    public JTLParser.FilterOptions buildFilterOptions() {
        return reportPanel.buildFilterOptions();
    }
}
