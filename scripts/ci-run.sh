#!/usr/bin/env bash
# Runs a command. When it fails, the most useful lines of its output go into a GitHub annotation and
# the step summary. Both are readable on the run's summary page without signing in, which the full
# log is not.
#
# Usage: bash scripts/ci-run.sh <title> <command> [arguments...]
set -uo pipefail

title=$1
shift
log=$(mktemp)
"$@" 2>&1 | tee "$log"
status=${PIPESTATUS[0]}

if [ "$status" -ne 0 ] && [ -n "${GITHUB_ACTIONS:-}" ]; then
  # Error lines first, then the end of the output where cargo and Gradle print their verdict.
  picked=$( { grep -E "^error|error\[E|panicked at|^failures:|FAILED|^---- |assertion|Permission denied|No such file|could not compile|What went wrong|Execution failed|e: file" "$log" | head -20
              echo "--- end of output ---"
              tail -n 15 "$log"; } | cut -c1-300 )
  {
    echo "### ${title} failed (exit code ${status})"
    echo '```'
    echo "$picked"
    echo '```'
  } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  encoded=$(printf '%s' "$picked" | sed 's/%/%25/g; s/\r/%0D/g' | awk 'BEGIN{ORS="%0A"}{print}')
  echo "::error title=${title} failed::${encoded}"
fi
exit "$status"
