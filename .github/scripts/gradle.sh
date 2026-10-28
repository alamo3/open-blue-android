#!/usr/bin/env bash
# Runs gradle; on failure, surfaces compiler/test errors as workflow annotations
# so they're readable without downloading the full log.
set -o pipefail
if ! gradle --no-daemon "$@" 2>&1 | tee build.log; then
  grep -E "^e: |What went wrong|^> |Could not|error:|FAILED|Exception" build.log | grep -v "^> Task" | head -80 > errors.txt || true
  split -l 12 errors.txt chunk_
  for f in chunk_*; do
    msg=$(sed -e 's/%/%25/g' "$f" | sed -e ':a;N;$!ba;s/\n/%0A/g')
    echo "::error title=Build error::$msg"
  done
  exit 1
fi
