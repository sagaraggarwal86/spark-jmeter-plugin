# JAAR — JTL AI Analysis & Reporting · Session Prompt for Claude Sonnet 4.6

---

## 1. Project Snapshot

| Field                  | Value                                                                                                                                                                   |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Artifact**           | `io.github.sagaraggarwal86:jaar-jmeter-plugin`                                                                                                                          |
| **Version**            | `5.0.1` (tag `v5.0.1`)                                                                                                                                                  |
| **Language / Build**   | Java 17, Maven                                                                                                                                                          |
| **JMeter target**      | 5.6.3 (provided scope)                                                                                                                                                  |
| **Distributable**      | Single fat JAR → `JMeter/lib/ext/` or Maven Central                                                                                                                     |
| **Package root**       | `io.github.sagaraggarwal86.jmeter`                                                                                                                                      |
| **Test framework**     | JUnit 5 · Mockito 5 · JaCoCo (80 % line coverage floor)                                                                                                                 |
| **Shaded deps**        | Gson 2.13.2, CommonMark 0.27.1 (no transitive exposure to JMeter classloader)                                                                                           |
| **Provided deps**      | ApacheJMeter_core, ApacheJMeter_components, jorphan, slf4j-api                                                                                                          |
| **Test-only deps**     | logback-classic                                                                                                                                                         |
| **CI/CD**              | GitHub Actions — `build.yml` (matrix: windows + ubuntu, `mvn clean verify -Dgpg.skip=true`) · `release.yml` (deploys to Maven Central on `v*.*.*` tag via `-P release`) |
| **CLI entry point**    | `io.github.sagaraggarwal86.jmeter.cli.Main` (wrapped by `jaar-cli-report.sh/.bat`)                                                                                      |
| **JMeter entry point** | `io.github.sagaraggarwal86.jmeter.gui.listener.ListenerGUI` (registered via `META-INF/services/org.apache.jmeter.gui.JMeterGUIComponent`)                               |
| **Properties file**    | `$JMETER_HOME/bin/ai-reporter.properties` (user-editable)                                                                                                               |
| **Bundled resources**  | `ai-reporter-prompt.txt` (system prompt), `ai-reporter.properties` (template)                                                                                           |

---

## 2. Architecture Map

### 2.1 `parser` package

| Class                     | Role                                                                                                                                                                                                                         | Key API                                                       |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| `JTLParser`               | Two-pass CSV parser. Pass 1: label discovery, sub-result detection, min/max timestamp, timestamp format resolution. Pass 2: aggregation into `SamplingStatCalculator`, time buckets, error-type count, latency accumulators. | `parse(filePath, FilterOptions) → ParseResult`                |
| `JTLParser.FilterOptions` | Mutable options bag: include/exclude labels, offsets, percentile, delimiter, generateParentSample, includeTimerDuration, chartIntervalSeconds, timestampFormatter, minTimestamp (set internally).                            | Public fields                                                 |
| `JTLParser.ParseResult`   | Immutable output: `results` (per-label `SamplingStatCalculator`), `startTimeMs`, `endTimeMs`, `durationMs`, `timeBuckets`, `errorTypeSummary` (top-5), `avgLatencyMs`, `avgConnectMs`, `latencyPresent`.                     | Public final fields + `formattedStartTime/EndTime/Duration()` |
| `JTLParser.TimeBucket`    | Value object: epochMs, avgResponseMs, errorPct, tps, kbps.                                                                                                                                                                   | Public final fields                                           |
| `JtlParserCore`           | Package-private statics: `splitCsvLine`, `extractPass1Fields`, `parseLineTokens`, `parseElapsedTokens`, `buildColumnMap`, `detectTimestampFormatter`, `parseTimestampMs`, `buildTimeBuckets`, `shouldInclude`.               | Static utility                                                |
| `DelimiterResolver`       | Reads `jmeter.save.saveservice.default_delimiter` from `user.properties` (priority) → `jmeter.properties` → default `,`.                                                                                                     | `resolve(File jmeterHome)`                                    |
| `TimestampFormatResolver` | Reads `jmeter.save.saveservice.timestamp_format` from same files. Returns `null` for epoch-ms mode.                                                                                                                          | `resolve(File jmeterHome)`                                    |
| `JtlParseException`       | Extends `IOException` — maps to `EXIT_PARSE_ERROR (4)` in `Main`.                                                                                                                                                            |                                                               |

**Sub-result detection** (two independent algorithms):

