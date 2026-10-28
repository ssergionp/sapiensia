#!/usr/bin/env bash
# Runs a CI step and, when it fails, copies the end of its output into an annotation:
# the error then shows up on the PR page and through the GitHub API, without opening the full log.
# Usage: .github/ci-report.sh "Step title" command arg1 arg2 ...
title="$1"
shift
log=$(mktemp)
set -o pipefail
"$@" 2>&1 | tee "$log"
status=$?
if [ "$status" -ne 0 ]; then
  message=$(tail -n 80 "$log" | sed -e 's/%/%25/g' -e 's/\r//g' -e 's/\x1b\[[0-9;]*m//g' | sed ':a;N;$!ba;s/\n/%0A/g')
  echo "::error title=${title}::${message}"
fi
exit "$status"
