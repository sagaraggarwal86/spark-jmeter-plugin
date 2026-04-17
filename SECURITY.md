# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 6.0.x   | Yes       |
| < 6.0   | No        |

## Security Model

JAAR is a **local-first** JMeter listener plugin. It does not open inbound sockets, run a server, perform reflection, or
emit telemetry. Outbound calls happen only when generating an AI report — aggregated test statistics sent to the AI
provider endpoint configured in the user's `ai-reporter.properties`.

### Threat Surface

| Area               | Design                                                                                                                                         |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **Prompt payload** | Aggregated per-transaction statistics, transaction labels, and error codes. No raw JTL samples; no request/response bodies.                    |
| **AI API calls**   | HTTPS for all built-in providers. Custom providers use whatever scheme is configured — plugin does not enforce TLS.                            |
| **Ping cache**     | In-memory only, JVM-lifetime; not persisted to disk.                                                                                           |
| **Markdown**       | Commonmark with `escapeHtml(false)` — AI-returned HTML passes through unescaped. Provider trust required.                                      |
| **HTML report**    | Static file on local disk. References Chart.js (cdnjs) and xlsx-js-style (jsdelivr) — fetched by the user's browser.                           |
| **API keys**       | Read from user-managed `ai-reporter.properties`. Never logged, never embedded in reports, never transmitted except to the configured provider. |

### What JAAR Does NOT Protect Against

- **AI provider trust**: Data from the Prompt payload above is transmitted to the third-party endpoint you configure.
  Review the provider's data-handling terms before sending sensitive workloads. Transaction labels often contain URLs or
  identifiers — scrub anything sensitive in the test plan before running.
- **Malicious or compromised provider**: HTML pass-through lets a compromised provider inject `<script>` into a section.
  Treat reports from untrusted providers as untrusted HTML.
- **API key leakage**: Keys sit in plaintext at `$JMETER_HOME/bin/ai-reporter.properties`. Protect with OS permissions.
  Never commit to version control. Rotate if shared.
- **CDN compromise**: For air-gapped environments, host Chart.js and xlsx-js-style locally and rewrite the URLs
  post-generation.

## Reporting a Vulnerability

1. **Do not** open a public GitHub issue.
2. Use
   GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
   on this repository. Include a description, reproduction steps, and potential impact.

## Dependencies

Bundled (shaded) runtime deps:

| Dependency | Purpose                   |
|------------|---------------------------|
| Gson       | JSON request/response     |
| Commonmark | Markdown → HTML rendering |

All other runtime deps are `provided` scope (resolved from JMeter's classpath). Updates tracked via Dependabot.