1. Consecutive-row: prev row has empty `dataType` (Transaction Controller), current has non-empty `dataType`, same
   `elapsed`, timestamps within 1 ms → current is a sub-result.
2. Numeric-suffix: `"Foo-1"`, `"Foo-2"` when parent `"Foo"` exists in labels.

**Auto bucket sizing** (chartIntervalSeconds == 0): `duration / 120` snapped up to nearest of
`[10s, 30s, 60s, 120s, 300s, 600s, 1800s, 3600s]`. Anchored to `options.minTimestamp` (not epoch) to avoid partial first
bucket.

**Partial bucket filter**: drops buckets whose effective coverage (overlap with test range) < 90 % of bucket size,
unless test fits within a single bucket.

---

### 2.2 `listener.core` package

| Class               | Role                                                                                                                                                                                                                                                  |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ColumnIndex`       | Constants: `ALL_COLUMNS` (13-element string array), `PERCENTILE_COL_INDEX=7`, `AVG_COL_INDEX=4`, `ERROR_RATE_COL_INDEX=9`, `NAME_COL_INDEX=0`. Single source of truth for column ordering.                                                            |
| `CellValueParser`   | Shared parse utils: `parseErrorRate(Object/String)` (strips `%`), `parseDouble(Object/String)`, `parseMs(String)` (strips `,`). Eliminates duplication across `CsvExporter`, `SlaRowRenderer`, `HtmlReportRenderer`.                                  |
| `SlaConfig`         | Immutable SLA snapshot: `errorPctThreshold` (-1=disabled), `rtMetric` (AVG/PNN), `rtThresholdMs` (-1=disabled), `percentile`. Factory: `from(errorPctStr, rtThresholdStr, rtMetric, percentile)` / `disabled(percentile)`.                            |
| `SlaRowRenderer`    | `DefaultTableCellRenderer` — highlights error-rate or RT cell in red bold when SLA is breached. Reads live `SlaConfig` via `Supplier<SlaConfig>`. Skips TOTAL row.                                                                                    |
| `TablePopulator`    | Table population, sorting (per-column cycle ↕→↑→↓→↕), column visibility toggle. `buildRowAsStrings(calc, pFraction)` is the single source of truth for row formatting (used by both GUI and CLI). `getVisibleRows()` returns TOTAL-excluded snapshot. |
| `TransactionFilter` | Case-insensitive substring or regex match. Pattern cache (max 100 entries, full-clear eviction).                                                                                                                                                      |
| `CsvExporter`       | CSV write via save dialog. Includes `Error% SLA` / `RT SLA` columns (PASS/FAIL/-) when SLA is configured. RFC 4180 escaping via `escapeCSV`.                                                                                                          |
| `ScenarioMetadata`  | Value object: `scenarioName`, `scenarioDesc`, `users`, `threadGroupName`.                                                                                                                                                                             |

---

### 2.3 `listener.gui` package

| Class                  | Role                                                                                                                                                                                                                                                                                                                                                                                                               |
|------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ListenerGUI`          | Extends `AbstractVisualizer`. JMeter lifecycle contracts (`configure`, `modifyTestElement`, `clearGui`, `add` no-op). Wires `FilePanelCustomizer` for Browse override and Enter-key loading. Reads test-plan metadata via `GuiPackage` for AI report. Delegates all UI/logic to `AggregateReportPanel`.                                                                                                            |
| `ListenerCollector`    | Extends `ResultCollector`. Holds property key constants (`PROP_*`). Overrides `sampleOccurred` as no-op to prevent live write-back to JTL file.                                                                                                                                                                                                                                                                    |
| `AggregateReportPanel` | Extends `JPanel`. Central UI controller (~958 lines — **known design debt**). Owns all Swing fields. Delegates to `ReportPanelBuilder` (layout), `TablePopulator` (data), `CsvExporter` (save), `AiReportLauncher` (AI workflow). Manages `aiExecutor` lifecycle via `addNotify`/`removeNotify`. 300 ms debounce on offset/chart-interval fields. Inner `PanelDataProvider` wires `AiReportLauncher.DataProvider`. |
| `AiReportLauncher`     | Orchestrates AI report button: validates provider, builds `PromptContent` on EDT (JMM safety), submits to background executor, shows progress dialog, wires cancellation via `AtomicBoolean` + `AtomicReference<Thread>`.                                                                                                                                                                                          |
| `ReportPanelBuilder`   | Pure UI construction: filter panel, time-info panel, SLA panel, table scroll pane, column dropdown, sort-arrow header renderer.                                                                                                                                                                                                                                                                                    |
| `FilePanelCustomizer`  | Component-tree surgery on JMeter's built-in `FilePanel`: hides irrelevant controls, overrides Browse action, wires Enter key on filename field.                                                                                                                                                                                                                                                                    |
| `SimpleDocListener`    | `@FunctionalInterface` extending `DocumentListener` — routes all three events to `onUpdate()`.                                                                                                                                                                                                                                                                                                                     |

