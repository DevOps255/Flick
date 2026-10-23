# Flick

Send a file to a phone or PC next to you. Ultrasound finds the device, a direct
Wi-Fi link carries the data, and a quick flick of the phone starts the transfer.

## Layout

| Path | Language | Role |
| --- | --- | --- |
| `crates/flick-proto` | Rust | 12 byte announcement sent as sound, replay guard |
| `crates/flick-gesture` | Rust | Flick detection from accelerometer samples |
| `crates/flick-core` | Rust | Ties the announcement to LocalSend's certificate identity |
| `third_party/localsend` | Rust | LocalSend core, CLI, server (Apache 2.0) |
| `third_party/dashbeam/engine` | Rust | DashBeam engine: iroh transport, pairing, BLAKE3 (AGPL 3.0) |
| `android/` | Kotlin, Compose | App UI, Room database, Hilt injection |

The Rust crates have no platform code, so Android and desktop share them.

## Build and test

```bash
cargo test                      # rustup installs the pinned toolchain (1.97.1)
cd third_party/localsend && cargo test -p localsend --features full
cd android && ./gradlew test    # unit tests, needs Android Studio or the SDK
cd android && ./gradlew installDebug
```

## Status

The Android app runs on simulated devices and transfers (`data/simulated`).
Real discovery and transfer replace those two classes in `di/AppModule.kt`.
See `ARCHITECTURE.md` for what is borrowed from which project.
