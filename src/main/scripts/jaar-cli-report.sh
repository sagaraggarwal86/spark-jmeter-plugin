#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────
#  JAAR — JTL AI Analysis & Reporting  (macOS / Linux)
#
#  Place this script in $JMETER_HOME/bin/ alongside jmeter.sh.
#  The plugin JAR must be in $JMETER_HOME/lib/ext/.
#
#  Usage:
#    ./jaar-cli-report.sh -i results.jtl --provider groq --config ai-reporter.properties
# ──────────────────────────────────────────────────────────────────

set -euo pipefail

# Resolve JMETER_HOME from this script's location (bin/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JMETER_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"

# Verify JMeter installation
if [ ! -d "$JMETER_HOME/lib" ]; then
    echo "ERROR: JMeter lib directory not found at $JMETER_HOME/lib" >&2
    echo "       This script must be placed in JMETER_HOME/bin/" >&2
    exit 1
fi

# Build classpath: plugin JAR + JMeter libs
CP="$JMETER_HOME/lib/ext/*:$JMETER_HOME/lib/*"

# Launch CLI
exec java -cp "$CP" com.personal.jmeter.cli.Main "$@"