**State persistence** (all via `ListenerCollector` properties stored in `.jmx`):
`startOffset`, `endOffset`, `percentile`, `errorPctSla`, `rtThresholdSla`, `rtMetric`, `chartInterval`, `search`,
`regex`, `filterMode`, `lastFile`, `colVisibility`.

**Load vs. restore distinction**: `loadJtlFile()` always resets SLA fields; `loadJtlFileForRestore()` does not (SLA
fields already restored from `TestElement` by `configure()` before this is called).

---

### 2.4 `ai.prompt` package

| Class                          | Role                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `PromptLoader`                 | Reads `/ai-reporter-prompt.txt` from JAR classpath. Returns `null` on missing/empty resource.                                                                                                                                                                                                                                                                                                                                                                                             |
| `PromptContent`                | Immutable record: `systemPrompt` + `userMessage`.                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `PromptRequest`                | Immutable record: scenario context fields (users, name, desc, times, percentile, SLA strings). Null normalised to `""` / `"Not configured"` in compact canonical constructor.                                                                                                                                                                                                                                                                                                             |
| `PromptBuilder`                | Builds `PromptContent` from `SamplingStatCalculator` map. Single-pass over results; computes: `globalStats`, `anomalyTransactions`, `errorEndpoints` (capped 15), `slowestEndpoints` (threshold-based: medianP90 × 1.5, floor 3, cap 15), `allTransactionStats`, `tpsSeries`, `rtSlaSummary`, `errorSlaSummary`, `classificationSummary`, `overallVerdictSummary`, `mandatedHypothesisTargets`. All SLA verdicts and classification are pre-computed in Java — not delegated to AI model. |
| `PromptBuilder.LatencyContext` | Record: `avgLatencyMs`, `avgConnectMs`, `latencyPresent`. Sentinel: `ABSENT`.                                                                                                                                                                                                                                                                                                                                                                                                             |

**Classification logic** (Java, not AI):

- ERROR-BOUND: errorRatePct > 2.0 %
- CAPACITY-WALL: plateaued AND latencyRatio > 3.0
- LATENCY-BOUND: !plateaued AND latencyRatio > 3.0 AND errorRatePct ≤ 2.0 %
- THROUGHPUT-BOUND: plateaued AND latencyRatio ≤ 3.0 AND errorRatePct ≤ 2.0 % (also default)

Plateau: tailAvgTps / peakTps ≥ 0.90 (tail = last 25 % of tpsSeries).

---

### 2.5 `ai.provider` package

| Class                 | Role                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AiProviderConfig`    | Immutable value: `providerKey`, `displayName`, `apiKey`, `model`, `baseUrl` (trailing slash stripped), `timeoutSeconds`, `maxTokens`, `temperature`. `chatCompletionsUrl()` appends `/chat/completions`.                                                                                                                                                                                                                                          |
| `AiProviderRegistry`  | Loads `ai-reporter.properties` (disk → JAR fallback). Discovers all providers with non-blank `api.key`. Orders by `ai.reporter.order` property → `KNOWN_PROVIDERS` → alphabetical. Structural key format check (`gsk_`, `sk-`, `sk-ant-`, `AIza`, `csk-`). Live ping with `ConcurrentHashMap` cache (composite key `providerKey:apiKey`).                                                                                                         |
| `AiReportService`     | Calls `/chat/completions`. 3 attempts, 2 s delay, retries HTTP 429 + 5xx only. Assistant prefill (`## Executive Summary\n\n`) injected for all providers **except Cerebras** (prefill causes Cerebras to produce only 1 section on FAIL scenarios). Truncation detection via `finish_reason=length` and `usage.completion_tokens >= maxTokens`. Appends graceful notice for truncation + missing sections. Validates 7 expected section headings. |
| `SharedHttpClient`    | Singleton `HttpClient` with 15 s connect timeout. Shared by `AiReportService` and `AiProviderRegistry`.                                                                                                                                                                                                                                                                                                                                           |
| `AiProviderException` | Extends `IOException` → maps to `EXIT_AI_ERROR (5)`. Configuration-time failure.                                                                                                                                                                                                                                                                                                                                                                  |
| `AiServiceException`  | Extends `IOException` → maps to `EXIT_AI_ERROR (5)`. Runtime API failure.                                                                                                                                                                                                                                                                                                                                                                         |

