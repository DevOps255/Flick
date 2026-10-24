# Architecture

## Flow of one transfer

1. Both phones open Flick. Ultrasonic discovery starts at launch and pauses in the background. The user picks files.
2. Each phone plays its `Announcement` (`flick-proto`, 18 bytes) as ultrasound and listens for the others (`flick-acoustic`, ggwave).
3. A phone that hears one rejects replays, learns the address and port, and pins the certificate prefix.
4. If the phones share a network nothing happens. Otherwise they form a Wi-Fi Direct group:
   the phone with the larger fingerprint prefix creates it, the other joins, and the group name
   and password come from a hash of both prefixes (`flick-core/src/link.rs`), so nothing is
   negotiated over the air. The sender then connects over TLS, and the handshake fails unless
   the certificate starts with the announced 8 bytes.
5. A swipe on the file card, or a Flick of the phone (`flick-gesture`), starts the send. Files stream over HTTPS.

Sound only starts the session. It carries no secret and no file data.

## Included and referenced work

Both projects are copied into `third_party/` with their licenses. See `NOTICE.md`.

| Project | License | Path | Used for |
| --- | --- | --- | --- |
| LocalSend | Apache 2.0 | `third_party/localsend/packages/core` | HTTPS server and client, certificate identity, resume, file model, multicast discovery on a shared network |
| DashBeam | AGPL 3.0 | `third_party/dashbeam/engine` | Copied, not called yet. Offers iroh QUIC transport, BLAKE3 verification, pairing and trusted devices |
| ggwave | MIT | `third_party/ggwave` | Data over sound |
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
