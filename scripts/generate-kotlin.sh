#!/usr/bin/env bash
# Regenerates the Kotlin bindings after changing crates/flick-ffi.
set -euo pipefail
cd "$(dirname "$0")/.."
cargo build -p flick-ffi --features cli
cargo run -q -p flick-ffi --features cli --bin uniffi-bindgen -- generate \
  --library target/debug/libflick_ffi.so --language kotlin \
  --out-dir android/app/src/main/java
