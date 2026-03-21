package com.personal.jmeter.listener.core;

import com.personal.jmeter.parser.JTLParser;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.function.Supplier;

/**
 * Table cell renderer that highlights a cell in red bold when its value
 * breaches the active SLA threshold.
 *
 * <p>Only the specific breaching cell is highlighted — not the entire row.
 * The TOTAL row is never highlighted regardless of its values.</p>
 *
 * <p>The renderer reads the current {@link SlaConfig} via a {@link Supplier}
 * on every paint call, so threshold changes take effect immediately on the
 * next {@link JTable#repaint()} without requiring a full table repopulate.</p>
 *
 * <p>Install via {@code table.setDefaultRenderer(Object.class, renderer)}
 * after the table has been constructed.</p>
 * @since 4.6.0
 */
public final class SlaRowRenderer extends DefaultTableCellRenderer {

    private static final long serialVersionUID = 1L;

    private static final Color BREACH_FOREGROUND = Color.RED;

    private final Supplier<SlaConfig> slaConfigSupplier;
    private final int errorRateColIdx;
    private final int avgColIdx;
    private final int pnnColIdx;
    private final int nameColIdx;

    /**
     * Constructs the renderer.
     *
     * @param slaConfigSupplier live supplier of the current SLA config; must not be null
     * @param errorRateColIdx   model column index of the Error Rate column
     * @param avgColIdx         model column index of the Avg (ms) column
     * @param pnnColIdx         model column index of the Pnn (ms) column
     * @param nameColIdx        model column index of the Transaction Name column
     */
    public SlaRowRenderer(Supplier<SlaConfig> slaConfigSupplier,
                          int errorRateColIdx, int avgColIdx,
                          int pnnColIdx, int nameColIdx) {
        this.slaConfigSupplier = slaConfigSupplier;
        this.errorRateColIdx = errorRateColIdx;
        this.avgColIdx = avgColIdx;
        this.pnnColIdx = pnnColIdx;
        this.nameColIdx = nameColIdx;
    }

    // ─────────────────────────────────────────────────────────────
    // Renderer contract
    // ─────────────────────────────────────────────────────────────

    /**
     * Strips the trailing {@code %} from an Error Rate cell before parsing.
     */
    private static double parseErrorRate(Object val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(val.toString().replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Breach evaluation
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses a plain numeric cell value.
     */
    private static double parseDouble(Object val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Parse helpers
    // ─────────────────────────────────────────────────────────────

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {

        // Let super set correct background, foreground, and text for the current L&F
        Component c = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column);

        // Always reset font to the table font first
        setFont(table.getFont());

        // Never breach-highlight selected cells (would fight selection colour)
        // or the TOTAL row
        if (isSelected) return c;

        Object nameCell = table.getModel().getValueAt(row, nameColIdx);
        if (JTLParser.TOTAL_LABEL.equals(
                nameCell != null ? nameCell.toString() : "")) {
            return c;
        }

        // Check breach for this specific column only; explicitly reset for non-breach cells
        // to prevent any stale-renderer-state artifact painting adjacent cells red.
        int modelCol = table.convertColumnIndexToModel(column);
        if (isBreach(table, row, modelCol, slaConfigSupplier.get())) {
            setForeground(BREACH_FOREGROUND);
            setFont(table.getFont().deriveFont(Font.BOLD));
        } else {
            setForeground(table.getForeground());
            setFont(table.getFont());
        }

        return c;
    }

    private boolean isBreach(JTable table, int row, int modelCol, SlaConfig config) {
        if (modelCol == errorRateColIdx && config.isErrorPctEnabled()) {
            return parseErrorRate(table.getModel().getValueAt(row, errorRateColIdx))
                    > config.errorPctThreshold;
        }
        if (modelCol == avgColIdx
                && config.isRtEnabled()
                && config.rtMetric == SlaConfig.RtMetric.AVG) {
            return parseDouble(table.getModel().getValueAt(row, avgColIdx))
                    > config.rtThresholdMs;
        }
        if (modelCol == pnnColIdx
                && config.isRtEnabled()
                && config.rtMetric == SlaConfig.RtMetric.PNN) {
            return parseDouble(table.getModel().getValueAt(row, pnnColIdx))
                    > config.rtThresholdMs;
        }
        return false;
    }
}