**Known providers**: groq, gemini, mistral, deepseek, cerebras, openai, claude. Models and base URLs defined as maps in
`AiProviderRegistry`. Custom providers supported via `ai.reporter.<key>.*` properties.

---

### 2.6 `ai.report` package

| Class                       | Role                                                                                                                                                                                                                                                                                                                                                          |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AiReportCoordinator`       | Orchestrates background workflow: `AiReportService.generateReport` → `MarkdownSectionNormaliser.normalise` → `MarkdownUtils.extractVerdict` + `stripVerdictLine` → `HtmlReportRenderer.renderToFile`. Manages cancellation (`AtomicBoolean`), EDT callbacks, progress label, save-file dialog (`SwingUtilities.invokeAndWait`). Evicts ping cache on 401/403. |
| `HtmlReportRenderer`        | Converts Markdown to HTML, builds transaction metrics table (with optional SLA PASS/FAIL columns), builds charts section, assembles full page via `HtmlPageBuilder`. Atomic file write (`.tmp` → ATOMIC_MOVE → REPLACE_EXISTING fallback). 10 MB disk-space check.                                                                                            |
| `HtmlPageBuilder`           | Pure HTML/JS generation: tab layout with sidebar navigation, metadata grid, Chart.js 4.4.1 (CDN), SheetJS 0.18.5 (CDN). Excel export (one sheet per tab). GFM pipe-table → HTML conversion. Raw HTML stripping from AI output. PASS/FAIL/classification token styling. Chart.js instances created outside panel to avoid 0×0 canvas bug on hidden init.       |
| `MarkdownSectionNormaliser` | Removes duplicate section headings (earlier occurrences only) — safety net for Mistral's skeleton prefill echo.                                                                                                                                                                                                                                               |
| `MarkdownUtils`             | `extractVerdict`: two-pass — canonical (last non-blank line for `VERDICT:PASS/FAIL`) → fallback (first `BRIEF_VERDICT:PASS/FAIL`). `verdictSource`: `CANONICAL` / `FALLBACK` / `UNDECISIVE`. `stripVerdictLine`: removes all lines starting with `VERDICT:` or `BRIEF_VERDICT:`. Handles emphasis-wrapped tokens (`**VERDICT:FAIL**`).                        |

**Report section order**: Executive Summary → Transaction Metrics → Bottleneck Analysis → Error Analysis → Advanced Web
Diagnostics → Root Cause Hypotheses → Recommendations → Performance Charts → Verdict.

**File output**: `JAAR_<Provider>_Report_<yyyyMMddHHmmss>.html` (UI: save dialog defaulting to JTL directory; CLI:
`--output` arg).

---

### 2.7 `cli` package

| Class               | Role                                                                                                                                                              |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Main`              | Entry point. Initialises JMeter properties from classpath. Maps exceptions to typed exit codes (0–7).                                                             |
| `CliReportPipeline` | Headless pipeline: parse → build table rows → resolve provider → validate/ping → load prompt → call AI → extract verdict → render HTML. Progress to `System.err`. |
| `CliArgs`           | Plain `String[]` parser. Validates required fields, ranges, `--regex`/`--exclude` require `--search`. Normalises provider to lowercase.                           |

**Exit codes**: 0=PASS, 1=FAIL, 2=UNDECISIVE, 3=bad args, 4=parse error, 5=AI error, 6=write error, 7=unexpected.

---

### 2.8 Data flow summary

```
JTL file
  ↓ DelimiterResolver + TimestampFormatResolver (jmeter.properties)
  ↓ JTLParser.parse() → ParseResult
  ↓
TablePopulator.buildRowAsStrings()  →  List<String[]> tableRows
  ↓
PromptBuilder.build()               →  PromptContent (system + user messages)
  ↓
AiReportService.generateReport()    →  Markdown string
  ↓
MarkdownSectionNormaliser.normalise()
MarkdownUtils.extractVerdict() + stripVerdictLine()
  ↓
HtmlReportRenderer.renderToFile()   →  .html file
```

---

## 3. Established Conventions & Constraints

