# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 2.7.x   | :white_check_mark: |
| < 2.7   | :x:                |

## Reporting a Vulnerability

If you discover a security vulnerability in the JAAR plugin,
please report it responsibly:

1. **Do not** open a public issue.
2. Email the maintainer at the address listed in `pom.xml`, or use GitHub's
   [private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
   feature on this repository.


## Scope

This plugin processes JTL files locally and makes outbound HTTPS calls only to
the AI provider endpoint configured by the user. It does not run a server,
accept inbound connections, or store credentials on disk.