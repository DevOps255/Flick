# Flick for Windows and Linux

The desktop app of Flick. A phone and a PC pair the way two phones do: the phone makes the gesture, the PC hears it over Bluetooth,
and the files travel over the shared Wi-Fi. It uses the same pairing engine (`flick-proto`) and the same transfer core (`flick-core`) as the
phone, and a [Slint](https://slint.dev) interface that follows the phone's screens.

## Use it

1. Open Flick on the PC and on the phone. The PC shows the phone in Nearby.
2. To send to the phone, choose files on the PC, then tilt the phone or hold it against the PC. The send starts by itself.
3. To send to the PC, choose files on the phone, then tilt the phone or hold it against the PC.
4. Received files are in your Downloads folder, inside `Flick`.

Both devices must be on the same Wi-Fi network, with Bluetooth on.

## Build

You need [Rust](https://rustup.rs). The toolchain version is read from `rust-toolchain.toml` at the root of the repository.

Linux (Debian and Ubuntu), once:

```bash
sudo apt install libdbus-1-dev pkg-config libfontconfig1-dev libxkbcommon-dev
```

Windows needs nothing more than Rust and the Visual Studio C++ build tools.

From the root of the repository, on both systems:

```bash
cargo run --release --manifest-path desktop/Cargo.toml
cargo test --manifest-path desktop/Cargo.toml
```

## How it is built

| File | Role |
| --- | --- |
| `src/main.rs` | Starts the node, the Bluetooth tasks and the window, and connects the screens to them |
| `src/bluetooth.rs` | Scans for the Bluetooth frames of phones with `btleplug` and reports their signal strength |
| `src/advertise.rs` | Advertises this PC to phones: BlueZ through `bluer` on Linux, the Windows Bluetooth API on Windows |
| `src/discovery.rs` | Feeds the pairing engine, trusts the closest phone, asks phones for their name, reports pairings |
| `src/airdrop.rs` | The timeline of the pairing effect, the same as on Android |
| `src/effect.rs` | The pairing effect: the maths of the phone's shader, run on a snapshot of the window and shown over it |
| `ui/app.slint` | The interface: home, nearby, profile, the transfer card and the effect |

This crate has a workspace of its own, like `tools/ble-probe`, because it needs system libraries that the main workspace does not.
