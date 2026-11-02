# Notice

Flick is released under the GNU Affero General Public License v3.0 (`LICENSE`).
That license applies because Flick includes code from DashBeam.

## Included code

| Path | Origin | License |
| --- | --- | --- |
| `third_party/localsend` | LocalSend, Rust core, CLI and signaling server | Apache 2.0 |
| `third_party/dashbeam/engine` | DashBeam, Rust engine (protocol, native, wasm-io) | AGPL 3.0 |
| `third_party/ggwave` | ggwave, data over sound (C++) | MIT |
| `android/.../ui/effects/AirDropShader.kt` | AirDrop effect shader ported from GLSL to AGSL, from https://www.shadertoy.com/view/dlXczX | Not stated, see below |

Both projects keep their original license files and copyright headers.
The Flutter app of LocalSend and the Tauri and web front ends of DashBeam are
not included. Flick has its own Android interface.

## Changes to included code

`third_party/localsend/packages/core/src/http/client/server_cert_verifier.rs`:
the pinned fingerprint may now be a prefix of at least 8 bytes of the real one,
because the ultrasonic announcement carries only that much. A full length pin
still has to match exactly.

## License of the AirDrop shader

The shader at https://www.shadertoy.com/view/dlXczX has no license in its code. Shadertoy's default is
Creative Commons Attribution NonCommercial ShareAlike 3.0. That license does not allow commercial use
and is not compatible with the AGPL 3.0 that covers Flick. Before Flick is published or sold, either ask the
author for permission, or replace the shader with an original one. The author's name is on the Shadertoy page.

## Referenced code, not included

BluetoothProximity (MIT Lincoln Laboratory, MIT) for the ranging method, replaced for now by a
signal strength model with a Kalman filter in `flick-proto/src/pairing.rs`.
