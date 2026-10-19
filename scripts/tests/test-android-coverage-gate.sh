#!/usr/bin/env bash
# The Android coverage gate must fail on bad input and pass on good input.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
C="$ROOT/scripts/tests/android-coverage"
fail=0
run() { # description, expected(pass|fail), command...
  local desc="$1" expect="$2"; shift 2
  local status=0
  "$@" >/dev/null 2>&1 || status=$?
  if [[ "$expect" == pass && $status -eq 0 ]] || [[ "$expect" == fail && $status -ne 0 ]]; then echo "ok   $desc"; else echo "FAIL $desc (exit $status)"; fail=1; fi
}
gate() { python3 -I "$ROOT/scripts/android-coverage-gate.py" "$@"; }
run "coverage: both above 80% passes"      pass gate --core "$C/ok.xml"  --app "$C/ok.xml"
run "coverage: core 79% fails"             fail gate --core "$C/low.xml" --app "$C/ok.xml"
run "coverage: app 79% fails"              fail gate --core "$C/ok.xml"  --app "$C/low.xml"
run "coverage: empty report fails"         fail gate --core "$C/empty.xml" --app "$C/ok.xml"
run "coverage: report without LINE fails"  fail gate --core "$C/ok.xml"  --app "$C/no_line_counter.xml"
run "coverage: unreadable report fails"    fail gate --core "$C/not_xml.xml" --app "$C/ok.xml"
run "coverage: missing file fails"         fail gate --core "$C/nope.xml" --app "$C/ok.xml"
run "coverage: threshold is configurable"  pass gate --core "$C/low.xml" --app "$C/low.xml" --threshold 75
exit $fail
