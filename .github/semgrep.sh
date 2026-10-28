#!/usr/bin/env bash
# Static analysis with Semgrep's default rules; prints one line per finding (rule, file:line).
mkdir -p .tmp
docker run --rm -v "$PWD:/src" semgrep/semgrep:1.182.0 semgrep scan --config p/default --error --metrics off \
  --exclude frontend/pnpm-lock.yaml --json --output /src/.tmp/semgrep.json --quiet
status=$?
jq -r '.results[] | "\(.extra.severity) \(.check_id) \(.path):\(.start.line)"' .tmp/semgrep.json
jq -r '.errors[]? | "ERROR \(.type // "") \(.message // "" | tostring | .[0:200])"' .tmp/semgrep.json
exit "$status"
