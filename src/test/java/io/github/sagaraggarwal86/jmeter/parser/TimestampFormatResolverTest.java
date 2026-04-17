package io.github.sagaraggarwal86.jmeter.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TimestampFormatResolver}.
 *
 * <p>Uses temporary directories for properties file fixtures.
 * No network, no Swing, no persistent state.</p>
 */
@DisplayName("TimestampFormatResolver")
class TimestampFormatResolverTest {

    private static final String KEY = TimestampFormatResolver.PROPERTY_KEY;

    /**
     * Creates a JMeter home directory structure with user.properties and/or
     * jmeter.properties containing the given content.
     */
    private static File writeProps(Path tempDir, String userContent, String jmeterContent)
        throws IOException {
        File home = tempDir.toFile();
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        if (userContent != null) {
            Files.writeString(binDir.resolve("user.properties"), userContent, StandardCharsets.UTF_8);
        }
        if (jmeterContent != null) {
            Files.writeString(binDir.resolve("jmeter.properties"), jmeterContent, StandardCharsets.UTF_8);
        }
        return home;
    }

    // ─────────────────────────────────────────────────────────────
    // resolve() — public API
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("null jmeterHome returns null (epoch-ms mode)")
        void nullJmeterHomeReturnsNull() {
            assertNull(TimestampFormatResolver.resolve(null));
        }

        @Test
        @DisplayName("no properties files returns null (epoch-ms mode)")
        void noPropertiesFilesReturnsNull(@TempDir Path dir) throws IOException {
            Files.createDirectories(dir.resolve("bin"));
            assertNull(TimestampFormatResolver.resolve(dir.toFile()));
        }

        @Test
        @DisplayName("valid format in user.properties returns formatter")
        void validFormatInUserPropsReturnsFormatter(@TempDir Path dir) throws IOException {
            File home = writeProps(dir,
                KEY + "=yyyy/MM/dd HH:mm:ss", null);
            DateTimeFormatter result = TimestampFormatResolver.resolve(home);
            assertNotNull(result, "Should return a formatter for valid pattern");
        }

        @Test
        @DisplayName("valid format in jmeter.properties returns formatter")
        void validFormatInJmeterPropsReturnsFormatter(@TempDir Path dir) throws IOException {
            File home = writeProps(dir,
                null, KEY + "=yyyy-MM-dd HH:mm:ss.SSS");
            DateTimeFormatter result = TimestampFormatResolver.resolve(home);
            assertNotNull(result, "Should return a formatter from jmeter.properties");
        }

        @Test
        @DisplayName("user.properties takes priority over jmeter.properties")
        void userPropsTakesPriority(@TempDir Path dir) throws IOException {
            File home = writeProps(dir,
                KEY + "=yyyy/MM/dd HH:mm:ss",
                KEY + "=MM/dd/yyyy HH:mm:ss");
            DateTimeFormatter result = TimestampFormatResolver.resolve(home);
            assertNotNull(result);
            // Verify the user.properties format is used by parsing a matching date
            assertDoesNotThrow(() ->
                java.time.LocalDateTime.parse("2026/03/20 14:30:00", result));
        }

        @Test
        @DisplayName("'ms' sentinel returns null (epoch-ms mode)")
        void msSentinelReturnsNull(@TempDir Path dir) throws IOException {
            File home = writeProps(dir, KEY + "=ms", null);
            assertNull(TimestampFormatResolver.resolve(home),
                "'ms' is the epoch-ms sentinel — should return null");
        }

        @Test
        @DisplayName("'MS' (case-insensitive) sentinel returns null")
        void msSentinelCaseInsensitive(@TempDir Path dir) throws IOException {
            File home = writeProps(dir, KEY + "=MS", null);
            assertNull(TimestampFormatResolver.resolve(home));
        }

        @Test
        @DisplayName("invalid pattern returns null (with warning, no exception)")
        void invalidPatternReturnsNull(@TempDir Path dir) throws IOException {
            File home = writeProps(dir, KEY + "=ZZZZZINVALID", null);
            assertNull(TimestampFormatResolver.resolve(home),
                "Invalid pattern should return null gracefully");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseLine() — line-level parsing
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseLine()")
    class ParseLineTests {

        @Test
        @DisplayName("null line returns null")
        void nullLineReturnsNull() {
            assertNull(TimestampFormatResolver.parseLine(null));
        }

        @Test
        @DisplayName("empty line returns null")
        void emptyLineReturnsNull() {
            assertNull(TimestampFormatResolver.parseLine(""));
        }

        @Test
        @DisplayName("commented line returns null")
        void commentedLineReturnsNull() {
            assertNull(TimestampFormatResolver.parseLine("# " + KEY + "=yyyy/MM/dd"));
        }

        @Test
        @DisplayName("active line returns format string")
        void activeLineReturnsFormat() {
            assertEquals("yyyy/MM/dd HH:mm:ss",
                TimestampFormatResolver.parseLine(KEY + "=yyyy/MM/dd HH:mm:ss"));
        }

        @Test
        @DisplayName("'ms' value returns null (epoch-ms sentinel)")
        void msValueReturnsNull() {
            assertNull(TimestampFormatResolver.parseLine(KEY + "=ms"));
        }

        @Test
        @DisplayName("empty value after = returns null")
        void emptyValueReturnsNull() {
            assertNull(TimestampFormatResolver.parseLine(KEY + "="));
        }

        @Test
        @DisplayName("partial key match is rejected")
        void partialKeyMatchRejected() {
            assertNull(TimestampFormatResolver.parseLine(KEY + "_extra=yyyy/MM/dd"));
        }

        @Test
        @DisplayName("whitespace around value is trimmed")
        void whitespaceAroundValueTrimmed() {
            assertEquals("yyyy-MM-dd",
                TimestampFormatResolver.parseLine(KEY + "=  yyyy-MM-dd  "));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // readFormatFromFile() — last-occurrence-wins semantics
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readFormatFromFile()")
    class ReadFormatFromFileTests {

        @Test
        @DisplayName("last active occurrence wins")
        void lastOccurrenceWins(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("test.properties");
            Files.writeString(file,
                KEY + "=yyyy/MM/dd HH:mm:ss\n"
                    + KEY + "=MM/dd/yyyy HH:mm:ss\n",
                StandardCharsets.UTF_8);
            assertEquals("MM/dd/yyyy HH:mm:ss",
                TimestampFormatResolver.readFormatFromFile(file.toFile()));
        }

        @Test
        @DisplayName("null file returns null")
        void nullFileReturnsNull() {
            assertNull(TimestampFormatResolver.readFormatFromFile(null));
        }

        @Test
        @DisplayName("non-existent file returns null")
        void nonExistentFileReturnsNull(@TempDir Path dir) {
            assertNull(TimestampFormatResolver.readFormatFromFile(
                dir.resolve("does-not-exist.properties").toFile()));
        }

        @Test
        @DisplayName("file with only commented lines returns null")
        void onlyCommentsReturnsNull(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("test.properties");
            Files.writeString(file, "# " + KEY + "=yyyy/MM/dd\n", StandardCharsets.UTF_8);
            assertNull(TimestampFormatResolver.readFormatFromFile(file.toFile()));
        }
    }
}
