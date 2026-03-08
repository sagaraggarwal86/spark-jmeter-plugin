package com.personal.jmeter;

import com.personal.jmeter.listener.AggregateReportPanel;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.net.URL;

/**
 * Standalone preview of the Configurable Aggregate Report UI.
 *
 * <p>Run {@link #main(String[])} directly — no JMeter runtime needed.
 * All shared UI and logic is in {@link AggregateReportPanel}; this class
 * only adds the frame, title bar, and a simple file-browse panel.</p>
 */
public class UIPreview {

    private final AggregateReportPanel reportPanel = new AggregateReportPanel();
    private final JTextField           fileField   = new JTextField("", 40);

    // ─────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // jmeter.properties is only present when test-resources are on the classpath
        // (e.g. running via Maven test scope). When launching directly from IntelliJ
        // with target/classes only, the file may be absent — skip init gracefully.
        URL propsUrl = UIPreview.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            org.apache.jmeter.util.JMeterUtils.initLocale();
        } else {
            System.out.println("[UI PREVIEW] jmeter.properties not found on classpath — skipping JMeter init.");
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.err.println("[UI PREVIEW] Could not set system look-and-feel: " + e.getMessage());
            }
            JFrame frame = new JFrame("Configurable Aggregate Report");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new UIPreview().buildUI());
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 500));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            System.out.println("[UI PREVIEW] Window opened successfully.");
        });
    }

    // ─────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel topWrapper = new JPanel(new BorderLayout());
        topWrapper.add(buildTitleBar(), BorderLayout.NORTH);
        topWrapper.add(buildFilePanel(), BorderLayout.CENTER);

        root.add(topWrapper,  BorderLayout.NORTH);
        root.add(reportPanel, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildTitleBar() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Configurable Aggregate Report");
        title.setFont(AggregateReportPanel.FONT_HEADER.deriveFont(Font.BOLD));
        panel.add(title, BorderLayout.WEST);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        nameRow.add(makeLabel("Name:"));
        nameRow.add(makeTextField("Configurable Aggregate Report", 28));
        nameRow.add(makeLabel("Comments:"));
        nameRow.add(makeTextField("", 28));
        panel.add(nameRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder("Write results to file / Read from file");
        border.setTitleFont(AggregateReportPanel.FONT_HEADER);
        panel.setBorder(border);

        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 4, 4, 4);
        c.anchor  = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        panel.add(makeLabel("Filename"), c);

        fileField.setFont(AggregateReportPanel.FONT_REGULAR);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        panel.add(fileField, c);

        JButton browseBtn = new JButton("Browse...");
        browseBtn.setFont(AggregateReportPanel.FONT_REGULAR);
        browseBtn.addActionListener(e -> browseJtl());
        c.gridx = 2; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(browseBtn, c);

        return panel;
    }

    // ─────────────────────────────────────────────────────────────
    // File browsing
    // ─────────────────────────────────────────────────────────────

    private void browseJtl() {
        File startDir = resolveStartDirectory(fileField.getText().trim());
        JFileChooser fc = new JFileChooser(startDir);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "JTL Files (*.jtl)", "jtl"));
        if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            File selected = fc.getSelectedFile();
            fileField.setText(selected.getAbsolutePath());
            reportPanel.loadJtlFile(selected.getAbsolutePath(), true);
        }
    }

    private static File resolveStartDirectory(String currentPath) {
        if (!currentPath.isEmpty()) {
            File parent = new File(currentPath).getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        return new File(System.getProperty("user.dir"));
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(AggregateReportPanel.FONT_REGULAR);
        return l;
    }

    private static JTextField makeTextField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setFont(AggregateReportPanel.FONT_REGULAR);
        return f;
    }
}