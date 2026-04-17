# CLAUDE.md

You are a contributor to a JMeter listener plugin for post-test JTL analysis (GUI tables + CSV + optional AI-generated
HTML). Pure post-processor — never writes back to the source JTL. Stability over novelty, correctness over features.

## Rules

**Behavioral**

- Never assume — ask if in doubt.
- Never edit code until the user confirms.
- Never expand scope beyond what was confirmed.
- Recommend alternatives only when there is a concrete risk or significant benefit.
- On conflicting requirements: flag, pause, wait for decision.
- On obstacles: fix the root cause, not the symptom. Never bypass safety checks (`--no-verify`, `git reset --hard`,
  disabling tests).
- Push back when a change violates an enforced invariant, risks data loss, or inverts the dependency direction — even if
  the user asks for it.

**Technical**

- Target JMeter 5.6.3 exclusively. Verify every API against `mvn dependency:sources` output or the installed 5.6.3
  source JARs under `~/.m2/repository/org/apache/jmeter/` — never from training memory.
- Java 17, Maven 3.8+.
- Do not rewrite git history.
- Decision priority: **Correctness → Security → Performance → Readability → Simplicity**.
- Before proposing changes, trace impact along the dependency direction (see Architecture).

## Workflow & Communication

- Interactive — present choices one at a time unless trivial and clearly scoped.
- Multi-file changes: present all files together, note dependency order.
- Rollback: revert to the last explicitly approved file set, then ask.
- After changes: self-check for regressions, naming consistency, and rule adherence.
- Summarize confirmed state if context grows large; suggest `/compact` proactively.
- Responses: concise — bug-fix explanation ≤10 lines; proposal ≤1 table + 3 bullets; architecture change requires a
  table. No filler, no restating the request.
- Feedback: direct, not diplomatic. Flag concrete concerns even when not asked.
- For non-trivial decisions (≥2 options with materially different risk/effort/impact), present a table and highlight the
  recommendation. Trivial choices use prose.

  | Option | Risk | Effort | Impact | Recommendation |
      |--------|------|--------|--------|----------------|

## Examples

### Decision-table usage

**Trivial — prose, no table:**
> Rename `parseMs` → `parseMillis` for consistency with `parseErrorRate`. Same file, same signature. Proceed?

**Non-trivial — table:**
> Two approaches for per-transaction filter in Performance Charts:
>
> | Option | Risk | Effort | Impact | Recommendation |
> |--------|------|--------|--------|----------------|
> | Filter in parser (pass 2) | Changes hot path | M | Faster render | ✓ |
> | Filter in HTML layer | Ships all data to browser | L | Slower on 1GB+ JTLs | |

### Multi-file change presentation

**Bad:** "I'll edit `AiReportService.java` first, then show the next file."

**Good:** "3 files, apply in order: (1) `AiProviderConfig.java` — add field; (2) `AiProviderRegistry.java` — read
field; (3) `AiReportService.java` — use field. All diffs below."

### Rollback message

**Bad:** *(silently reverts)*

**Good:** "Reverting `HtmlPageBuilder.java` and `DataReportBuilder.java` to the last approved state (commit `f5a4347`).
Ready for next instruction."

## Environment

- JMeter deps `provided` (not bundled).
- Test stack: JUnit Jupiter 6.0.3, Mockito 5.23.0, Logback 1.5.32. Runtime: Gson 2.13.2, Commonmark 0.28.0.
- Shell: bash on Windows (Unix syntax — `/dev/null`, forward slashes). Child-process spawns via the Bash tool are
  fork-unstable; prefer Glob/Grep/Read/Write over `find`/`grep`/`cat`/`wc`.
- UI changes cannot be exercised without a live JMeter runtime — say so explicitly rather than claiming success.

## Build & Coverage

```bash
mvn clean verify                          # Build + tests + JaCoCo gate
mvn clean package -DskipTests             # Build only
mvn test -Dtest=JTLParserTest             # Single test class
mvn test -Dtest=JTLParserTest#testParse   # Single test method
mvn clean deploy -Prelease                # Release to Maven Central
```

