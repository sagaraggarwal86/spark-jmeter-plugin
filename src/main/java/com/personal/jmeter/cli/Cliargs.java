package com.personal.jmeter.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates command-line arguments for the CLI report generator.
 *
 * <p>No external libraries — uses a plain {@code String[]} loop.
 * All validation is performed eagerly; callers inspect {@link #errors()}
 * before proceeding.</p>
 */
final class CliArgs {

    private final List<String> errors = new ArrayList<>();
    // ── Required ──────────────────────────────────────────────────
    private String inputFile;
    private String provider;
    private String configFile;
    // ── Output ────────────────────────────────────────────────────
    private String outputFile = "./report.html";
    // ── Filter Options ────────────────────────────────────────────
    private int startOffset = 0;
    private int endOffset = 0;
    private int percentile = 90;
    private int chartInterval = 0;
    private String search = "";
    private boolean regex = false;
    private boolean exclude = false;
    // ── Report Metadata ───────────────────────────────────────────
    private String scenarioName = "";
    private String description = "";
    private int virtualUsers = 0;
    // ── SLA Thresholds ────────────────────────────────────────────
    private int errorSla = -1;
    private long rtSla = -1;
    private String rtMetric = "percentile";
    // ── State ─────────────────────────────────────────────────────
    private boolean helpRequested = false;

    private CliArgs() {
    }

    // ─────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses and validates the given command-line arguments.
     *
     * @param args raw command-line arguments
     * @return populated {@code CliArgs} instance (check {@link #errors()} before use)
     */
    static CliArgs parse(String[] args) {
        CliArgs cli = new CliArgs();
        cli.doParse(args);
        if (!cli.helpRequested) {
            cli.validate();
        }
        return cli;
    }

    // ─────────────────────────────────────────────────────────────
    // Accessors
    // ─────────────────────────────────────────────────────────────

    static String helpText() {
        return """
                JAAR — JTL AI Analysis & Reporting  (CLI Mode)
                
                Usage:
                  jaar-cli-report.sh  [options]     (macOS / Linux)
                  jaar-cli-report.bat [options]     (Windows)
                
                  Place the wrapper script in $JMETER_HOME/bin/.
                  The plugin JAR must be in $JMETER_HOME/lib/ext/.
                
                Required:
                  -i, --input FILE            JTL/CSV file path
                  --provider STRING           provider name, case-insensitive
                                              (groq, openai, claude, gemini, mistral, deepseek)
                  --config FILE               path to ai-reporter.properties
                
                Output:
                  -o, --output FILE           HTML report output path (default: ./report.html)
                
                Filter Options:
                  --start-offset INT          seconds to trim from start
                  --end-offset INT            seconds to trim from end
                  --percentile INT            percentile 1-99 (default: 90)
                  --chart-interval INT        seconds per chart bucket, 0=auto (default: 0)
                  --search STRING             label filter text (include mode by default)
                  --regex                     treat --search as regex
                  --exclude                   exclude matching transactions (default: include)
                
                Report Metadata:
                  --scenario-name STRING      scenario name for report header
                  --description STRING        scenario description
                  --virtual-users INT         virtual user count for report header
                
                SLA Thresholds:
                  --error-sla INT             error rate threshold % (1-99)
                  --rt-sla LONG               response time threshold in ms
                  --rt-metric avg|percentile  which RT column for --rt-sla (default: percentile)
                
                Help:
                  -h, --help                  print this message and exit
                
                Exit Codes:
                  0   AI verdict PASS — pipeline continues
                  1   AI verdict FAIL — pipeline gate fails
                  2   AI verdict UNDECISIVE — pipeline continues
                  3   Invalid arguments
                  4   JTL parse error
                  5   AI provider error (key, ping, or API failure)
                  6   Report write error
                  7   Unexpected error — full stack trace printed to stderr
                
                Examples:
                  # Minimal
                  jaar-cli-report.sh -i results.jtl --provider groq --config ai-reporter.properties
                
                  # Full
                  jaar-cli-report.sh \\
                    -i results.jtl -o report.html \\
                    --provider openai --config /path/to/ai-reporter.properties \\
                    --start-offset 10 --end-offset 300 --percentile 95 \\
                    --chart-interval 60 --search "Login" \\
                    --scenario-name "Load Test" --description "Peak hour" --virtual-users 200 \\
                    --error-sla 5 --rt-sla 2000 --rt-metric percentile
                """;
    }

    String inputFile() {
        return inputFile;
    }

    String outputFile() {
        return outputFile;
    }

    String provider() {
        return provider;
    }

    String configFile() {
        return configFile;
    }

    int startOffset() {
        return startOffset;
    }

    int endOffset() {
        return endOffset;
    }

    int percentile() {
        return percentile;
    }

    int chartInterval() {
        return chartInterval;
    }

    String search() {
        return search;
    }

    boolean regex() {
        return regex;
    }

    boolean exclude() {
        return exclude;
    }

    String scenarioName() {
        return scenarioName;
    }

    String description() {
        return description;
    }

    int virtualUsers() {
        return virtualUsers;
    }

    int errorSla() {
        return errorSla;
    }

    long rtSla() {
        return rtSla;
    }

    String rtMetric() {
        return rtMetric;
    }

    boolean helpRequested() {
        return helpRequested;
    }

    boolean hasErrorSla() {
        return errorSla > 0;
    }

    boolean hasRtSla() {
        return rtSla > 0;
    }

    // ─────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────

    List<String> errors() {
        return errors;
    }

    private void doParse(String[] args) {
        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "-i", "--input" -> inputFile = nextValue(args, i++, arg);
                case "-o", "--output" -> outputFile = nextValue(args, i++, arg);
                case "--provider" -> provider = nextValue(args, i++, arg);
                case "--config" -> configFile = nextValue(args, i++, arg);
                case "--start-offset" -> startOffset = nextInt(args, i++, arg);
                case "--end-offset" -> endOffset = nextInt(args, i++, arg);
                case "--percentile" -> percentile = nextInt(args, i++, arg);
                case "--chart-interval" -> chartInterval = nextInt(args, i++, arg);
                case "--search" -> search = nextValue(args, i++, arg);
                case "--regex" -> regex = true;
                case "--exclude" -> exclude = true;
                case "--scenario-name" -> scenarioName = nextValue(args, i++, arg);
                case "--description" -> description = nextValue(args, i++, arg);
                case "--virtual-users" -> virtualUsers = nextInt(args, i++, arg);
                case "--error-sla" -> errorSla = nextInt(args, i++, arg);
                case "--rt-sla" -> rtSla = nextLong(args, i++, arg);
                case "--rt-metric" -> rtMetric = nextValue(args, i++, arg);
                case "--help", "-h" -> helpRequested = true;
                default -> errors.add("Unknown argument: " + arg);
            }
            i++;
        }
    }

    private String nextValue(String[] args, int current, String flag) {
        if (current + 1 < args.length) {
            String next = args[current + 1];
            // A token is treated as a flag only when it starts with "--"
            // OR is exactly two characters: "-" + a single letter (e.g. -i, -o, -h).
            // Everything else — including "-MyTest", "-5", or "--" — is a valid value.
            boolean looksLikeFlag = next.startsWith("--")
                    || (next.length() == 2
                    && next.charAt(0) == '-'
                    && Character.isLetter(next.charAt(1)));
            if (!looksLikeFlag) {
                return next;
            }
        }
        errors.add(flag + " requires a value");
        return null;
    }

    private int nextInt(String[] args, int current, String flag) {
        String val = nextValue(args, current, flag);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            errors.add(flag + " must be an integer, got: " + val);
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Validation
    // ─────────────────────────────────────────────────────────────

    private long nextLong(String[] args, int current, String flag) {
        String val = nextValue(args, current, flag);
        if (val == null) return 0;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            errors.add(flag + " must be an integer, got: " + val);
            return 0;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Help text
    // ─────────────────────────────────────────────────────────────

    private void validate() {
        // Required
        if (inputFile == null || inputFile.isBlank())
            errors.add("--input is required");
        else if (!new File(inputFile).isFile())
            errors.add("JTL file not found: " + inputFile);

        if (provider == null || provider.isBlank())
            errors.add("--provider is required");
        if (configFile == null || configFile.isBlank())
            errors.add("--config is required");
        else if (!new File(configFile).isFile())
            errors.add("Config file not found: " + configFile);

        // Ranges
        if (percentile < 1 || percentile > 99)
            errors.add("--percentile must be between 1 and 99");
        if (chartInterval < 0 || chartInterval > 3600)
            errors.add("--chart-interval must be between 0 and 3600");
        // BUG FIX: guard was (errorSla > 0) which made the inner (errorSla < 1) branch
        // unreachable, silently accepting 0 and negative values as "disabled".
        // Correct guard is (errorSla != -1) — -1 is the internal "not provided" sentinel.
        if (errorSla != -1 && (errorSla < 1 || errorSla > 99))
            errors.add("--error-sla must be between 1 and 99");
        if (rtSla != -1 && rtSla < 1)
            errors.add("--rt-sla must be a positive integer in ms");

        // SLA dependency
        if (rtMetric != null && !rtMetric.isBlank()) {
            String lower = rtMetric.toLowerCase(java.util.Locale.ROOT);
            if (!lower.equals("avg") && !lower.equals("percentile"))
                errors.add("--rt-metric must be 'avg' or 'percentile'");
            rtMetric = lower;
        }
        if (rtSla > 0 && (rtMetric == null || rtMetric.isBlank()))
            rtMetric = "percentile";

        // Regex without search
        if (regex && (search == null || search.isBlank()))
            errors.add("--regex requires --search");

        // Exclude without search
        if (exclude && (search == null || search.isBlank()))
            errors.add("--exclude requires --search");

        // Normalise provider to lowercase
        if (provider != null)
            provider = provider.toLowerCase(java.util.Locale.ROOT);
    }
}