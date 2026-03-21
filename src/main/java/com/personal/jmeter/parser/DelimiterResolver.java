package com.personal.jmeter.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Resolves the JTL field delimiter from JMeter's properties files.
 *
 * <h3>Resolution order (highest priority first)</h3>
 * <ol>
 *   <li>{@code $JMETER_HOME/bin/user.properties}</li>
 *   <li>{@code $JMETER_HOME/bin/jmeter.properties}</li>
 *   <li>Default: {@code ','}</li>
 * </ol>
 *
 * <p>A property line is considered active only when it is not commented out
 * (does not start with {@code #}) and has a non-empty value after the
 * {@code =} sign. Commented-out or missing entries are treated as absent.</p>
 *
 * <p>All methods are static; this class is a stateless utility.</p>
 * @since 4.6.0
 */
public final class DelimiterResolver {

    /**
     * The JMeter property key that defines the JTL field delimiter.
     */
    static final String PROPERTY_KEY = "jmeter.save.saveservice.default_delimiter";
    /**
     * Default delimiter when no property is configured.
     */
    static final char DEFAULT_DELIMITER = ',';
    private static final Logger log = LoggerFactory.getLogger(DelimiterResolver.class);
    /**
     * Tab literal — JMeter uses {@code \t} as the property value for tab-separated JTLs.
     */
    private static final String TAB_ESCAPE = "\\t";

    private DelimiterResolver() { /* static utility — not instantiable */ }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Resolves the JTL field delimiter from JMeter's properties files.
     *
     * <p>Reads {@code user.properties} first (highest priority), then
     * {@code jmeter.properties}. The first file that contains an active
     * (non-commented, non-empty) {@code jmeter.save.saveservice.default_delimiter}
     * value wins. If neither file contains an active value, or if
     * {@code jmeterHome} is {@code null}, the default delimiter
     * {@code ','} is returned.</p>
     *
     * @param jmeterHome the JMeter home directory; may be {@code null}
     * @return resolved single-character delimiter; never returns {@code '\0'}
     */
    public static char resolve(File jmeterHome) {
        if (jmeterHome == null) {
            log.debug("resolve: jmeterHome is null — using default delimiter ','");
            return DEFAULT_DELIMITER;
        }

        // user.properties takes priority over jmeter.properties
        File userProps = new File(jmeterHome, "bin/user.properties");
        File jmeterProps = new File(jmeterHome, "bin/jmeter.properties");

        char fromUser = readDelimiterFromFile(userProps);
        if (fromUser != '\0') {
            log.info("resolve: delimiter '{}' resolved from {}", fromUser, userProps.getAbsolutePath());
            return fromUser;
        }

        char fromJmeter = readDelimiterFromFile(jmeterProps);
        if (fromJmeter != '\0') {
            log.info("resolve: delimiter '{}' resolved from {}", fromJmeter, jmeterProps.getAbsolutePath());
            return fromJmeter;
        }

        log.debug("resolve: no active delimiter property found — using default ','");
        return DEFAULT_DELIMITER;
    }

    // ─────────────────────────────────────────────────────────────
    // File reading
    // ─────────────────────────────────────────────────────────────

    /**
     * Reads the delimiter value from a single properties file.
     *
     * <p>Scans the file line by line for the <em>last</em> active occurrence of
     * {@code jmeter.save.saveservice.default_delimiter=<value>}. The last
     * occurrence wins, matching {@link java.util.Properties#load} semantics
     * where later entries override earlier ones.</p>
     *
     * <p>A line is active when:</p>
     * <ul>
     *   <li>Its trimmed content does not start with {@code #}</li>
     *   <li>It contains {@code =} with a non-empty value after the key</li>
     * </ul>
     *
     * @param file properties file to read; may not exist
     * @return resolved delimiter character, or {@code '\0'} if not found or file unreadable
     */
    static char readDelimiterFromFile(File file) {
        if (file == null || !file.isFile()) {
            log.debug("readDelimiterFromFile: file not found or not a file: {}",
                    file != null ? file.getAbsolutePath() : "null");
            return '\0';
        }

        char result = '\0';
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                char parsed = parseLine(line);
                if (parsed != '\0') {
                    result = parsed; // last active occurrence wins
                }
            }
        } catch (IOException e) {
            log.warn("readDelimiterFromFile: failed to read {}. reason={}",
                    file.getAbsolutePath(), e.getMessage());
            return '\0';
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────
    // Line parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses a single line from a properties file for the delimiter key.
     *
     * @param line raw line from the file; may be {@code null}
     * @return delimiter character if the line is an active delimiter entry;
     * {@code '\0'} otherwise
     */
    static char parseLine(String line) {
        if (line == null) return '\0';
        String trimmed = line.trim();

        // Commented-out or empty lines are inactive
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return '\0';

        // Must start with the exact property key
        if (!trimmed.startsWith(PROPERTY_KEY)) return '\0';

        // Find the '=' separator
        int eqIdx = trimmed.indexOf('=');
        if (eqIdx < 0) return '\0';

        // Key portion must match exactly (no partial matches like "...default_delimiter_extra=")
        String key = trimmed.substring(0, eqIdx).trim();
        if (!key.equals(PROPERTY_KEY)) return '\0';

        String value = trimmed.substring(eqIdx + 1).trim();
        if (value.isEmpty()) return '\0';

        // Handle JMeter's tab escape convention
        if (TAB_ESCAPE.equals(value)) {
            return '\t';
        }

        // Single-character delimiter
        if (value.length() == 1) {
            return value.charAt(0);
        }

        log.warn("parseLine: multi-character delimiter value '{}' is not supported — ignoring.", value);
        return '\0';
    }
}
