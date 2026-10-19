#!/usr/bin/env bash
# `build-reference-db.py verify` must pass the committed bundle and fail a tampered database, a tampered manifest, a
# flipped golden expectation and an app that does not bundle the data (requirements B14, C6).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail=0
verify() { python3 -I "$ROOT/scripts/build-reference-db.py" verify "$@"; }
run() { # description, expected(pass|fail), verify arguments...
  local desc="$1" expect="$2"; shift 2
  local status=0
  verify "$@" >/dev/null 2>&1 || status=$?
  if [[ "$expect" == pass && $status -eq 0 ]] || [[ "$expect" == fail && $status -ne 0 ]]; then echo "ok   $desc"; else echo "FAIL $desc (exit $status)"; fail=1; fi
}

run "the committed bundle passes" pass

mkdir "$WORK/tampered-db"
cp "$ROOT"/reference-data/bundle/* "$WORK/tampered-db/"
python3 -I -c "
import sqlite3, sys
c = sqlite3.connect(sys.argv[1] + '/reference.sqlite')
c.execute(\"UPDATE presentation SET name = 'TAMPERED' WHERE cip13 = (SELECT min(cip13) FROM presentation)\")
c.commit()
" "$WORK/tampered-db"
run "a tampered database fails" fail --bundle "$WORK/tampered-db"

mkdir "$WORK/tampered-manifest"
cp "$ROOT"/reference-data/bundle/* "$WORK/tampered-manifest/"
sed -i.bak 's/"contentSha256": "[0-9a-f]*"/"contentSha256": "bad"/' "$WORK/tampered-manifest/reference-manifest.json"
run "a tampered manifest fails" fail --bundle "$WORK/tampered-manifest"

python3 -I -c "
import json, sys
doc = json.load(open(sys.argv[1]))
doc['cases'][0]['expect'] = 'eyes' if doc['cases'][0]['expect'] != 'eyes' else 'other'
json.dump(doc, open(sys.argv[2], 'w'))
" "$ROOT/reference-data/golden-list.json" "$WORK/golden-flipped.json"
run "a flipped golden expectation fails" fail --golden "$WORK/golden-flipped.json"

run "an unknown app name fails" fail --apps windows
exit $fail
