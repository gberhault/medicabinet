#!/usr/bin/env bash
# Fails when a String Catalog key is untranslated in any shipped language (fr, en).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/ios"
python3 -I "$ROOT/scripts/ios-check-strings.py" "$@"
