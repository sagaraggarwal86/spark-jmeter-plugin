# CLAUDE.md

## Prohibitions [STRICT]

- Target JMeter 5.6.3 exclusively — verify all APIs, interfaces, and classes exist in 5.6.3 before using them
- Never change git history or Java 17 implementation
- Never assume — ask if in doubt
- Never make changes to code until user confirms
- Never change existing functionality or make changes beyond confirmed scope
- Only recommend alternatives when there is a concrete risk or significant benefit
- Analyze impact across dependent layers (parser → listener.core → listener.gui → ai → cli) before proposing changes
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**

## Workflow

- Interactive session — present choices one by one, unless changes are trivial and clearly scoped
- If my choices severely impact application integrity or cause excessive changes, briefly explain consequences and
  recommend better alternatives
- After all changes are finalized, self-check for regressions, naming consistency, and adherence to these rules before
  presenting files
- Multi-file changes: present all files together with dependency order noted
- Conflicting requirements: flag the conflict, pause, and wait for decision
- Rollback: revert to last explicitly approved file set, then ask how to proceed
- If context grows large, summarize confirmed state before continuing

## Response Style

- Concise — no filler phrases, no restating the request, no vague or over-explanatory content

## Communication

- Always provide honest feedback — flag risks, trade-offs, or better alternatives even if the user didn't ask.
  Do not agree silently if there is a concrete concern. Be direct, not diplomatic.
- For every decision point or design choice, present options in a concise table:

  | Option | Risk | Effort | Impact | Recommendation |
        |--------|------|--------|--------|----------------|

  Highlight the recommended option. Keep descriptions brief — one line per cell.

## Self-Maintenance

- **Auto-optimize CLAUDE.md**: After any session that adds or modifies design decisions, constraints, or architectural
  details in this file, review CLAUDE.md for redundancy, stale entries, and verbosity. Remove duplicates, compress
  verbose entries, and ensure every line carries actionable information. Do not wait for the user to request this.
- **Auto-compact**: When the conversation context grows large (many tool calls, long code reads, repeated file edits),
  proactively suggest `/compact` to the user before context becomes unwieldy. Do not wait until context is nearly full.
- **Auto-update README.md**: After any session that adds, removes, or modifies user-facing features (filters, columns,
  report panels, CLI options, configuration), update README.md to reflect the change. Keep feature tables, filter docs,
  GUI overview, and configuration sections current. Do not wait for the user to request this.

## Role

- Act as a senior full-stack Java engineer with DevOps, QA, security, architecture, technical documentation, and UI/UX
  expertise

## Skill Set

- JMeter specialist (distributed systems, JVM tuning, network diagnostics, load testing analysis)
- Java 17 application design & development using Maven
- JMeter Plugin development for JMeter 5.6.3
- CI/CD pipelines, GitHub integration
- Complex Java system design
- Concise & unambiguous documentation
- UI/UX design, Project management
- Exception handling, Performance engineering

## Build Commands

```bash
mvn clean verify                          # Build + tests + JaCoCo coverage check
mvn clean package -DskipTests             # Build only
mvn test -Dtest=JTLParserTest             # Single test class
mvn test -Dtest=JTLParserTest#testParse   # Single test method
mvn clean deploy -Prelease                # Release to Maven Central
```

Requirements: JDK 17 only, Maven 3.6+. JaCoCo enforces **80%** line coverage excluding `listener.gui/**`,
`ai.report/**`,
`AiProviderRegistry`, `SharedHttpClient`, `Main`, `TimestampFormatResolver`.

## Architecture

JMeter listener plugin for post-test analysis of JTL files. Loads JTL files and generates filterable aggregate metrics
tables, CSV exports with SLA status, and AI-powered HTML performance reports via any OpenAI-compatible LLM provider.
All processing is file-based with zero runtime overhead — no live metrics collection during test execution.

### Package Structure

