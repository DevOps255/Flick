# Architecture

## Flow of one transfer

1. The user picks files. Both devices have Flick open.
2. The sender plays an `Announcement` (`flick-proto`) in the 18 kHz band.
3. The receiver decodes it, rejects replays, and learns the sender's port.
4. Both devices join a Wi-Fi Direct group. The sender connects over TLS and
   checks the certificate fingerprint against `session_id`.
5. A Flick (`flick-gesture`) confirms the intent to send. Files stream over HTTPS.

Sound only starts the session. It carries no secret and no file data.

## Included and referenced work

Both projects are copied into `third_party/` with their licenses. See `NOTICE.md`.

| Project | License | Path | Used for |
| --- | --- | --- | --- |
| LocalSend | Apache 2.0 | `third_party/localsend/packages/core` | HTTPS server and client, certificate identity, resume, file model, multicast discovery on a shared network |
| DashBeam | AGPL 3.0 | `third_party/dashbeam/engine` | iroh QUIC transport for peers without a direct link, BLAKE3 verification, pairing, trusted devices, transfer history |
| ggwave | MIT | not copied yet | Data over sound |
| BluetoothProximity | MIT | reference | Ultrasonic ranging method, to be ported to Rust |
| NameDrop | MIT | reference | Ripple shader, to be ported to AGSL |

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
