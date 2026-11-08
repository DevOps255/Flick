#!/usr/bin/env bash
# Fails when the committed Kotlin bindings do not match the Rust code they were generated from.
# A mismatch makes the app close at its first call into Rust, with "API checksum mismatch".
# Regenerate with scripts/generate-kotlin.sh.
set -euo pipefail
cd "$(dirname "$0")/.."

committed=android/app/src/main/java/uniffi/flick_ffi/flick_ffi.kt
fresh=$(mktemp -d)
trap 'rm -rf "$fresh"' EXIT

cargo build -p flick-ffi --features cli
case "$(uname -s)" in
  Darwin) library="${CARGO_TARGET_DIR:-target}/debug/libflick_ffi.dylib" ;;
  MINGW*|MSYS*|CYGWIN*) library="${CARGO_TARGET_DIR:-target}/debug/flick_ffi.dll" ;;
  *) library="${CARGO_TARGET_DIR:-target}/debug/libflick_ffi.so" ;;
esac
cargo run -q -p flick-ffi --features cli --bin uniffi-bindgen -- generate \
  --library "$library" --language kotlin --out-dir "$fresh" 2>&1 | grep -v ktlint || true

if diff -q "$fresh/uniffi/flick_ffi/flick_ffi.kt" "$committed" >/dev/null; then
  echo "Kotlin bindings match the Rust code."
else
  echo "The Kotlin bindings are out of date. Run scripts/generate-kotlin.sh and commit the result." >&2
  diff "$fresh/uniffi/flick_ffi/flick_ffi.kt" "$committed" | head -40 >&2 || true
  exit 1
fi