| Package         | Responsibility                                                                                                                                                                                                                                             |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `parser`        | `JTLParser` (two-pass CSV parser), `JtlParserCore` (static utilities), `DelimiterResolver`, `TimestampFormatResolver`, `JtlParseException`                                                                                                                 |
| `listener.core` | `ColumnIndex` (13-column constants), `CellValueParser`, `TablePopulator` (row formatting + sorting), `TransactionFilter`, `SlaConfig`, `SlaRowRenderer`, `CsvExporter`, `ScenarioMetadata`                                                                 |
| `listener.gui`  | `ListenerGUI` (extends AbstractVisualizer), `ListenerCollector` (extends ResultCollector, sampleOccurred=no-op), `AggregateReportPanel` (~958 lines, known SRP debt), `ReportPanelBuilder`, `FilePanelCustomizer`, `AiReportLauncher`, `SimpleDocListener` |
| `ai.prompt`     | `PromptLoader`, `PromptContent` (record), `PromptRequest` (record), `PromptBuilder` (pre-computed verdicts + classification)                                                                                                                               |
| `ai.provider`   | `AiProviderConfig`, `AiProviderRegistry` (7 providers + custom), `AiReportService` (OpenAI-compatible API), `SharedHttpClient` (singleton), `AiProviderException`, `AiServiceException`                                                                    |
| `ai.report`     | `AiReportCoordinator` (orchestrator), `HtmlReportRenderer`, `HtmlPageBuilder`, `MarkdownSectionNormaliser`, `MarkdownUtils`                                                                                                                                |
| `cli`           | `Main` (exit codes 0-7), `CliArgs`, `CliReportPipeline`                                                                                                                                                                                                    |

### Key Design Decisions

- **File-based, zero runtime overhead**: No live metrics collection. JTL files loaded and analyzed post-test.
- **Two-pass JTL parser**: Pass 1 for label discovery, sub-result detection, timestamps. Pass 2 for aggregation,
  time buckets, error classification. Handles 1-2 GB+ files via streaming.
- **Pre-computed analysis**: All verdicts (PASS/FAIL), classifications (THROUGHPUT-BOUND, LATENCY-BOUND, ERROR-BOUND,
  CAPACITY-WALL), SLA evaluations computed in Java — AI writes prose justifying pre-computed results, never computes
  verdicts itself.
- **Shading strategy**: Only Gson and CommonMark shaded into fat JAR. JMeter-provided deps never bundled.
- **Provider abstraction**: `AiProviderRegistry` + `AiProviderConfig` + `AiReportService` abstracts provider quirks
  (Cerebras prefill suppression, token limits, model variants). 7 known providers + unlimited custom endpoints.
- **Single source of truth**: `TablePopulator.buildRowAsStrings` for row formatting (GUI + CLI),
  `ColumnIndex.ALL_COLUMNS` for column ordering.
- **sampleOccurred no-op**: `ListenerCollector` prevents live write-back to source JTL file.

### AI Analysis Architecture

- **Java does ~95% of work**: Transaction metrics table, SLA verdicts, classification summary, error/RT SLA summaries,
  anomaly detection, slowest endpoints — all pre-computed.
- **AI does ~5%**: Generates narrative prose across 9 report sections (Executive Summary, Bottleneck Analysis,
  Error Analysis, Advanced Web Diagnostics, Root Cause Hypotheses, Recommendations, etc.).
- **7 providers**: groq, gemini, mistral, deepseek, cerebras, openai, claude. Shared `ai-reporter.properties` config.
- **CLI exit codes**: 0=PASS, 1=FAIL, 2=UNDECISIVE, 3=bad args, 4=parse error, 5=AI error, 6=write error, 7=unexpected.

### Key Constraints

- `ListenerCollector` property key strings stored in `.jmx` files — renaming breaks backward compatibility.
- `ColumnIndex.ALL_COLUMNS` array order is fixed — CSV export and HTML column mapping depend on positional indices.
- Exit codes (0-7) documented in README and used by CI scripts — changing them breaks CI/CD.
- `SECTION_SKELETON` and `EXPECTED_SECTION_HEADINGS` in `AiReportService` are part of the provider contract.
- Column 0 (Transaction Name) must always remain visible.
- `JTLParser.TOTAL_LABEL = "TOTAL"` must never be excluded from results map.
- Cerebras provider must NOT receive assistant prefill.
- JSONL schema field names are public contract — renaming is a breaking change.
- `PromptContent` must be built on EDT (JMM visibility for `SamplingStatCalculator` values).

### Reference Architecture

Refer to `docs/JAAR_session_prompt.md` for the complete architectural reference including all class APIs,
data flow diagrams, established conventions, coding standards, naming rules, patterns, open items, and change log.
