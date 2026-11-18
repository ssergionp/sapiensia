#!/usr/bin/env bash
# Static analysis with Semgrep's default rules; prints one line per finding (rule, file:line).
# .claude/skills/impeccable is a third-party skill copied unchanged from github.com/pbakaus/impeccable at a pinned
# commit (its live-mode browser scripts trip the default rules); gitleaks and Trivy still scan it.
mkdir -p .tmp
docker run --rm -v "$PWD:/src" semgrep/semgrep:1.182.0 semgrep scan --config p/default --error --metrics off \
  --exclude frontend/pnpm-lock.yaml --exclude .claude/skills/impeccable --json --output /src/.tmp/semgrep.json --quiet
status=$?
jq -r '.results[] | "\(.extra.severity) \(.check_id) \(.path):\(.start.line)"' .tmp/semgrep.json
jq -r '.errors[]? | "ERROR \(.type // "") \(.message // "" | tostring | .[0:200])"' .tmp/semgrep.json
exit "$status"