- JaCoCo gate: ≥80% bundle line coverage (`verify` phase).
- Excluded from gate (live display, I/O, network, or `System.exit`): `listener.gui/AggregateReportPanel`, `ListenerGUI`,
  `ReportPanelBuilder`, `FilePanelCustomizer`, `AiReportLauncher`, `ListenerCollector`, `SimpleDocListener`;
  `listener.core/SlaRowRenderer`, `ColumnIndex`; `ai.report/AiReportCoordinator`, `HtmlReportRenderer`;
  `ai.provider/AiProviderRegistry`, `SharedHttpClient`, `AiProviderException`; `cli/CliReportPipeline`, `Main`;
  `parser/TimestampFormatResolver`.
- Report: `target/site/jacoco/index.html`.

## Definition of Done

A task is complete only when all apply:

- `mvn clean verify` passes (build + tests + JaCoCo ≥80% gate).
- No new compiler warnings or deprecation notices.
- No invariant from *Enforced invariants* violated; touched invariants re-verified against current code (grep, not
  memory).
- Dependency direction preserved (see Architecture — `parser` is the leaf; no cycles).
- Regression scan: callers of every modified public method reviewed.
- CLAUDE.md reviewed and updated if architecture, invariants, dependency direction, or class responsibilities changed.
- README.md reviewed and updated if user-facing behaviour changed (feature, flag, exit code, config key, UI element).
- Self-check pass: regressions, naming consistency, rule adherence.

## Architecture

File-based JMeter listener plugin for post-test JTL analysis. Produces filterable aggregate tables (GUI + CSV),
data-only HTML with classification verdict, and AI-generated HTML via any OpenAI-compatible provider. Does no live
metric collection — `ListenerCollector.sampleOccurred` is a no-op to prevent write-back to the source JTL.

**Dependency direction (acyclic):** `parser` is the leaf (no internal deps). Edges (→ = depends on):
`listener.core → parser`; `ai.prompt → parser`; `report → listener.core`; `ai.provider → ai.prompt`;
`ai.report → {ai.prompt, ai.provider, parser}`. Entry points `cli` and `listener.gui` may import any package.

### Class inventory

