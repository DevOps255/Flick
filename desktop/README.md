# Flick for Windows and Linux

The desktop app of Flick. A phone and a PC pair the way two phones do: the phone makes the gesture, the PC hears it over Bluetooth,
and the files travel over the shared Wi-Fi. It uses the same pairing engine (`flick-proto`) and the same transfer core (`flick-core`) as the
phone, and a [Slint](https://slint.dev) interface that follows the phone's screens.

## Use it

1. Open Flick on the PC and on the phone. The PC shows the phone in Nearby.
2. To send to the phone, choose files on the PC, then tilt the phone or hold it against the PC. The send starts by itself.
3. To send to the PC, choose files on the phone, then tilt the phone or hold it against the PC.
4. Between two PCs, hold them together. They pair when their Bluetooth signal says they touch, then one PC creates a private network and the other joins it.
5. Received files are in your Downloads folder, inside `Flick`.

No router, no internet and no shared network are needed. When the PC and the phone are not on the same network, the phone creates a private network and the PC joins it, like any Wi-Fi network, then goes back to its own network when the phone leaves. Bluetooth must be on, and the PC needs a Wi-Fi card.

## The spotlight

When files are chosen, the first one appears large in the middle of the window, with everything else blurred, like the pause menu of a
game. A picture shows its content when it is an image, and the big letters of its type otherwise. The **Grab** button plays the
move of a closed hand: the card shrinks and slides away toward the bottom while the blur clears. **Release here**, on the Nearby screen,
plays the opposite: the card comes back from the bottom and settles in the middle.

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
| `src/discovery.rs` | Feeds the pairing engine, trusts the closest phone, asks phones for their name, joins the phone's network when needed, reports pairings |
| `src/wifi.rs` | Joins the private network of a phone, or creates one for another PC, and gives the PC its own network back: NetworkManager on Linux, `netsh` and a Wi-Fi Direct group owner on Windows |
| `src/wifi_text.rs` | The text those tools want and give, tested apart |
| `src/identity.rs` | The certificate of this PC, with a fingerprint that starts with zeros so that the phone always creates the network |
| `src/airdrop.rs` | The timeline of the pairing effect, the same as on Android |
| `src/spotlight.rs` | The timing of the spotlight: the card appearing, being grabbed, being released |
| `src/stage.rs` | Puts the spotlight on the window: snapshot, blur, card position, frame by frame |
| `src/effect.rs` | The pairing effect: the maths of the phone's shader, run on a snapshot of the window and shown over it |
| `ui/app.slint` | The interface: home, nearby, profile, the transfer card and the effect |

This crate has a workspace of its own, like `tools/ble-probe`, because it needs system libraries that the main workspace does not.