### Coding standards

- **SRP limit**: 300 lines per class (AggregateReportPanel at ~958 lines is the only documented exception — deferred
  until GUI test harness exists).
- **Static utility classes**: `private` no-arg constructor, `final` class, all-static methods.
- **Immutable value objects**: Records or final classes with `Objects.requireNonNull` in constructors.
- **Single source of truth**: `ColumnIndex.ALL_COLUMNS` for column names; `TablePopulator.buildRowAsStrings` for row
  formatting; `ParseResult.DISPLAY_TIME_FORMAT` for date display.
- **Defensive field guards**: Null → empty string via `Objects.requireNonNullElse`. Numeric defaults via checked parse
  helpers.
- **Section headings separator**: `// ─────────────────────────────────────────────────────────────` used consistently
  before every logical section.
- **CHANGED comments**: Changed lines annotated `// CHANGED` with explanation.
- **Javadoc**: All public and package-private methods fully documented.

### Naming rules

- Package: `io.github.sagaraggarwal86.jmeter.<module>` (never change)
- Test classes: `<SubjectClass>Test.java` or `<SubjectClass>StaticsTest.java`
- Properties keys in `ListenerCollector`: `PROP_*` constants (e.g., `PROP_START_OFFSET`)
- AI provider keys: lowercase (`groq`, `openai`, `claude`, etc.)

### Patterns in use

- **Supplier injection**: `SlaConfig`, `ScenarioMetadata`, file path read via `Supplier<T>` to capture live state.
- **DataProvider callback interface**: `AiReportLauncher.DataProvider` decouples launcher from `AggregateReportPanel`
  private fields.
- **Two-pass parse**: Pass 1 for metadata; Pass 2 for aggregation. `colMap` built once, reused in Pass 2.
- **Pre-flag optimization**: `hasInclude`, `hasExclude`, `hasOffset` computed once before Pass 2 hot loop.
- **Atomic file write**: `.tmp` → ATOMIC_MOVE → REPLACE_EXISTING fallback.
- **Debounce timer**: 300 ms `javax.swing.Timer` on offset and chart-interval fields.
- **Ping cache**: Composite key `providerKey:apiKey` invalidates on key rotation.

### Hard constraints

- **Never change Java version** (must remain Java 17).
- **Never alter git history**.
- **Never expose shaded dependencies** as transitive deps to JMeter's classloader.
- **Fat JAR is the only distributable artifact** — no multi-module split.
- **`ListenerCollector.sampleOccurred` must remain a no-op** — prevents live write-back to source JTL.
- **`AggregateReportPanel` must extend `JPanel`** — required by JMeter AbstractVisualizer integration.
- **`ListenerGUI` must extend `AbstractVisualizer`** — JMeter plugin contract.
- **`TablePopulator.buildRowAsStrings` is the single source of truth for row formatting** — both GUI and CLI must use
  it; never duplicate the formatting logic.
- **`JMETER_HOME` resolution**: from `System.getProperty("jmeter.home")` → `System.getenv("JMETER_HOME")` in GUI mode;
  from config-file parent path → env var in CLI mode.
- **PromptContent must be built on EDT** (JMM visibility for `SamplingStatCalculator` values).
- **Cerebras provider must NOT receive assistant prefill** — causes single-section output on FAIL scenarios.
- **`JTLParser.TOTAL_LABEL = "TOTAL"` must never be excluded from `results` map** (callers skip it via `equals` check,
  not by removal).
- **Column 0 (Transaction Name) must always remain visible** — `ReportPanelBuilder.buildColumnDropdown` disables its
  checkbox.

### What must never change

- `ListenerCollector` property key strings — stored in `.jmx` files; renaming breaks backward compatibility.
- `ColumnIndex.ALL_COLUMNS` array order — CSV export and HTML table column mapping depend on positional indices.
- Exit codes in `Main` — documented in README and used by CI scripts.
- `SECTION_SKELETON = "## Executive Summary\n\n"` in `AiReportService` — prefill contract.
- `EXPECTED_SECTION_HEADINGS` in `AiReportService` — section validation and truncation detection.
- `EXPECTED_HEADINGS` in `MarkdownSectionNormaliser` — must mirror `EXPECTED_SECTION_HEADINGS`.

---

## 4. Current State — Open Items