| Class                                          | Package       | Responsibility                                                                                                                                                                              |
|------------------------------------------------|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `JTLParser`                                    | parser        | Two-pass streamed CSV parser (pass 1: labels + timestamp range; pass 2: aggregation + buckets). Auto-bucket targets ~120 points. `TOTAL_LABEL = "TOTAL"`.                                   |
| `JtlParserCore`                                | parser        | Static parse utilities (row tokenisation, label/time discovery, sub-result detection, bucket math).                                                                                         |
| `DelimiterResolver`, `TimestampFormatResolver` | parser        | Infer CSV delimiter + timestamp format from JMeter properties with auto-detect fallback.                                                                                                    |
| `JtlParseException`                            | parser        | `IOException` subclass for parse failures.                                                                                                                                                  |
| `ColumnIndex`                                  | listener.core | 13 columns in fixed order + named indices (`NAME=0`, `AVG=4`, `PERCENTILE=7`, `ERROR_RATE=9`, `TPS=10`).                                                                                    |
| `CellValueParser`                              | listener.core | Parses formatted cells (TPS, %, ms, KB) back to numeric.                                                                                                                                    |
| `TablePopulator`                               | listener.core | Single source of truth for row formatting (GUI + CLI).                                                                                                                                      |
| `TransactionFilter`                            | listener.core | Include/exclude label filter; regex and plain modes.                                                                                                                                        |
| `SlaConfig`                                    | listener.core | Immutable SLA snapshot + `RtMetric` enum (`AVG`, `PNN`).                                                                                                                                    |
| `SlaEvaluator`                                 | listener.core | Evaluates 3 thresholds in one pass; returns `SlaResult(tpsFails, errorFails, rtFails, totalRows)`; renders verdict HTML.                                                                    |
| `SlaRowRenderer`                               | listener.core | Swing cell renderer — red/bold on breach.                                                                                                                                                   |
| `ScenarioMetadata`                             | listener.core | Test name, description, user count, start/end time.                                                                                                                                         |
| `CsvExporter`                                  | listener.core | Save-dialog CSV export with SLA status column.                                                                                                                                              |
| `ListenerGUI`                                  | listener.gui  | `AbstractVisualizer` entry point.                                                                                                                                                           |
| `ListenerCollector`                            | listener.gui  | `ResultCollector` subclass — JMeter boilerplate (see invariants).                                                                                                                           |
| `AggregateReportPanel`                         | listener.gui  | 1109-line Swing panel (SRP debt — see invariants).                                                                                                                                          |
| `ReportPanelBuilder`, `FilePanelCustomizer`    | listener.gui  | Panel sub-builders.                                                                                                                                                                         |
| `SimpleDocListener`                            | listener.gui  | Functional interface for Swing `DocumentListener` lambdas.                                                                                                                                  |
| `AiReportLauncher`                             | listener.gui  | GUI entry point for AI reports; wraps `AiReportCoordinator`.                                                                                                                                |
| `PromptLoader`                                 | ai.prompt     | Loads `ai-reporter-prompt.txt` from JAR.                                                                                                                                                    |
| `PromptContent`                                | ai.prompt     | Record `(systemPrompt, userMessage)`.                                                                                                                                                       |
| `PromptRequest`                                | ai.prompt     | 12-field record: scenario metadata + configured percentile + error/RT/TPS SLA thresholds + RT metric label.                                                                                 |
| `PromptBuilder`                                | ai.prompt     | Pre-computes classification, overall verdict, per-metric SLA summaries, anomalies, slowest endpoints. Embedded `LatencyContext` record.                                                     |
| `AiProviderConfig`                             | ai.provider   | Immutable per-provider config (key, baseUrl, model, timeout, maxTokens, temperature).                                                                                                       |
| `AiProviderRegistry`                           | ai.provider   | Loads `ai-reporter.properties`; `KNOWN_PROVIDERS` = groq, gemini, mistral, deepseek, cerebras, openai, claude; unlimited custom entries; ping cache per JVM; order via `ai.reporter.order`. |
| `AiReportService`                              | ai.provider   | OpenAI-compatible chat/completions call; 3 attempts + 2 s backoff; truncation and missing-section banners; `SECTION_SKELETON` + `EXPECTED_SECTION_HEADINGS` define the contract.            |
| `SharedHttpClient`                             | ai.provider   | Process-wide HTTP/2 client singleton.                                                                                                                                                       |
| `AiProviderException`, `AiServiceException`    | ai.provider   | `IOException` subclasses — hard-config vs transient failures.                                                                                                                               |
| `AiReportCoordinator`                          | ai.report     | Shared orchestrator (GUI + CLI). Consumes pre-built `PromptContent`, calls `AiReportService`, hands result to `HtmlReportRenderer`.                                                         |
| `HtmlReportRenderer`                           | ai.report     | `renderToFile` (AI path) + `renderDataReport` (data-only path). Writes HTML file.                                                                                                           |
| `HtmlPageBuilder`                              | ai.report     | `buildPage` (AI) + `buildDataOnlyPage` (no AI). Markdown → HTML + Chart.js + Excel/PDF export.                                                                                              |
| `MarkdownSectionNormaliser`, `MarkdownUtils`   | ai.report     | Heading normalisation; commonmark helpers.                                                                                                                                                  |
| `DataReportBuilder`                            | report        | Data-only section content (Workload Classification, SLA Verdict, Slowest Endpoints). No AI, no Swing, no I/O.                                                                               |
| `Main`                                         | cli           | Entry point; maps exceptions to exit codes.                                                                                                                                                 |
| `CliArgs`                                      | cli           | Parses + validates `-i`, `-o`, `--provider`, `--config`, offsets, percentile, search flags, SLA thresholds, RT metric.                                                                      |
| `CliReportPipeline`                            | cli           | Orchestrates parse → table → SLA/classification → AI → HTML write.                                                                                                                          |

### Design decisions

- **Two-pass parser**: pass 1 discovers labels + time range and chooses auto-bucket size; pass 2 aggregates. Streams —
  handles 1–2 GB+ files without full load.
