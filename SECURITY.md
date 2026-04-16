# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 6.0.x   | Yes       |
| < 6.0   | No        |

## Security Model

JAAR is a **local-first** JMeter listener plugin. It does not open inbound sockets, run a server, perform reflection, or emit telemetry. Outbound calls happen only when generating an AI report — aggregated test statistics sent to the AI provider endpoint configured in the user's `ai-reporter.properties`.

### Threat Surface

| Area              | Design                                                                                                                                         |
|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| **JTL file I/O**  | Read-only streaming parse. Input path chosen via file chooser or `-i` flag.                                                                    |
| **Report writes** | HTML output path chosen by the user. No writes outside the specified path.                                                                     |
| **Prompt payload**| Aggregated per-transaction statistics, transaction labels, and error codes. No raw JTL samples; no request/response bodies.                    |
| **AI API calls**  | HTTPS for all built-in providers. Custom providers use whatever scheme is configured — plugin does not enforce TLS.                            |
| **Ping cache**    | In-memory only, JVM-lifetime. Composite key `providerKey:apiKey` is SHA-256 hashed so rotating the key invalidates the entry.                  |
| **Markdown**      | Commonmark with `escapeHtml(false)` — AI-returned HTML passes through unescaped. Provider trust required.                                      |
| **Serialisation** | Gson for JSON. No polymorphic deserialisation.                                                                                                 |
| **HTML report**   | Static file on local disk. References Chart.js (cdnjs) and xlsx-js-style (jsdelivr) — fetched by the user's browser, not by the plugin.        |
| **API keys**      | Read from user-managed `ai-reporter.properties`. Never logged, never embedded in reports, never transmitted except to the configured provider. |

### What JAAR Does NOT Protect Against

- **AI provider trust**: Data from the Prompt payload above is transmitted to the third-party endpoint you configure. Review the provider's data-handling terms before sending sensitive workloads.
- **Transaction label leakage**: Labels often contain URLs or identifiers — scrub anything sensitive in the test plan before running.
- **Malicious or compromised provider**: HTML pass-through lets a compromised provider inject `<script>` into a section. Treat reports from untrusted providers as untrusted HTML.
- **API key leakage**: Keys sit in plaintext at `$JMETER_HOME/bin/ai-reporter.properties`. Protect with OS permissions. Never commit to version control. Rotate if shared.
- **Custom providers**: Any OpenAI-compatible `base.url` is accepted. The plugin does not pin certificates or restrict endpoints — trust follows your configuration.
- **CDN availability or compromise**: Opening the generated HTML needs network access to the CDNs above. For air-gapped environments, host the JS locally and rewrite the URLs post-generation.
- **Adversarial JTL input**: The parser is defensive against malformed rows but makes no guarantee against files crafted to exhaust memory or CPU.

## Reporting a Vulnerability

1. **Do not** open a public GitHub issue.
2. Use GitHub's [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability) on this repository, or email **sagarhoney.aggarwal@gmail.com** with a description, reproduction steps, and potential impact.
3. Acknowledgment within 72 hours. Fix prioritised by severity.

## Dependencies

Bundled into the plugin JAR (fat JAR via `maven-shade-plugin`):

| Dependency | Purpose                   |
|------------|---------------------------|
| Gson       | JSON request/response     |
| Commonmark | Markdown → HTML rendering |

All other dependencies are `provided` scope (resolved from JMeter's classpath):

| Dependency              | Purpose            |
|-------------------------|--------------------|
| ApacheJMeter_core       | JMeter integration |
| ApacheJMeter_components | JMeter integration |
| jorphan                 | JMeter utilities   |
| slf4j-api               | Logging            |

Updates tracked via Dependabot.
