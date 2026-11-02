# Architecture

## Flow of one transfer

1. Both phones open Flick. Each advertises a 21 byte frame over Bluetooth Low Energy and scans for the others (`flick-proto/src/ble.rs`). The frame holds the announcement: an 8 byte prefix of the TLS certificate fingerprint, the IPv4 address, the port, and whether a pairing gesture just happened. Discovery pauses when the app leaves the screen.
2. Signal strength gives a rough distance. A Kalman filter smooths it (`flick-proto/src/pairing.rs`).
3. A phone closer than about a metre is linked ahead of time: nothing on a shared network, otherwise a Wi-Fi Direct group. The phone with the larger fingerprint prefix creates the group, the other joins, and the group name and password are a hash of both prefixes (`flick-core/src/link.rs`), so nothing is negotiated over the air. The link is therefore up before the user does anything.
4. The user picks a file, then both users tilt their phones. The gyroscope detects a deliberate turn and ignores walking and shaking (`flick-gesture/src/tilt.rs`). Each phone shows the gesture in its next frame.
5. A phone pairs with the closest phone that made the gesture within 2.5 seconds. Two phones about as close make the answer ambiguous and nothing is paired.
6. The paired phone must answer over TLS with a certificate that starts with the announced 8 bytes. Only then is it ready, and only then the AirDrop effect plays, on both phones.
7. A swipe on the file card, or a Flick of the phone (`flick-gesture`), sends. Files stream over HTTPS from the open file descriptor, with a SHA-256 the receiver checks.

Bluetooth carries no secret and no file data.

## Included and referenced work

Both projects are copied into `third_party/` with their licenses. See `NOTICE.md`.

| Project | License | Path | Used for |
| --- | --- | --- | --- |
| LocalSend | Apache 2.0 | `third_party/localsend/packages/core` | HTTPS server and client, certificate identity, resume, file model, multicast discovery on a shared network |
| DashBeam | AGPL 3.0 | `third_party/dashbeam/engine` | Copied, not called yet. Offers iroh QUIC transport, BLAKE3 verification, pairing and trusted devices |
| ggwave | MIT | `third_party/ggwave` | Data over sound. No longer used by the app, kept in `flick-acoustic` |
| BluetoothProximity | MIT | reference | Ultrasonic ranging method, to be ported to Rust |
| NameDrop | MIT | `ui/effects/NameDropShader.kt` | Water wave shader, ported to AGSL. Played when the phones are in sync, at the gesture, and on arrival |

Flick is AGPL 3.0 because it includes DashBeam code.

## How the two engines fit

LocalSend is the transport when both devices share an IP network, which is the
case after Wi-Fi Direct. DashBeam's iroh transport covers the case where no
direct link exists. `flick-core` ties the sound to LocalSend's certificate
identity. Choosing between the two transports per transfer is the next task.

## Android layers

`ui` talks to `core` interfaces only. `data` implements them. `di` chooses which
implementation is used.

| Concern | Choice | Replaces in Flutter |
| --- | --- | --- |
| State | `ViewModel` with `StateFlow` | Riverpod |
| Navigation | Navigation Compose, typed routes | go_router |
| Serialization | kotlinx.serialization | json_serializable |
| Injection | Hilt | get_it, injectable |
| Database | Room (SQLite) | sqflite, drift |
| Settings | DataStore | shared_preferences |

## Motion rules

Defined once in `ui/theme/Motion.kt`: strong ease-out for entrances, critically
damped springs for anything the user can move, press scale of 0.97, nothing
above 300 ms, loops stop when the system disables animations.
