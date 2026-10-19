#!/usr/bin/env bash
# Usage: bash tools/watch_build.sh [short sha]   (from the Swing5 folder)
# Waits for the latest GitHub Actions run of LiorAvrahami/6SteP to finish and prints the error lines of a failed job.
# The token: $GH_TOKEN, or the file named by $GH_TOKEN_FILE (keep that file outside the project; never commit it).
T="${GH_TOKEN:-$(cat "${GH_TOKEN_FILE:?set GH_TOKEN or GH_TOKEN_FILE}")}"
API="https://api.github.com/repos/LiorAvrahami/6SteP"
H="Authorization: Bearer $T"
WANT_SHA="${1:-}"
for i in $(seq 1 60); do
  J=$(curl -s -H "$H" "$API/actions/runs?per_page=3")
  LINE=$(printf '%s' "$J" | python -c "
import sys, json
runs = json.load(sys.stdin).get('workflow_runs', [])
want = '$WANT_SHA'
for r in runs:
    if not want or r['head_sha'].startswith(want):
        print(r['id'], r['status'], r.get('conclusion') or '-', r['run_number'], r['head_sha'][:7]); break
else:
    print('none queued -', '-', '-', '-')
")
  set -- $LINE
  RUN_ID=$1; STATUS=$2; CONC=$3; NUM=$4; SHA=$5
  echo "$(date +%H:%M:%S) run $RUN_ID #$NUM $SHA: $STATUS $CONC"
  if [ "$STATUS" = "completed" ]; then
    if [ "$CONC" != "success" ]; then
      JOBS=$(curl -s -H "$H" "$API/actions/runs/$RUN_ID/jobs")
      JOB_ID=$(printf '%s' "$JOBS" | python -c "
import sys, json
for j in json.load(sys.stdin).get('jobs', []):
    if j.get('conclusion') not in ('success', None): print(j['id']); break
")
      echo "--- failed job $JOB_ID: steps"
      printf '%s' "$JOBS" | python -c "
import sys, json
for j in json.load(sys.stdin).get('jobs', []):
    for s in j.get('steps', []): print('  ', s['name'], '->', s.get('conclusion'))
"
      curl -s -L -H "$H" "$API/actions/jobs/$JOB_ID/logs" > "$S/build_log.txt"
      echo "--- error lines (log saved to $S/build_log.txt, $(wc -l < "$S/build_log.txt") lines)"
      grep -n -i -E "^.{0,40}(e: |error:|error |FAILURE|What went wrong|Could not|unresolved|Unresolved|cannot|failed)" "$S/build_log.txt" | grep -v -i "warning" | head -60
    else
      REL=$(curl -s -H "$H" "$API/releases/latest" | python -c "
import sys, json
r = json.load(sys.stdin); print(r.get('tag_name'), r.get('html_url'), [a['name'] + ' ' + str(a['size']//1024) + ' KB' for a in r.get('assets', [])])")
      echo "--- release: $REL"
    fi
    exit 0
  fi
  sleep 30
done
echo "timed out waiting for the run"