| # | Area           | Description                                                                                                                                                                                                                    | Status                                 |
|---|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| 1 | `listener.gui` | `AggregateReportPanel` exceeds 300-line SRP guideline (~958 lines). Splitting requires a dedicated headless/AssertJ-Swing GUI test strategy. Documented in class Javadoc.                                                      | [ ] Open — deferred                    |
| 2 | `listener.gui` | No test coverage for GUI classes (excluded from JaCoCo). `AggregateReportPanel`, `ListenerGUI`, `ReportPanelBuilder`, `FilePanelCustomizer`, `AiReportLauncher`, `AiReportCoordinator`, `HtmlReportRenderer` are all excluded. | [ ] Open — blocked on GUI test harness |
| 3 | `ai.provider`  | Cerebras prefill suppression is a workaround for provider-specific behaviour (qwen-3-235b-a22b-instruct-2507 terminates early with prefill on FAIL scenarios). If Cerebras updates the model, this may need re-testing.        | [ ] Open — monitor                     |
| 4 | `parser`       | `TimestampFormatResolver` excluded from JaCoCo coverage (requires live JMeter properties files). No unit tests for it.                                                                                                         | [ ] Open                               |
| 5 | `listener.gui` | `providerCombo` null-item placeholder when no providers configured uses a null-item workaround rather than a custom `ListCellRenderer`.                                                                                        | [ ] Open — low priority                |
| 6 | `build`        | JaCoCo coverage floor is 80 %. If coverage drops below this threshold the build fails. Any new code without tests will fail CI.                                                                                                | [ ] Ongoing gate                       |
| 7 | `ai.report`    | `HtmlReportRenderer.parseErrorRate` and `parseMs` are private duplicates of `CellValueParser` methods — not yet migrated to shared utility in this class.                                                                      | [ ] Open — minor                       |

---

## 5. Change Log (append-only)

| Session Date              | Area Changed | Description | Status |
|---------------------------|--------------|-------------|--------|
| <!-- append rows here --> |              |             |        |

---

### How to update the Change Log

At the end of every session, paste the following into the chat:

```
Update Change Log: [date] | [area] | [what changed] | [done/in-progress]
```

Claude will append a new row to the table and confirm. Never edit past rows.

---

## 6. Standing Instructions for Claude

1. **Never change git history or Java 17 implementation.**
2. **Never assume — ask if in doubt.**
3. **Never make code changes until explicitly confirmed.**
4. **Never change existing functionality beyond confirmed scope.**
5. **If uncertain, state uncertainty explicitly and ask.**
6. **Only recommend alternatives when there is a concrete risk or significant benefit.**
7. **Interactive session — present choices one at a time, unless changes are trivial and clearly scoped.**
8. **If choices severely impact application integrity or cause excessive changes, briefly explain consequences and
   recommend better alternatives.**
9. **After all changes are finalised, self-check for regressions, naming consistency, and adherence to these rules
   before presenting files.**
10. **Analyse impact across dependent layers (API → service → model) before proposing changes.**
11. **Code changes: present full file with changes marked as `// CHANGED`.**
12. **Multi-file changes: present all files together with dependency order noted.**
13. **Conflicting requirements: flag the conflict, pause, and wait for decision.**
14. **Rollback: revert to last explicitly approved file set, then ask how to proceed.**
15. **Align test expectations to production behaviour, not the reverse.**
16. **All public/package-private methods must have Javadoc.**
17. **Any new class exceeding 300 lines triggers an SRP review comment.**
18. **New shaded deps must be explicitly approved — classloader isolation is critical.**
19. **Never add a GUI-level dependency to `listener.core` or `parser` packages.**
20. **Maintain 80 % JaCoCo line coverage — do not add production code without corresponding tests unless the class is
    already in the JaCoCo exclusion list.**

---

## 7. Session Kick-off Template

Copy and paste at the start of each new session:

```
Context:
  Project: JAAR — JTL AI Analysis & Reporting (io.github.sagaraggarwal86:jaar-jmeter-plugin)
  Version: 5.0.1 | Java 17 | Maven | JMeter 5.6.3
  Packages: parser · listener.core · listener.gui · ai.prompt · ai.provider · ai.report · cli
  Coverage floor: 80% (JaCoCo). GUI classes excluded from coverage.
  Key constraint: Fat JAR only; no transitive dep exposure; AggregateReportPanel is known design debt (~958 lines).

Today's goal: [GOAL]

Constraints: [any session-specific limits, e.g. "no new dependencies", "GUI-only changes", "tests must pass on Windows + Ubuntu"]

Reference the Change Log and Open Items before proposing anything.
```
