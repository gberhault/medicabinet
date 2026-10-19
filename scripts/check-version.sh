#!/usr/bin/env bash
# A product's version must equal the newest entry ("## X.Y.Z — date") of its CHANGELOG.
#   bash scripts/check-version.sh <ios|android> [version-file] [changelog]
# ios:     MARKETING_VERSION in ios/project.yml          vs ios/CHANGELOG.md
# android: versionName in android/app/build.gradle.kts   vs android/CHANGELOG.md
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PRODUCT="${1:-}"

case "$PRODUCT" in
  ios)
    VERSION_FILE="${2:-$ROOT/ios/project.yml}"
    CHANGELOG="${3:-$ROOT/ios/CHANGELOG.md}"
    pattern='s/^[[:space:]]*MARKETING_VERSION:[[:space:]]*"?([0-9]+\.[0-9]+\.[0-9]+)"?[[:space:]]*$/\1/p'
    label="MARKETING_VERSION"
    ;;
  android)
    VERSION_FILE="${2:-$ROOT/android/app/build.gradle.kts}"
    CHANGELOG="${3:-$ROOT/android/CHANGELOG.md}"
    pattern='s/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([0-9]+\.[0-9]+\.[0-9]+)"[[:space:]]*$/\1/p'
    label="versionName"
    ;;
  *)
    echo "usage: check-version.sh <ios|android> [version-file] [changelog]" >&2
    exit 2
    ;;
esac

[[ -f "$VERSION_FILE" ]] || { echo "✗ $VERSION_FILE not found" >&2; exit 1; }
[[ -f "$CHANGELOG" ]] || { echo "✗ $CHANGELOG not found" >&2; exit 1; }

product_version="$(sed -nE "$pattern" "$VERSION_FILE" | head -1)"
changelog_version="$(sed -nE 's/^##[[:space:]]+([0-9]+\.[0-9]+\.[0-9]+)([[:space:]].*)?$/\1/p' "$CHANGELOG" | head -1)"

[[ -n "$product_version" ]] || { echo "✗ no $label found in $VERSION_FILE" >&2; exit 1; }
[[ -n "$changelog_version" ]] || { echo "✗ no '## X.Y.Z' entry found in $CHANGELOG" >&2; exit 1; }
if [[ "$product_version" != "$changelog_version" ]]; then
  echo "✗ $PRODUCT version mismatch: $label is $product_version, $CHANGELOG top entry is $changelog_version" >&2
  exit 1
fi
echo "Version OK ($PRODUCT $product_version)"
