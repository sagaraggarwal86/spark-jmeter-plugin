# JAAR — JTL AI Analysis & Reporting

A file-based Apache JMeter listener plugin for post-test JTL analysis. Load a JTL file and get
a filterable aggregate table, CSV export, and an AI-generated HTML performance report — with zero
runtime overhead.

---

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Filter Settings](#filter-settings)
- [SLA Thresholds](#sla-thresholds)
- [AI Performance Report](#ai-performance-report)
- [Local LLM Support](#local-llm-support)
- [CLI Mode](#cli-mode)
- [Large JTL Files](#large-jtl-files)
- [Running Tests](#running-tests)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

---

## Features

| Feature                        | Description                                                                                      |
|--------------------------------|--------------------------------------------------------------------------------------------------|
| 📂 **JTL File Processing**     | Browse and load JTL files — the metrics table populates instantly                                |
| ⏱️ **Start / End Offset**      | Exclude ramp-up and ramp-down periods by entering a time window in seconds                       |
| 📈 **Configurable Percentile** | Set any percentile value: 50th, 90th, 95th, 99th, or custom                                      |
| 🔍 **Transaction Filter**      | Filter by transaction name with Include/Exclude mode, plain text or regex                        |
| 👁️ **Column Visibility**      | Show or hide any column via a dropdown multi-select control                                       |
| ✅ **Pass / Fail Counts**       | Dedicated columns for transactions passed and transactions failed                                |
| 🕐 **Test Time Info**          | Start Date/Time, End Date/Time, and total Duration shown automatically                           |
| 🔀 **Sortable Columns**        | Click any column header to sort ascending; click again for descending                            |
| 🚨 **SLA Thresholds**          | Set Error % and Response Time thresholds — breaching cells are highlighted in red                |
| 💾 **CSV Export**              | Save all visible columns to a CSV file; SLA status columns (PASS/FAIL) included when configured  |
| 🤖 **AI Performance Report**   | Generate a styled HTML report with deep-dive analysis, powered by any OpenAI-compatible provider |
| 📊 **Chart Interval**          | Configure the time-bucket interval for performance charts (default: auto, or set custom)         |
| 🔄 **Provider Reload**         | Reload the AI provider list from `ai-reporter.properties` without restarting JMeter             |
| 📐 **Delimiter Detection**     | Automatically reads the JTL delimiter from JMeter's properties files (`,`, `;`, `\t`, etc.)      |
| 🕒 **Timestamp Format**        | Auto-detects epoch-ms and formatted timestamps (`yyyy/MM/dd HH:mm:ss`, `MM/dd/yyyy`, etc.)      |
| 🚫 **No Live Metrics**         | Designed for post-test JTL analysis — no runtime overhead                                        |

---

## Requirements

| Requirement   | Version                    |
|---------------|----------------------------|
| Java          | 17+                        |
| Apache JMeter | 5.6.3+                     |
| Maven         | 3.6+ *(build only)*        |
| AI API key    | *(AI report feature only)* |

---

## Installation

### From Releases (Recommended)

1. Download the latest JAR from the
   [GitHub Releases](https://github.com/sagaraggarwal86/jaar-jmeter-plugin/releases) page.

2. Copy it to your JMeter `lib/ext/` directory:
   ```
   <JMETER_HOME>/lib/ext/jaar-jmeter-plugin-<version>.jar
   ```

3. Restart JMeter.

4. *(Optional — CLI mode)* Copy the wrapper scripts to `<JMETER_HOME>/bin/`:
   ```
   <JMETER_HOME>/bin/jaar-cli-report.bat     (Windows)
   <JMETER_HOME>/bin/jaar-cli-report.sh      (macOS / Linux)
   ```
   The scripts are in the `src/main/scripts/` directory of the source repository.

5. *(Optional — AI report)* Copy the sample configuration file to `<JMETER_HOME>/bin/`:
   ```
   <JMETER_HOME>/bin/ai-reporter.properties
   ```
   A sample file with all supported options is provided at
   [docs/ai-reporter.properties](docs/ai-reporter.properties).
   Set at least one provider's `api.key` to enable the AI report feature:
   ```properties
   ai.reporter.mistral.api.key=          ← Recommended (free, high limits)
   ai.reporter.cerebras.api.key=
   ai.reporter.gemini.api.key=
   ai.reporter.openai.api.key=
   ai.reporter.claude.api.key=
   ```

### Build from Source

**Prerequisites:** Java 17+, Maven 3.6+

```bash
git clone https://github.com/sagaraggarwal86/jaar-jmeter-plugin.git
cd jaar-jmeter-plugin
mvn clean verify
cp target/jaar-jmeter-plugin-*.jar $JMETER_HOME/lib/ext/
```

---

## Quick Start

1. In JMeter: **Test Plan → Add → Listener → JAAR — JTL AI Analysis & Reporting**
2. Click **Browse** and select a JTL file
3. The metrics table populates immediately
4. Adjust filters as needed — the table updates without re-browsing

---

## Filter Settings

### Start / End Offset

Offsets let you focus analysis on the steady-state portion of a test by excluding ramp-up and
ramp-down samples. Both values are in seconds, measured from the first sample in the JTL file.

| Start Offset | End Offset | Behaviour                                       |
|--------------|------------|-------------------------------------------------|
| *(empty)*    | *(empty)*  | All samples included                            |
| `5`          | *(empty)*  | Skip first 5 seconds; include the rest          |
| *(empty)*    | `25`       | Include up to 25 seconds; skip everything after |
| `5`          | `25`       | Include only the 5s – 25s window                |

> **Tip:** Changing offset values re-parses the JTL file automatically after a brief pause — no need to re-browse.

### Transaction Names

Filter the results table by typing in the **Transaction Names** field.

| Mode                     | Behaviour                        | Example                          |
|--------------------------|----------------------------------|----------------------------------|
| **Plain text** (default) | Case-insensitive substring match | `login` matches `Login Flow`     |
| **RegEx** (checkbox on)  | Java regex pattern match         | `Login\|Checkout` matches either |

| Filter Mode           | Behaviour                                                                |
|-----------------------|--------------------------------------------------------------------------|
| **Include** (default) | Show only transactions matching the pattern                              |
| **Exclude**           | Hide transactions matching the pattern — all non-matching rows are shown |

### Chart Interval

The **Chart Interval (s, 0=auto)** field controls the time-bucket width for performance charts.

| Value | Behaviour                                                                        |
|-------|----------------------------------------------------------------------------------|
| `0`   | Auto — dynamically selects an interval based on test duration (~120 data points) |
| `10`  | Each chart data point represents a 10-second window                              |
| `60`  | Each chart data point represents a 1-minute window                               |

Valid range: 0–3600 seconds.

---

## SLA Thresholds

Set live SLA thresholds in the **SLA Thresholds** panel. Breaching cells are highlighted in
**red bold** — no re-parse required.

| Field             | Description                                                                                   |
|-------------------|-----------------------------------------------------------------------------------------------|
| **Error %**       | Highlight Error Rate cells exceeding this value (1–99)                                        |
| **Response Time** | Choose **Avg (ms)** or **Pnn (ms)** from the dropdown, then enter a threshold in milliseconds |

The TOTAL row is never highlighted. Leave a field blank to disable that threshold.

---

## AI Performance Report

Click **Generate AI Report** to analyse the loaded JTL data with any supported AI provider.
A save dialog lets you choose where to save the self-contained HTML report.

**Supported providers:** Mistral (free, **recommended**), Groq (free), Gemini (free), DeepSeek (free),
Cerebras (free), OpenAI (paid), Claude (paid), Ollama (local / free) — or any OpenAI-compatible endpoint.

### Pre-Computed Analysis

The plugin pre-computes several analytical results in Java before sending data to the AI provider. This ensures
deterministic, accurate outputs regardless of which AI model is used:

| Pre-Computed Field      | Description                                                                                                                              |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `classificationSummary` | Bottleneck classification (THROUGHPUT-BOUND, LATENCY-BOUND, ERROR-BOUND, CAPACITY-WALL) with latency ratio, plateau ratio, and reasoning |
| `overallVerdictSummary` | Final PASS/FAIL verdict combining SLA results with classification fallback                                                               |
| `errorSlaSummary`       | Per-transaction error rate SLA evaluation with worst transaction and breach details                                                      |
| `rtSlaSummary`          | Per-transaction response time SLA evaluation with worst transaction and breach details                                                   |

The AI provider's role is to write analytical prose that justifies and explains the pre-computed results — it never
computes the classification, verdict, or SLA outcomes itself.

### Report Sections

| Section                   | Description                                                        |
|---------------------------|--------------------------------------------------------------------|
| Executive Summary         | Scenario overview, PASS/FAIL verdict, and binding constraint       |
| Bottleneck Analysis       | Pre-computed classification with throughput/latency/error evidence |
| Error Analysis            | Failure mode characterisation and SLA threshold verdict            |
| Advanced Web Diagnostics  | Response time breakdown by network, server, and transfer phase     |
| Root Cause Hypotheses     | Ranked list of probable causes with supporting metric evidence     |
| Recommendations           | Prioritised action table mapped to root cause findings             |
| Verdict                   | Single PASS or FAIL outcome from pre-computed verdict              |
| Transaction Metrics Table | Full per-transaction breakdown with SLA status columns             |
| Performance Charts        | Response time, error rate, throughput, and bandwidth over time     |

### Report Exports

The HTML report includes two export buttons:

| Button           | Output                                                                                   |
|------------------|------------------------------------------------------------------------------------------|
| **Export Excel** | One worksheet per report tab — analysis sections as prose, Transaction Metrics as a table |
| **Export PDF**   | Opens the browser print dialog with all tabs expanded and charts visible                 |

### Truncation Handling

When the AI provider hits its token limit before completing all 7 sections, the plugin detects this
and appends a notice identifying which sections were completed and which were not reached:

> **⚠ Partial report — reached its output limit**
>
> **Sections completed:** Executive Summary, Bottleneck Analysis, Error Analysis
>
> **Sections not reached:** Advanced Web Diagnostics, Root Cause Hypotheses, Recommendations, Verdict
>
> The SLA verdict and transaction metrics above are accurate regardless of the missing sections.
> To get a complete report, try regenerating or increase `max.tokens` for this provider in `ai-reporter.properties`.

The plugin detects truncation via two signals — `finish_reason: "length"` and a fallback check of
`usage.completion_tokens >= max_tokens`. The SLA verdict, classification, and transaction metrics
are always accurate regardless of truncation — they are computed in Java, not by the AI.

### API Key Setup

Copy [docs/ai-reporter.properties](docs/ai-reporter.properties) to `<JMETER_HOME>/bin/`
and set at least one provider's `api.key`:

```properties
ai.reporter.mistral.api.key=your-key-here          ← Recommended (free, 500K TPM)
ai.reporter.cerebras.api.key=csk-your-key-here     ← Free, 60K TPM
ai.reporter.groq.api.key=gsk_your-key-here         ← Free, 12K TPM (may hit limits)
ai.reporter.gemini.api.key=AIza-your-key-here
ai.reporter.deepseek.api.key=your-key-here
ai.reporter.openai.api.key=sk-your-key-here
ai.reporter.claude.api.key=sk-ant-your-key-here
```

Select the provider from the dropdown next to the **Generate AI Report** button. Click **Reload** to
refresh the provider list after editing `ai-reporter.properties` — no restart needed.

### Provider Order

By default, providers appear in the dropdown in built-in order (Groq first, then Gemini, Mistral, etc.).
Override this with the `ai.reporter.order` property:

```properties
ai.reporter.order=cerebras,mistral,groq
```

Only configured providers (those with a non-blank `api.key`) are shown. Providers not listed in
`ai.reporter.order` appear after the listed ones, in alphabetical order.

### Custom Providers

Any OpenAI-compatible endpoint can be added by specifying a provider key with `base.url` and `model`:

```properties
ai.reporter.myprovider.api.key=your-key-here
ai.reporter.myprovider.base.url=https://api.myprovider.com/v1
ai.reporter.myprovider.model=my-model-name
ai.reporter.myprovider.tier=Free
ai.reporter.myprovider.timeout.seconds=90
ai.reporter.myprovider.max.tokens=8192
ai.reporter.myprovider.temperature=0.3
```

The `tier` property sets the label shown in the dropdown (e.g. `My Provider (Free)`).

---

## Local LLM Support

The plugin supports **Ollama** — a local model runner — as a fully offline, free alternative to
cloud AI providers. No API key, no internet connection required after model download.

### Setup

1. **Install Ollama** from [https://ollama.com](https://ollama.com) (Windows / macOS / Linux).

2. **Pull a model:**
   ```bash
   ollama pull qwen2.5:7b     # ~5 GB — recommended for analytical tasks
   ollama pull mistral        # ~4 GB — strong reasoning
   ollama pull llama3.2       # ~2 GB — fast, good quality
   ```

3. **Add an Ollama block to `ai-reporter.properties`:**
   ```properties
   ai.reporter.ollama.api.key=ollama
   ai.reporter.ollama.model=qwen2.5:7b
   ai.reporter.ollama.base.url=http://localhost:11434/v1
   ai.reporter.ollama.timeout.seconds=180
   ai.reporter.ollama.max.tokens=8192
   ai.reporter.ollama.temperature=0.3
   ```

4. Select **ollama** from the provider dropdown and click **Generate AI Report**.

On CPU-only machines, set `timeout.seconds=180` or higher — generation can take 1–3 minutes.
Ensure at least 8 GB RAM free before pulling a 7B model.

---

## CLI Mode

Generate an AI performance report from the command line — no JMeter GUI required.

### Setup

Copy the wrapper scripts to your JMeter `bin/` directory:

```
<JMETER_HOME>/bin/jaar-cli-report.bat    ← Windows
<JMETER_HOME>/bin/jaar-cli-report.sh     ← macOS / Linux
```

### Quick Start

```bash
# Windows
jaar-cli-report.bat -i results.jtl --provider mistral --config ai-reporter.properties

# macOS / Linux
./jaar-cli-report.sh -i results.jtl --provider mistral --config ai-reporter.properties
```

### All Options

```
Required:
  -i, --input FILE            JTL file path
  --provider STRING           provider name (mistral, groq, gemini, deepseek,
                               cerebras, openai, claude, ollama, or custom)
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
```

### Exit Codes

| Code | Meaning                                       |
|------|-----------------------------------------------|
| `0`  | AI verdict **PASS**                           |
| `1`  | AI verdict **FAIL**                           |
| `2`  | AI verdict **UNDECISIVE**                     |
| `3`  | Invalid arguments                             |
| `4`  | JTL parse error                               |
| `5`  | AI provider error (key, ping, or API failure) |
| `6`  | Report write error                            |
| `7`  | Unexpected error                              |

### CI/CD Pipeline Example

```bash
./jaar-cli-report.sh \
  -i results.jtl -o report.html \
  --provider mistral --config /etc/jmeter/ai-reporter.properties \
  --start-offset 10 --end-offset 300 --percentile 95 \
  --scenario-name "Nightly Load Test" --virtual-users 200 \
  --error-sla 5 --rt-sla 2000 --rt-metric percentile

EXIT_CODE=$?
if [ $EXIT_CODE -eq 1 ]; then
  echo "Performance gate FAILED"
  exit 1
fi
```

On success, two lines are printed to stdout:

```
/absolute/path/to/report.html
VERDICT:PASS
```

All `[CLI]` progress messages go to stderr so stdout stays clean for scripting.

---

## Large JTL Files

The parser is fully streaming — it never loads the entire file into memory. However, JTL files
above ~500 MB require careful JVM configuration to avoid GC pressure.

| JTL Size | Recommended JVM Heap | Approx Parse Time (SSD) |
|----------|----------------------|-------------------------|
| < 100 MB | Default (256 MB)     | < 2s                    |
| 100 MB–500 MB | 512 MB          | 2–5s                    |
| 500 MB–1 GB | `-Xmx1g`          | 5–10s                   |
| 1–2 GB   | `-Xmx2g`             | 10–20s                  |

**GUI mode:** Set the JVM heap in `<JMETER_HOME>/bin/jmeter.bat` (Windows) or
`<JMETER_HOME>/bin/jmeter.sh` (macOS/Linux):

```bash
JVM_ARGS="-Xmx2g"
```

**CLI mode:** Pass the flag directly:

```bash
java -Xmx2g -jar jaar-jmeter-plugin-*.jar ...
```

> **Tip:** Use `--start-offset` and `--end-offset` to trim ramp-up/ramp-down before parsing —
> this reduces the effective row count significantly on long tests.

---

## Running Tests

```bash
# Build and run all tests
mvn clean verify

# Unit tests only
mvn test
```

---

## Troubleshooting

**The AI report is missing sections or shows a truncation warning.**
The AI provider reached its `max_tokens` limit. Increase the limit in `ai-reporter.properties`:

```properties
ai.reporter.<provider>.max.tokens=16000
```

**The plugin does not appear in JMeter's Add → Listener menu.**
Verify the JAR is in `<JMETER_HOME>/lib/ext/`. Restart JMeter after copying.

**The Generate AI Report button is greyed out.**
No configured provider found. Verify that `ai-reporter.properties` exists in
`<JMETER_HOME>/bin/` with at least one `api.key` set.

**"No Data" dialog appears when clicking Generate AI Report.**
No JTL file loaded. Click **Browse**, select a JTL file, and wait for the table to populate.

**Charts are blank in the HTML report.**
The report loads Chart.js from a CDN. Open the file in a browser with internet access.

**The table shows no rows after loading a JTL file.**
Check that Start/End Offset are not excluding all samples. Verify the filter mode is **Include**.

**The table shows one "unknown" row with all zeros.**
The configured delimiter does not match the JTL file. Check
`jmeter.save.saveservice.default_delimiter` in your JMeter properties files.

**"Unrecognised timestamp format" error when loading a JTL file.**
The JTL uses a formatted timestamp (e.g. `2026/03/20 14:08:39`). Set the format in
`<JMETER_HOME>/bin/user.properties`:

```properties
jmeter.save.saveservice.timestamp_format=yyyy/MM/dd HH:mm:ss
```

Supported patterns: `yyyy/MM/dd HH:mm:ss.SSS`, `yyyy/MM/dd HH:mm:ss`,
`yyyy-MM-dd HH:mm:ss.SSS`, `yyyy-MM-dd HH:mm:ss`, `MM/dd/yyyy HH:mm:ss.SSS`,
`MM/dd/yyyy HH:mm:ss`. Epoch-millisecond JTLs need no configuration.

**The AI report times out.**
Increase `timeout.seconds` for the provider. Default is 60 seconds; for large JTL files or
local models, 120–300 seconds is recommended.

**API key rejected — "HTTP 401" error.**
The `api.key` value is incorrect or revoked. Update it in `ai-reporter.properties` — the plugin
re-reads the file on every generate click, no restart needed.

**Rate limit exceeded — "HTTP 429" error.**
Wait a moment and retry, or switch to a different provider in the dropdown.

**Ollama: "Could not connect" error.**
Start Ollama with `ollama serve` and verify it is reachable at `http://localhost:11434`.

**Out of memory or slow parsing on large JTL files.**
See the [Large JTL Files](#large-jtl-files) section for JVM heap recommendations.

---

## Contributing

Bug reports and pull requests are welcome via
[GitHub Issues](https://github.com/sagaraggarwal86/jaar-jmeter-plugin/issues).

Before submitting a pull request:

- Run `mvn clean verify` and confirm all tests pass
- Test manually with JMeter 5.6.3 on your platform
- Keep each pull request focused on a single change

---

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.