package io.github.sagaraggarwal86.jmeter.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CliArgs}.
 *
 * <p>No file system side effects  except where a real file path is required by
 * validation (uses {@link TempDir} for those cases). No network, no Swing.</p>
 */
@DisplayName("CliArgs")
class CliArgsTest {

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Writes a minimal temporary JTL file and returns its absolute path.
     */
    private static String tempJtl(Path dir) throws IOException {
        Path f = dir.resolve("test.jtl");
        Files.writeString(f, "timeStamp,elapsed,label\n");
        return f.toString();
    }

    /**
     * Writes a minimal temporary properties file and returns its absolute path.
     */
    private static String tempConfig(Path dir) throws IOException {
        Path f = dir.resolve("ai-reporter.properties");
        Files.writeString(f, "ai.reporter.groq.api.key=gsk_test\n");
        return f.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Help flag
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Help flag")
    class HelpTests {

        @Test
        @DisplayName("--help sets helpRequested=true and skips validation")
        void helpFlagSkipsValidation() {
            CliArgs cli = CliArgs.parse(new String[]{"--help"});
            assertTrue(cli.helpRequested());
            assertTrue(cli.errors().isEmpty(), "No validation errors when --help is set");
        }

        @Test
        @DisplayName("-h is an alias for --help")
        void shortHelpAlias() {
            CliArgs cli = CliArgs.parse(new String[]{"-h"});
            assertTrue(cli.helpRequested());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Required arguments
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Required arguments")
    class RequiredArgTests {

        @Test
        @DisplayName("missing --input produces error")
        void missingInputProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--input")));
        }

        @Test
        @DisplayName("--ai is no longer required — omitting it produces no error")
        void omittingAiProducesNoError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--ai")),
                "--ai should not be required");
        }

        @Test
        @DisplayName("--provider without --config produces error")
        void providerWithoutConfigProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--provider and --config")));
        }

