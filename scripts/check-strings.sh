#!/usr/bin/env bash
# Fails when a String Catalog key is untranslated in any shipped language (fr, en).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I scripts/check-strings.py "$@"
