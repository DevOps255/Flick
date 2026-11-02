#!/usr/bin/env bash
# The project writes no em dash and no en dash, in code or in documents.
# Vendored projects and generated files are left alone.
set -euo pipefail
cd "$(dirname "$0")/.."

found=$(grep -rIn --exclude-dir=third_party --exclude-dir=target --exclude-dir=build --exclude-dir=.git \
  --exclude-dir=.gradle --exclude-dir=jniLibs --exclude=flick_ffi.kt --exclude=Cargo.lock --exclude=LICENSE --exclude=check-style.sh \
  -e '—' -e '–' . || true)
if [ -n "$found" ]; then
  echo "Dashes found, rewrite these lines with a comma, a colon or a full stop:" >&2
  echo "$found" >&2
  exit 1
fi
echo "No dashes."