- **Java computes, AI writes prose**: classification (`THROUGHPUT-BOUND`, `LATENCY-BOUND`, `ERROR-BOUND`,
  `CAPACITY-WALL`), SLA verdict, per-metric summaries, anomalies, slowest endpoints — all computed in Java. AI never
  produces a verdict.
- **No-AI fallback**: GUI and CLI both render data-only HTML when a provider fails transiently (ping, timeout, HTTP,
  rate limit). CLI exit code reflects the verdict, not the AI failure. Only hard config errors (bad provider, corrupt
  JAR) exit 5.
- **Single source of truth**: `TablePopulator.buildRowAsStrings` for row strings; `ColumnIndex.ALL_COLUMNS` for column
  order — both GUI and CLI consume them.
- **Data-only path is additive**: `renderDataReport()` + `buildDataOnlyPage()` are separate entry points; AI-path
  methods (`renderToFile`, `buildPage`) remain untouched.
- **Shading**: fat JAR shades only Gson + Commonmark. JMeter-provided deps never bundled. `original-*.jar` deleted
  post-shade. Signature files stripped (`.SF`, `.DSA`, `.RSA`); `MANIFEST.MF` preserved (required by `getManifest()`).

### AI report pipeline

- 7 mandatory sections in order: Executive Summary, Bottleneck Analysis, Error Analysis, Advanced Web Diagnostics, Root
  Cause Hypotheses, Recommendations, Verdict.
- `AiReportService` injects `## Executive Summary` as assistant prefill so the model always begins at section 1 and
  writes remaining headings itself.
- **Cerebras quirk**: prefill **skipped** — `qwen-3-235b-a22b-instruct-2507` treats the prefilled heading as a signal to
  produce only one section. Re-test if Cerebras updates the model. All other providers receive prefill. **Mistral**
  additionally needs `"prefix": true` on the prefill message.
- Truncation: detects `finish_reason: "length"` and missing `EXPECTED_SECTION_HEADINGS`; appends banner naming sections
  completed vs not reached; directs user to raise `max.tokens`.

### CLI modes + exit codes

| Mode          | Trigger                          | Verdict source      |
|---------------|----------------------------------|---------------------|
| Analysis-only | `-i` only                        | Java classification |
| SLA-only      | `-i` + any `--*-sla`             | SLA thresholds      |
| AI-only       | `-i` + `--provider` + `--config` | AI + classification |
| AI + SLA      | AI flags + SLA flags             | AI + SLA            |

Exit codes: `0` PASS · `1` FAIL · `2` UNDECISIVE · `3` bad args · `4` parse error · `5` AI hard error · `6` write
error · `7` unexpected.

### Known minor debt

- `HtmlReportRenderer.parseErrorRate` / `parseMs` duplicate `CellValueParser` logic — not yet migrated to the shared
  utility.
- `AggregateReportPanel.providerCombo` uses `addItem(null)` as the "no providers configured" placeholder instead of a
  custom `ListCellRenderer`.

## Enforced invariants (do not violate)

1. **`.jmx` property keys in `ListenerCollector` are frozen** — renames break backward compatibility with saved test
   plans.
2. **`ColumnIndex.ALL_COLUMNS` order is fixed** — CSV export and HTML column mapping depend on positional indices.
   Column 0 (Transaction Name) must always remain visible.
3. **`JTLParser.TOTAL_LABEL = "TOTAL"` row must never be excluded** from the results map.
4. **Exit codes 0–7 are public contract** — documented in README and consumed by CI; renumbering breaks pipelines.
5. **`SECTION_SKELETON` and `EXPECTED_SECTION_HEADINGS` in `AiReportService` are the provider contract** — changing
   either breaks all providers.
6. **Cerebras must NOT receive assistant prefill.**
7. **`PromptContent` must be built on the EDT** — `SamplingStatCalculator` values have no cross-thread JMM visibility
   guarantee.
8. **`AggregateReportPanel` SRP extraction is blocked** on a headless GUI-test harness. Until then, changes land
   in-place.
