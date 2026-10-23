# Notice

Flick is released under the GNU Affero General Public License v3.0 (`LICENSE`).
That license applies because Flick includes code from DashBeam.

## Included code

| Path | Origin | License |
| --- | --- | --- |
| `third_party/localsend` | LocalSend, Rust core, CLI and signaling server | Apache 2.0 |
| `third_party/dashbeam/engine` | DashBeam, Rust engine (protocol, native, wasm-io) | AGPL 3.0 |

Both projects keep their original license files and copyright headers.
The Flutter app of LocalSend and the Tauri and web front ends of DashBeam are
not included. Flick has its own Android interface.

## Referenced code, not included

BluetoothProximity (MIT Lincoln Laboratory, MIT) for the ultrasonic ranging
method. NameDrop (Cyril Zakka, MIT) for the ripple shader. ggwave (Georgi
Gerganov, MIT) for data over sound.
