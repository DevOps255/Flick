# Flick

Send a file to a phone next to you. Bluetooth finds it, a tilt of both phones pairs them, a
direct Wi-Fi link carries the data, and a quick flick of the phone starts the transfer.

The step by step installation guide for phones is in `GUIDE.md` (French).

## Layout

| Path | Language | Role |
| --- | --- | --- |
| `crates/flick-proto` | Rust | 12 byte announcement sent as sound, replay guard |
| `crates/flick-gesture` | Rust | Flick detection from accelerometer samples |
| `crates/flick-acoustic` | Rust and C++ | Bytes to ultrasound and back, built on ggwave |
| `crates/flick-ffi` | Rust | UniFFI bindings, generates the Kotlin API |
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

## Android and Rust

```bash
scripts/build-android-libs.sh   # builds libflick_ffi.so for each ABI (needs the NDK)
scripts/generate-kotlin.sh      # regenerates the Kotlin bindings after an API change
```

## Continuous integration

`.github/workflows/ci.yml` checks every push: format, clippy with warnings denied, all tests, the shader port, that the Kotlin bindings match the Rust code, a full Android build with the NDK, and a launch on an emulator.
The APK is attached to each run. The Android and emulator jobs have never run yet.
Run the same checks by hand with `scripts/check-bindings.sh` and `scripts/check-style.sh`.

## What works

Tested by `cargo test` on a computer, 64 tests:

- An announcement turns into ultrasound and is decoded again at any offset in the stream, with noise.
- Two nodes exchange files over real TLS. The receiver ends up with identical bytes and its progress never goes backwards.
- An unknown sender is declined, and a wrong certificate is rejected before any data leaves the sender.
- Every file is sent with its SHA-256. A damaged file is rejected, no partial file stays on disk, and the receiving screen ends instead of hanging.
- The Bluetooth frame round trips and fits in a legacy advertisement. Pairing picks the closest phone that gestured at the same time, and refuses when two are about equally close. Signal strength is smoothed by a Kalman filter.
- The tilt gesture is detected from gyroscope samples, and walking, shaking and slow drifting are ignored.
- A file whose path was deleted, so only its open descriptor reaches the bytes, is sent intact. That is the situation of a picked file on Android.
- One sender serves two receivers at once. By default only one phone is allowed to send to a receiver, and a list swap allows several.
- The Flick gesture is detected from accelerometer samples.
- The Wi-Fi Direct decision is right: no link on a shared network, one creator and one joiner otherwise, and both derive the same group name and password.

Written but not yet compiled or run on a phone: the Android app (`android/`).
The Kotlin syntax was checked with the Kotlin compiler, the types were not, because
the Android SDK is not available where this was written. The first Gradle sync may need small fixes.

## Modes

- Default: files go to the closest ready phone, and only the closest phone may send here.
- Several phones (a switch on the Nearby screen): files go to every ready phone, and any phone nearby may send here.

## Known limits

- Android 10 and later refuse to let an app turn Wi-Fi on. Flick notices that it is off and opens the system panel where one tap does it.

- Wi-Fi Direct is written but never ran on a phone. Joining a group by name and password (`WifiDirectLink.establish`) is the first thing to test on two devices, because Android phones differ here. On a shared Wi-Fi no group is needed.
- Android needs the nearby devices permission (13 and later) or location (older) for Wi-Fi Direct. On older versions, location services must also be on.
- The distance shown for a phone is estimated from how loud its sound is. It is not a measurement.
- A transfer survives leaving the screen: it runs in `TransferManager` and `TransferService` shows a notification. Nothing was tested on a phone, and Android 14 and later restrict starting such a service from the background, so it is started only while the app is open.

## Not built

- Resuming an interrupted transfer. LocalSend's protocol has no resume, so a failed transfer starts again from zero.
- The computer app (Slint). `flick-core`, `flick-acoustic` and `flick-gesture` are platform independent and ready for it, but no desktop audio or interface code exists.
- Real distance measurement from BluetoothProximity's method. The distance shown is estimated from loudness.
- DashBeam's iroh transport. The engine is copied in `third_party/dashbeam` but nothing calls it: on a proximity link LocalSend's transport already reaches the other phone, and iroh would only help over the internet.
- Sharing by link, so someone far away downloads a file without a cloud. The iroh transport in `third_party/dashbeam` is the planned base for it.