        @Test
        @DisplayName("--config without --provider produces error")
        void configWithoutProviderProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--provider and --config")));
        }

        @Test
        @DisplayName("no --provider and no SLA is valid (analysis-only mode)")
        void noProviderNoSlaIsValid(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir)
            });
            assertTrue(cli.errors().isEmpty(),
                "Analysis-only mode should be valid, but got: " + cli.errors());
        }

        @Test
        @DisplayName("SLA-only mode (no --provider) — no error when SLA present")
        void slaOnlyModeAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--error-sla", "5"
            });
            assertTrue(cli.errors().isEmpty(), "Expected no errors but got: " + cli.errors());
            assertFalse(cli.hasProvider());
            assertTrue(cli.hasAnySla());
        }

        @Test
        @DisplayName("non-existent input file produces error")
        void nonExistentInputFileProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", "/nonexistent/file.jtl",
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("not found")));
        }

        @Test
        @DisplayName("all required args present — no errors")
        void allRequiredArgsNoErrors(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertTrue(cli.errors().isEmpty(), "Expected no errors but got: " + cli.errors());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Default values
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Default values")
    class DefaultValueTests {

        @Test
        @DisplayName("outputFile defaults to ./report.html")
        void outputFileDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals("./report.html", cli.outputFile());
        }

        @Test
        @DisplayName("percentile defaults to 90")
        void percentileDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals(90, cli.percentile());
        }

        @Test
        @DisplayName("startOffset defaults to 0")
        void startOffsetDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertEquals(0, cli.startOffset());
        }

        @Test
        @DisplayName("regex defaults to false")
        void regexDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.regex());
        }

        @Test
        @DisplayName("hasErrorSla returns false when --error-sla not set")
        void hasErrorSlaFalseByDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir),
                "--provider", "groq", "--config", tempConfig(dir)
            });
            assertFalse(cli.hasErrorSla());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Range validation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Range validation")
    class RangeValidationTests {

        @Test
        @DisplayName("percentile=0 produces error")
        void percentileTooLow(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--percentile", "0"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("percentile=100 produces error")
        void percentileTooHigh(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--percentile", "100"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("percentile=95 is accepted")
        void percentileValid(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--percentile", "95"
            });
            assertEquals(95, cli.percentile());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("error-sla=100 produces error (must be 1–99)")
        void errorSlaOutOfRange(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--error-sla", "100"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
        }

        @Test
        @DisplayName("chart-interval=3601 produces error")
        void chartIntervalTooHigh(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--chart-interval", "3601"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--chart-interval")));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Parsing behaviour
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Parsing behaviour")
    class ParsingTests {

        @Test
        @DisplayName("provider name is normalised to lowercase")
        void providerNormalisedToLowercase(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "GROQ",
                "--config", tempConfig(dir)
            });
            assertEquals("groq", cli.provider());
        }

        @Test
        @DisplayName("--regex without --search produces error")
        void regexWithoutSearchProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--regex"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--regex")));
        }

        @Test
        @DisplayName("--regex with --search is accepted")
        void regexWithSearchAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--search", "Login", "--regex"
            });
            assertTrue(cli.regex());
            assertEquals("Login", cli.search());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--regex")));
        }

        @Test
        @DisplayName("unknown argument produces error")
        void unknownArgumentProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--unknown-flag"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("Unknown argument")));
        }

        @Test
        @DisplayName("non-integer value for --percentile produces error")
        void nonIntegerPercentileProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--percentile", "abc"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("--rt-metric avg is accepted and normalised to lowercase")
        void rtMetricAvgAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--rt-metric", "AVG"
            });
            assertEquals("avg", cli.rtMetric());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--rt-metric")));
        }

        @Test
        @DisplayName("--rt-metric invalid value produces error")
        void rtMetricInvalidProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--rt-metric", "median"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--rt-metric")));
        }

        @Test
        @DisplayName("--virtual-users and --scenario-name are parsed correctly")
        void metadataArgsParsedCorrectly(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir),
                "--virtual-users", "200", "--scenario-name", "Load Test"
            });
            assertEquals(200, cli.virtualUsers());
            assertEquals("Load Test", cli.scenarioName());
        }

        @Test
        @DisplayName("--description is parsed correctly")
        void descriptionParsedCorrectly(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--description", "Soak test run"
            });
            assertEquals("Soak test run", cli.description());
            assertTrue(cli.errors().isEmpty());
        }

        @Test
        @DisplayName("--rt-metric PERCENTILE uppercase is accepted and normalised")
        void rtMetricPercentileUppercaseAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--rt-metric", "PERCENTILE"
            });
            assertEquals("percentile", cli.rtMetric());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--rt-metric")));
        }

        @Test
        @DisplayName("flag as last arg with no value following produces error")
        void flagAsLastArgProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--percentile"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
        }

        @Test
        @DisplayName("multiple validation errors are all accumulated")
        void multipleErrorsAccumulated(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir),
                "--percentile", "100", "--error-sla", "100"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--percentile")));
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
            assertTrue(cli.errors().size() >= 2);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Boundary values
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Boundary values")
    class BoundaryValueTests {

        @Test
        @DisplayName("chart-interval=0 is accepted (auto mode)")
        void chartIntervalZeroAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--chart-interval", "0"
            });
            assertEquals(0, cli.chartInterval());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--chart-interval")));
        }

        @Test
        @DisplayName("chart-interval=3600 is accepted (maximum boundary)")
        void chartIntervalMaxBoundaryAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--chart-interval", "3600"
            });
            assertEquals(3600, cli.chartInterval());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--chart-interval")));
        }

        @Test
        @DisplayName("start-offset=-5 produces error (negative not allowed)")
        void startOffsetNegativeProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--start-offset", "-5"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--start-offset")));
        }

        @Test
        @DisplayName("end-offset=-10 produces error (negative not allowed)")
        void endOffsetNegativeProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--end-offset", "-10"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--end-offset")));
        }

        @Test
        @DisplayName("chart-interval=-1 produces error (below minimum)")
        void chartIntervalNegativeProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--chart-interval", "-1"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--chart-interval")));
        }

        @Test
        @DisplayName("error-sla=1 is accepted (minimum boundary)")
        void errorSlaMinBoundaryAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--error-sla", "1"
            });
            assertEquals(1, cli.errorSla());
            assertTrue(cli.hasErrorSla());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
        }

        @Test
        @DisplayName("error-sla=99 is accepted (maximum boundary)")
        void errorSlaMaxBoundaryAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--error-sla", "99"
            });
            assertEquals(99, cli.errorSla());
            assertTrue(cli.hasErrorSla());
            assertFalse(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
        }

        @Test
        @DisplayName("error-sla=0 produces a validation error (0 is not a valid threshold)")
        void errorSlaZeroIsRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--error-sla", "0"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")),
                "errorSla=0 should fail validation — 0 is not a valid SLA threshold (range is 1–99)");
        }

        @Test
        @DisplayName("hasRtSla() returns false when --rt-sla not set")
        void hasRtSlaFalseByDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir)
            });
            assertFalse(cli.hasRtSla());
        }

        @Test
        @DisplayName("hasRtSla() returns true when --rt-sla is set")
        void hasRtSlaTrueWhenSet(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir), "--rt-sla", "2000"
            });
            assertTrue(cli.hasRtSla());
            assertEquals(2000L, cli.rtSla());
        }

        @Test
        @DisplayName("hasTpsSla() returns false when --tps-sla not set")
        void hasTpsSlaFalseByDefault(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--provider", "groq",
                "--config", tempConfig(dir)
            });
            assertFalse(cli.hasTpsSla());
        }

        @Test
        @DisplayName("hasTpsSla() returns true when --tps-sla is set")
        void hasTpsSlaWhenSet(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--tps-sla", "10.5"
            });
            assertTrue(cli.hasTpsSla());
            assertEquals(10.5, cli.tpsSla(), 0.001);
        }

        @Test
        @DisplayName("tps-sla=0 produces validation error")
        void tpsSlaZeroRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--tps-sla", "0"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--tps-sla")));
        }

        @Test
        @DisplayName("tps-sla=-1 produces validation error")
        void tpsSlaNegativeRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--tps-sla", "-1"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--tps-sla")));
        }

        @Test
        @DisplayName("tps-sla=abc produces parse error")
        void tpsSlaNotANumber(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--tps-sla", "abc"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--tps-sla")));
        }

        @Test
        @DisplayName("hasAnySla() returns true with any SLA set")
        void hasAnySlaTrue(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--tps-sla", "5"
            });
            assertTrue(cli.hasAnySla());
        }

        @Test
        @DisplayName("error-sla negative value rejected")
        void errorSlaNegativeRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--error-sla", "-1"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--error-sla")));
        }

        @Test
        @DisplayName("rt-sla negative value rejected")
        void rtSlaNegativeRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--rt-sla", "-1"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--rt-sla")));
        }

        @Test
        @DisplayName("rt-sla zero value rejected")
        void rtSlaZeroRejected(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--rt-sla", "0"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--rt-sla")));
        }

        @Test
        @DisplayName("exclude without search produces error")
        void excludeWithoutSearchProducesError(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--exclude"
            });
            assertTrue(cli.errors().stream().anyMatch(e -> e.contains("--exclude")),
                "Expected --exclude error but got: " + cli.errors());
        }

        @Test
        @DisplayName("virtual-users parsed correctly")
        void virtualUsersParsed(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--virtual-users", "100"
            });
            assertTrue(cli.errors().isEmpty(), "Expected no errors but got: " + cli.errors());
            assertEquals(100, cli.virtualUsers());
        }

        @Test
        @DisplayName("search and exclude together accepted")
        void searchAndExcludeAccepted(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir), "--search", "Login", "--exclude"
            });
            assertTrue(cli.errors().isEmpty(), "Expected no errors but got: " + cli.errors());
            assertTrue(cli.exclude());
        }

        @Test
        @DisplayName("hasAnySla() returns false with no SLA set")
        void hasAnySlaFalse(@TempDir Path dir) throws IOException {
            CliArgs cli = CliArgs.parse(new String[]{
                "--input", tempJtl(dir)
            });
            assertFalse(cli.hasAnySla());
        }
    }
}
