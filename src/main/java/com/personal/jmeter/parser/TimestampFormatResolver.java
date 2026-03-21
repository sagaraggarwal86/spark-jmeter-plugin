package com.personal.jmeter.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;

/**
 * Resolves the JTL timestamp format from JMeter's properties files.
 *
 * <h3>Resolution order (highest priority first)</h3>
 * <ol>
 *   <li>{@code $JMETER_HOME/bin/user.properties}</li>
 *   <li>{@code $JMETER_HOME/bin/jmeter.properties}</li>
 *   <li>Default: {@code null} — epoch milliseconds mode</li>
 * </ol>
 *
 * <p>A property line is considered active only when it is not commented out
 * (does not start with {@code #}) and has a non-empty value after the
 * {@code =} sign.</p>
 *
 * <p>When the resolved value is {@code "ms"} (JMeter's built-in epoch-ms
 * sentinel), {@code null} is returned — identical to the absent-property case.
 * All other non-blank values are compiled into a {@link DateTimeFormatter};
 * if the pattern is invalid, {@code null} is returned and a warning is logged.</p>
 *
 * <p>All methods are static; this class is a stateless utility.</p>
 * @since 4.6.0
 */
public final class TimestampFormatResolver {

    /**
     * The JMeter property key that defines the JTL timestamp format.
     */
    static final String PROPERTY_KEY = "jmeter.save.saveservice.timestamp_format";
    private static final Logger log = LoggerFactory.getLogger(TimestampFormatResolver.class);
    /**
     * JMeter's built-in sentinel value for epoch-millisecond timestamps.
     * When encountered, treated identically to an absent property.
     */
    private static final String EPOCH_MS_VALUE = "ms";

    private TimestampFormatResolver() { /* static utility — not instantiable */ }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Resolves the JTL timestamp {@link DateTimeFormatter} from JMeter's
     * properties files.
     *
     * <p>Returns {@code null} when:</p>
     * <ul>
     *   <li>{@code jmeterHome} is {@code null}</li>
     *   <li>the property is absent or commented out in both files</li>
     *   <li>the property value is {@code "ms"} (epoch-ms sentinel)</li>
     *   <li>the pattern string is invalid (a warning is logged)</li>
     * </ul>
     *
     * <p>{@code null} signals epoch-millisecond mode to the caller.</p>
     *
     * @param jmeterHome the JMeter home directory; may be {@code null}
     * @return compiled {@link DateTimeFormatter}, or {@code null} for epoch-ms mode
     */
    public static DateTimeFormatter resolve(File jmeterHome) {
        String format = resolveFormatString(jmeterHome);
        if (format == null) {
            log.debug("resolve: no active timestamp_format — epoch ms mode.");
            return null;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            log.info("resolve: timestamp_format='{}' resolved.", format);
            return formatter;
        } catch (IllegalArgumentException e) {
            log.warn("resolve: invalid timestamp_format pattern '{}' — falling back to epoch ms. reason={}",
                    format, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Resolution logic
    // ─────────────────────────────────────────────────────────────

    /**
     * Resolves the raw format string from user.properties (priority) then
     * jmeter.properties. Returns {@code null} when neither contains an
     * active, non-ms value.
     */
    private static String resolveFormatString(File jmeterHome) {
        if (jmeterHome == null) return null;

        File userProps = new File(jmeterHome, "bin/user.properties");
        File jmeterProps = new File(jmeterHome, "bin/jmeter.properties");

        String fromUser = readFormatFromFile(userProps);
        if (fromUser != null) {
            log.info("resolveFormatString: timestamp_format='{}' from {}",
                    fromUser, userProps.getAbsolutePath());
            return fromUser;
        }

        String fromJmeter = readFormatFromFile(jmeterProps);
        if (fromJmeter != null) {
            log.info("resolveFormatString: timestamp_format='{}' from {}",
                    fromJmeter, jmeterProps.getAbsolutePath());
        }
        return fromJmeter;
    }

    // ─────────────────────────────────────────────────────────────
    // File reading
    // ─────────────────────────────────────────────────────────────

    /**
     * Reads the timestamp format from a single properties file.
     * The last active occurrence wins, matching {@link java.util.Properties#load}
     * semantics.
     *
     * @param file properties file to read; may not exist
     * @return format string, or {@code null} if not found / file unreadable
     */
    static String readFormatFromFile(File file) {
        if (file == null || !file.isFile()) {
            log.debug("readFormatFromFile: file not found: {}",
                    file != null ? file.getAbsolutePath() : "null");
            return null;
        }
        String result = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String parsed = parseLine(line);
                if (parsed != null) {
                    result = parsed; // last active occurrence wins
                }
            }
        } catch (IOException e) {
            log.warn("readFormatFromFile: failed to read {}. reason={}",
                    file.getAbsolutePath(), e.getMessage());
            return null;
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Line parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses a single properties-file line for the timestamp format key.
     *
     * @param line raw line; may be {@code null}
     * @return format string if the line is an active timestamp-format entry
     * and not the epoch-ms sentinel; {@code null} otherwise
     */
    static String parseLine(String line) {
        if (line == null) return null;
        String trimmed = line.trim();

        // Commented-out or empty lines are inactive
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null;

        // Must start with the exact property key
        if (!trimmed.startsWith(PROPERTY_KEY)) return null;

        int eqIdx = trimmed.indexOf('=');
        if (eqIdx < 0) return null;

        // Key must match exactly — no partial matches
        String key = trimmed.substring(0, eqIdx).trim();
        if (!key.equals(PROPERTY_KEY)) return null;

        String value = trimmed.substring(eqIdx + 1).trim();
        if (value.isEmpty()) return null;

        // "ms" is JMeter's epoch-ms sentinel — treat as absent
        if (EPOCH_MS_VALUE.equalsIgnoreCase(value)) return null;

        return value;
    }
}