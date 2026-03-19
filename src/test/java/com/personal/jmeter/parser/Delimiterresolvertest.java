package com.personal.jmeter.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DelimiterResolver}.
 *
 * <p>Uses {@link TempDir} to create ephemeral properties files — no real
 * JMeter installation required. Each test builds a fake {@code $JMETER_HOME/bin/}
 * directory structure with the specific property content under test.</p>
 */
@DisplayName("DelimiterResolver")
class DelimiterResolverTest {

    @TempDir
    Path tempDir;

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Creates {@code $tempDir/bin/<fileName>} with the given content. */
    private File writeProps(String fileName, String content) throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve(fileName), content, StandardCharsets.UTF_8);
        return tempDir.toFile(); // returns fake JMETER_HOME
    }

    /** Creates both properties files under {@code $tempDir/bin/}. */
    private File writeBothProps(String jmeterContent, String userContent) throws IOException {
        Path binDir = tempDir.resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("jmeter.properties"), jmeterContent, StandardCharsets.UTF_8);
        Files.writeString(binDir.resolve("user.properties"), userContent, StandardCharsets.UTF_8);
        return tempDir.toFile();
    }

    // ─────────────────────────────────────────────────────────────
    // resolve() — integration of priority rules
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("null jmeterHome → default comma")
        void nullHome() {
            assertEquals(',', DelimiterResolver.resolve(null));
        }

        @Test
        @DisplayName("non-existent directory → default comma")
        void nonExistentHome() {
            assertEquals(',', DelimiterResolver.resolve(new File("/no/such/path")));
        }

        @Test
        @DisplayName("both files absent → default comma (example 1)")
        void bothAbsent() throws IOException {
            Path binDir = tempDir.resolve("bin");
            Files.createDirectories(binDir);
            assertEquals(',', DelimiterResolver.resolve(tempDir.toFile()));
        }

        @Test
        @DisplayName("both commented out → default comma (example 1)")
        void bothCommentedOut() throws IOException {
            File home = writeBothProps(
                    "#jmeter.save.saveservice.default_delimiter=|\n",
                    "#jmeter.save.saveservice.default_delimiter=;\n");
            assertEquals(',', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("user.properties takes priority over jmeter.properties (example 2)")
        void userPriority() throws IOException {
            File home = writeBothProps(
                    "jmeter.save.saveservice.default_delimiter=|\n",
                    "jmeter.save.saveservice.default_delimiter=;\n");
            assertEquals(';', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("jmeter.properties active, user.properties commented → use jmeter (example 3)")
        void jmeterActiveUserCommented() throws IOException {
            File home = writeBothProps(
                    "jmeter.save.saveservice.default_delimiter=|\n",
                    "#jmeter.save.saveservice.default_delimiter=;\n");
            assertEquals('|', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("jmeter.properties commented, user.properties active → use user (example 4)")
        void jmeterCommentedUserActive() throws IOException {
            File home = writeBothProps(
                    "#jmeter.save.saveservice.default_delimiter=|\n",
                    "jmeter.save.saveservice.default_delimiter=;\n");
            assertEquals(';', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("only jmeter.properties present with active value")
        void onlyJmeterPresent() throws IOException {
            File home = writeProps("jmeter.properties",
                    "jmeter.save.saveservice.default_delimiter=;\n");
            assertEquals(';', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("only user.properties present with active value")
        void onlyUserPresent() throws IOException {
            File home = writeProps("user.properties",
                    "jmeter.save.saveservice.default_delimiter=|\n");
            assertEquals('|', DelimiterResolver.resolve(home));
        }

        @Test
        @DisplayName("tab delimiter via \\t escape")
        void tabDelimiter() throws IOException {
            File home = writeProps("jmeter.properties",
                    "jmeter.save.saveservice.default_delimiter=\\t\n");
            assertEquals('\t', DelimiterResolver.resolve(home));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseLine() — line-level edge cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseLine()")
    class ParseLine {

        @Test
        @DisplayName("null line → '\\0'")
        void nullLine() {
            assertEquals('\0', DelimiterResolver.parseLine(null));
        }

        @Test
        @DisplayName("blank line → '\\0'")
        void blankLine() {
            assertEquals('\0', DelimiterResolver.parseLine("   "));
        }

        @Test
        @DisplayName("commented line → '\\0'")
        void commentedLine() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "#jmeter.save.saveservice.default_delimiter=;"));
        }

        @Test
        @DisplayName("comment with leading spaces → '\\0'")
        void commentWithSpaces() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "  # jmeter.save.saveservice.default_delimiter=;"));
        }

        @Test
        @DisplayName("active semicolon → ';'")
        void activeSemicolon() {
            assertEquals(';', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter=;"));
        }

        @Test
        @DisplayName("active pipe → '|'")
        void activePipe() {
            assertEquals('|', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter=|"));
        }

        @Test
        @DisplayName("value with surrounding spaces → trimmed")
        void spacesAroundValue() {
            assertEquals(';', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter = ; "));
        }

        @Test
        @DisplayName("empty value after '=' → '\\0'")
        void emptyValue() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter="));
        }

        @Test
        @DisplayName("no '=' sign → '\\0'")
        void noEquals() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter"));
        }

        @Test
        @DisplayName("partial key match ignored → '\\0'")
        void partialKeyMatch() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter_extra=;"));
        }

        @Test
        @DisplayName("unrelated property → '\\0'")
        void unrelatedProperty() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.output_format=csv"));
        }

        @Test
        @DisplayName("multi-character value → '\\0' (unsupported)")
        void multiCharValue() {
            assertEquals('\0', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter=;;"));
        }

        @Test
        @DisplayName("tab escape → '\\t'")
        void tabEscape() {
            assertEquals('\t', DelimiterResolver.parseLine(
                    "jmeter.save.saveservice.default_delimiter=\\t"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // readDelimiterFromFile() — file-level edge cases
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readDelimiterFromFile()")
    class ReadFromFile {

        @Test
        @DisplayName("null file → '\\0'")
        void nullFile() {
            assertEquals('\0', DelimiterResolver.readDelimiterFromFile(null));
        }

        @Test
        @DisplayName("non-existent file → '\\0'")
        void nonExistentFile() {
            assertEquals('\0', DelimiterResolver.readDelimiterFromFile(
                    new File("/no/such/file.properties")));
        }

        @Test
        @DisplayName("last occurrence wins when property appears multiple times")
        void lastOccurrenceWins() throws IOException {
            File home = writeProps("jmeter.properties",
                    "jmeter.save.saveservice.default_delimiter=|\n"
                            + "jmeter.save.saveservice.default_delimiter=;\n");
            Path file = home.toPath().resolve("bin/jmeter.properties");
            assertEquals(';', DelimiterResolver.readDelimiterFromFile(file.toFile()));
        }

        @Test
        @DisplayName("active entry after commented entry → active wins")
        void activeAfterCommented() throws IOException {
            File home = writeProps("jmeter.properties",
                    "#jmeter.save.saveservice.default_delimiter=|\n"
                            + "jmeter.save.saveservice.default_delimiter=;\n");
            Path file = home.toPath().resolve("bin/jmeter.properties");
            assertEquals(';', DelimiterResolver.readDelimiterFromFile(file.toFile()));
        }

        @Test
        @DisplayName("file with only unrelated properties → '\\0'")
        void noMatchingProperty() throws IOException {
            File home = writeProps("jmeter.properties",
                    "some.other.property=value\n"
                            + "another.property=123\n");
            Path file = home.toPath().resolve("bin/jmeter.properties");
            assertEquals('\0', DelimiterResolver.readDelimiterFromFile(file.toFile()));
        }
    }
}
