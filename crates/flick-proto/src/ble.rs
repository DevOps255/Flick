//! The frame a phone advertises over Bluetooth Low Energy.
//!
//! It carries the same [`Announcement`] as the ultrasonic version, plus whether the user just made
//! the pairing gesture. A legacy advertisement holds 31 bytes, and this frame uses 21 of them in
//! manufacturer data, so no extended advertising is needed.

use crate::{Announcement, ANNOUNCEMENT_LEN};

/// First byte of every frame. A scan filter on this byte hides other devices' manufacturer data.
pub const MAGIC: u8 = 0xF1;

/// Manufacturer id used in the advertisement. 0xFFFF is the value reserved for testing and internal use.
pub const MANUFACTURER_ID: u16 = 0xFFFF;

/// Size in bytes of an encoded [`BleFrame`].
pub const FRAME_LEN: usize = 1 + ANNOUNCEMENT_LEN + 2;

// The build fails if the frame ever outgrows a legacy advertisement: 31 bytes, of which 3 are flags
// and 4 are the manufacturer header.
const _: () = assert!(3 + 4 + FRAME_LEN <= 31);

/// What a phone says over Bluetooth.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BleFrame {
    pub announcement: Announcement,
    /// True for a few seconds after the user made the pairing gesture on this phone.
    pub gesture: bool,
    /// Grows by one at every gesture, so a repeated gesture is told apart from the same one still showing.
    pub gesture_seq: u8,
}

impl BleFrame {
    /// Serializes to `magic | announcement | flags | gesture_seq`.
    pub fn encode(&self) -> [u8; FRAME_LEN] {
        let mut out = [0u8; FRAME_LEN];
        out[0] = MAGIC;
        out[1..1 + ANNOUNCEMENT_LEN].copy_from_slice(&self.announcement.encode());
        out[1 + ANNOUNCEMENT_LEN] = u8::from(self.gesture);
        out[FRAME_LEN - 1] = self.gesture_seq;
        out
    }

    /// Parses manufacturer data. Returns `None` for anything that is not a Flick frame.
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != FRAME_LEN || bytes[0] != MAGIC {
            return None;
        }
        Some(Self {
            announcement: Announcement::decode(&bytes[1..1 + ANNOUNCEMENT_LEN]).ok()?,
            gesture: bytes[1 + ANNOUNCEMENT_LEN] & 1 == 1,
            gesture_seq: bytes[FRAME_LEN - 1],
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame() -> BleFrame {
        BleFrame {
            announcement: Announcement {
                fingerprint_prefix: [1; 8],
                ipv4: [192, 168, 49, 1],
                port: 53317,
                nonce: [4; 4],
            },
            gesture: true,
            gesture_seq: 7,
        }
    }

    #[test]
    fn round_trip() {
        assert_eq!(BleFrame::decode(&frame().encode()), Some(frame()));
    }

    #[test]
    fn foreign_data_is_ignored() {
        let mut bytes = frame().encode();
        bytes[0] = 0x00;
        assert_eq!(BleFrame::decode(&bytes), None);
        assert_eq!(BleFrame::decode(&bytes[..10]), None);
    }
}
