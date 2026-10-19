#!/usr/bin/env bash
# MARKETING_VERSION in project.yml must equal the newest CHANGELOG.md entry ("## X.Y.Z — date").
#   bash scripts/check-version.sh [project.yml] [CHANGELOG.md]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${1:-$ROOT/project.yml}"
CHANGELOG="${2:-$ROOT/CHANGELOG.md}"

project_version="$(sed -nE 's/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"?([0-9]+\.[0-9]+\.[0-9]+)"?[[:space:]]*$/\1/p' "$PROJECT" | head -1)"
changelog_version="$(sed -nE 's/^##[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+)([[:space:]].*)?$/\1/p' "$CHANGELOG" | head -1)"

[[ -n "$project_version" ]] || { echo "✗ no MARKETING_VERSION found in $PROJECT" >&2; exit 1; }
[[ -n "$changelog_version" ]] || { echo "✗ no '## X.Y.Z' entry found in $CHANGELOG" >&2; exit 1; }
if [[ "$project_version" != "$changelog_version" ]]; then
  echo "✗ version mismatch: project.yml has $project_version, CHANGELOG.md top entry is $changelog_version" >&2
  exit 1
fi
echo "Version OK ($project_version)"
