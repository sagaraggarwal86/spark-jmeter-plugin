package com.sagar.jmeter.sampler;

import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class SamplePluginSamplerUI extends AbstractSamplerGui {

    private final JTextField urlField     = new JTextField("https://example.com", 40);
    private final JTextField timeoutField = new JTextField("5000", 10);
    private final JTextArea  payloadArea  = new JTextArea(4, 40);

    public SamplePluginSamplerUI() {
        super();
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Sample Plugin Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;

        // Row 0: Target URL
        c.gridx = 0; c.gridy = 0;
        panel.add(new JLabel("Target URL:"), c);
        c.gridx = 1;
        panel.add(urlField, c);

        // Row 1: Timeout
        c.gridx = 0; c.gridy = 1;
        panel.add(new JLabel("Timeout (ms):"), c);
        c.gridx = 1;
        panel.add(timeoutField, c);

        // Row 2: Payload
        c.gridx = 0; c.gridy = 2;
        c.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Payload:"), c);
        c.gridx = 1;
        payloadArea.setLineWrap(true);
        payloadArea.setWrapStyleWord(true);
        panel.add(new JScrollPane(payloadArea), c);

        add(panel, BorderLayout.CENTER);
    }

    @Override public String getLabelResource() { return "sample_plugin_sampler"; }
    @Override public String getStaticLabel()   { return "Sample Plugin Sampler"; }

    @Override
    public TestElement createTestElement() {
        SamplePluginSampler s = new SamplePluginSampler();
        modifyTestElement(s);
        return s;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        configureTestElement(el);
        if (el instanceof SamplePluginSampler s) {
            s.setTargetUrl(urlField.getText().trim());
            s.setTimeoutMs(parseIntSafe(timeoutField.getText(), 5000));
            s.setPayload(payloadArea.getText());
        }
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
        if (el instanceof SamplePluginSampler s) {
            urlField.setText(s.getTargetUrl());
            timeoutField.setText(String.valueOf(s.getTimeoutMs()));
            payloadArea.setText(s.getPayload());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        urlField.setText("https://example.com");
        timeoutField.setText("5000");
        payloadArea.setText("");
    }

    private int parseIntSafe(String value, int defaultVal) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}