#!/usr/bin/env bash
# Builds libflick_ffi.so for every Android ABI and drops it where Gradle packages it.
# Needs the Android NDK (ANDROID_NDK_HOME) and: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
set -euo pipefail
cd "$(dirname "$0")/.."
command -v cargo-ndk >/dev/null || cargo install cargo-ndk --locked
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p flick-ffi --release
