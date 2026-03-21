package com.personal.jmeter.cli;

import com.personal.jmeter.ai.provider.AiProviderException;
import com.personal.jmeter.ai.provider.AiServiceException;
import com.personal.jmeter.parser.JtlParseException;
import org.apache.jmeter.util.JMeterUtils;

import java.io.IOException;
import java.net.URL;

/**
 * Command-line entry point for JAAR — JTL AI Analysis &amp; Reporting.
 *
 * <p>Parses a JTL file, calls the configured AI provider, and generates a
 * standalone HTML performance report — no JMeter GUI or Swing required.</p>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>{@code 0} — AI verdict PASS — pipeline continues</li>
 *   <li>{@code 1} — AI verdict FAIL — pipeline gate fails</li>
 *   <li>{@code 2} — AI did not generate a verdict line — pipeline continues</li>
 *   <li>{@code 3} — invalid command-line arguments</li>
 *   <li>{@code 4} — JTL parse error</li>
 *   <li>{@code 5} — AI provider error (key invalid, ping failed, API error)</li>
 *   <li>{@code 6} — report file write failure</li>
 *   <li>{@code 7} — unexpected error (unhandled RuntimeException, Error, etc.) — full stack trace printed to stderr</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>
 * java -cp jaar-jmeter-plugin.jar com.personal.jmeter.cli.Main \
 *   -i results.jtl --provider groq --config ai-reporter.properties
 * </pre>
 * @since 4.6.0
 */
public final class Main {

    /**
     * Exit code: AI verdict PASS — pipeline continues.
     */
    static final int EXIT_VERDICT_PASS = 0;
    /**
     * Exit code: AI verdict FAIL — pipeline gate fails.
     */
    static final int EXIT_VERDICT_FAIL = 1;
    /**
     * Exit code: AI did not generate a verdict line — pipeline continues.
     */
    static final int EXIT_VERDICT_UNDECISIVE = 2;
    /**
     * Exit code: invalid command-line arguments.
     */
    static final int EXIT_BAD_ARGS = 3;
    /**
     * Exit code: JTL parse failure.
     */
    static final int EXIT_PARSE_ERROR = 4;
    /**
     * Exit code: AI provider error (key, ping, or API).
     */
    static final int EXIT_AI_ERROR = 5;
    /**
     * Exit code: report file write failure.
     */
    static final int EXIT_WRITE_ERROR = 6;
    /**
     * Exit code: unexpected / unhandled error (RuntimeException, Error, etc.) — full stack trace printed to stderr.
     */
    static final int EXIT_UNEXPECTED_ERROR = 7;

    private Main() {
    }

    /**
     * CLI entry point.
     *
     * @param args command-line arguments (see {@link CliArgs#helpText()})
     */
    public static void main(String[] args) {
        // Initialise JMeter properties (SampleResult needs them)
        initJMeterProperties();

        CliArgs cli = CliArgs.parse(args);

        if (cli.helpRequested()) {
            System.out.println(CliArgs.helpText());
            System.exit(EXIT_VERDICT_PASS);
            return;
        }

        if (!cli.errors().isEmpty()) {
            System.err.println("Error(s):");
            cli.errors().forEach(e -> System.err.println("  " + e));
            System.err.println();
            System.err.println("Run with --help for usage.");
            System.exit(EXIT_BAD_ARGS);
            return;
        }

        try {
            CliReportPipeline.PipelineResult result = new CliReportPipeline(cli).execute();
            System.out.println(result.outputPath());
            System.out.println("VERDICT:" + result.verdict());
            switch (result.verdict()) {
                case "PASS" -> System.exit(EXIT_VERDICT_PASS);
                case "FAIL" -> System.exit(EXIT_VERDICT_FAIL);
                default -> System.exit(EXIT_VERDICT_UNDECISIVE);
            }
        } catch (JtlParseException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_PARSE_ERROR);
        } catch (AiProviderException | AiServiceException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_AI_ERROR);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(EXIT_WRITE_ERROR);
        } catch (Throwable t) {
            System.err.println("Unexpected error: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(EXIT_UNEXPECTED_ERROR);
        }
    }

    /**
     * Loads JMeter properties from the classpath so that {@code SampleResult}'s
     * constructor has the five properties it reads at instantiation time.
     * Falls back silently if not found — SampleResult uses its own defaults.
     */
    private static void initJMeterProperties() {
        URL propsUrl = Main.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }
}