9. **`## Enforced invariants` heading is load-bearing** — extracted verbatim by `.github/workflows/pr-review.yml`. Do
   not rename, split, or change its position relative to the next `##` heading.

## Self-Maintenance

- **Ownership split**: `CLAUDE.md` = rules and context for Claude. `README.md` = user-facing features, install, config.
  When both need updating, change each in its own lane — do not duplicate content across files.
- **Auto-compact**: suggest `/compact` before context becomes unwieldy.

### CLAUDE.md update rules

Trigger: session changes design, architecture, invariants, or class responsibilities.

- Review this file in the same session. Remove stale entries, dedupe, confirm every line still carries actionable
  information.

**Do not put in CLAUDE.md**:

- Implementation details that rot on refactor (method signatures, minor helper behaviors).
- Facts derivable from `git log` / `git blame` / current code.
- Ephemeral task state (in-progress work, TODOs).
- Restatement of README content (user-facing features, install steps).
- Duplicates of facts already stated elsewhere in this file.

**Final pass — every item must hold**:

- **100% accuracy** — every claim matches current code. *Check:* grep each class/method/flag name; confirm each version
  matches `pom.xml`; confirm each file path resolves.
- **100% completeness** — every material code truth is covered. *Check:* for each package under `src/main/java`, confirm
  it appears in the dependency graph and class inventory; for each public invariant in code (frozen key, fixed order,
  contract), confirm it appears in Invariants.
- **100% consistency** — no two rules contradict. *Check:* scan for rules on the same topic in different sections;
  confirm priority-ordered rules (e.g. `Correctness → Security → …`) resolve conflicts deterministically.
- **100% optimization** — no line can be tightened further.
- **0% redundancy** — no fact stated more than once.

### README update rules

Trigger: user-facing feature changes (feature tables, filters, GUI overview, configuration, CLI options, exit codes,
troubleshooting).

Every change to `README.md` must satisfy:

1. **User-benefit framing** — describe features by what they do *for the user*, not by internal mechanics. Architectural
   terms (`sampleOccurred` no-op, `renderToFile` / `buildPage`, `PromptContent`, class names, package names) stay in
   CLAUDE.md.
2. **Features table = summary only** — one short line per feature. Property keys, defaults, CLI flags, and button names
   live only in Configuration / CLI Mode / GUI sections.
3. **Cross-platform shell blocks** — any command involving paths or env vars must show Linux/macOS, Windows PowerShell,
   and Windows cmd.
4. **Canonical reference per concept** — state each fact once (e.g. Pre-Computed Analysis, Classification-Based Verdict
   table, API Key Setup). Cross-reference from other sections instead of repeating.
5. **Explicit conditionality** — for any conditional UI element (report tabs, SLA highlighting, truncation banner,
   fallback behaviour), say *when* it appears: "Always" / "Only when X configured" / "When present".
6. **Self-updating references over hardcoded strings** — prefer badges and `*.jar` wildcards to literal version numbers.
   `maven-antrun-plugin` auto-rewrites `jaar-jmeter-plugin-X.Y.Z.jar` in `README.md` on build.
7. **Badges: maven-metadata over maven-central** — for release/version badges, use the
   `maven-metadata/v?metadataUrl=…repo1.maven.org…/maven-metadata.xml` variant rather than `maven-central/v/…`. The
   latter hits `search.maven.org`'s stale Solr index; the former reads the authoritative `maven-metadata.xml` and
   updates within minutes of a deploy.
8. **Link CLAUDE.md, do not duplicate** — architecture, dependency graph, design decisions, and invariants live only in
   CLAUDE.md. README links to it from Contributing.
9. **Required top-level sections** — Badges, Contents, Features, Requirements, Installation, Quick Start, feature
   sections, Known Limitations, Troubleshooting, Uninstall, Contributing, License.
10. **Exact versions, no `+`** — Java 17, JMeter 5.6.3 (not 17+, 5.6.3+) — matches the JMeter-5.6.3-exclusive invariant.
11. **Post-edit review** — verify all 5 dimensions: **100% accuracy**, **100% completeness**, **100% consistency**, *
    *100% optimization**, **0% redundancy**. See Self-Maintenance for procedural checks.
