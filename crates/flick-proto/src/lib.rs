//! Wire format of the ultrasonic announcement that starts a Flick session.
//!
//! A device broadcasts an [`Announcement`] as sound. A nearby device that
//! decodes it learns where to connect (`port`) and which session to join.
//! The audio carries no secret: authentication happens afterwards over TLS,
//! where the peer certificate fingerprint must match `session_id`.
//!
//! The 12 byte size is chosen so one ggwave ultrasound frame carries it in
//! about one second.

use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Size in bytes of an encoded [`Announcement`].
pub const ANNOUNCEMENT_LEN: usize = 12;

/// What a device says out loud: who it is for this session and where to reach it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Announcement {
    /// First 4 bytes of the SHA-256 of the sender's TLS certificate.
    pub session_id: [u8; 4],
    /// TCP port of the sender's transfer server.
    pub port: u16,
    /// Random value, different on every broadcast. Used to reject replays.
    pub nonce: [u8; 6],
}

/// Why a byte slice could not be decoded as an [`Announcement`].
#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    /// The slice is not exactly [`ANNOUNCEMENT_LEN`] bytes long.
    WrongLength(usize),
}

impl Announcement {
    /// Serializes to `session_id | port (big endian) | nonce`.
    pub fn encode(&self) -> [u8; ANNOUNCEMENT_LEN] {
        let mut out = [0u8; ANNOUNCEMENT_LEN];
        out[..4].copy_from_slice(&self.session_id);
        out[4..6].copy_from_slice(&self.port.to_be_bytes());
        out[6..].copy_from_slice(&self.nonce);
        out
    }

    /// Parses bytes produced by [`Announcement::encode`].
    pub fn decode(bytes: &[u8]) -> Result<Self, DecodeError> {
        if bytes.len() != ANNOUNCEMENT_LEN {
            return Err(DecodeError::WrongLength(bytes.len()));
        }
        Ok(Self {
            session_id: bytes[..4].try_into().expect("length checked"),
            port: u16::from_be_bytes([bytes[4], bytes[5]]),
            nonce: bytes[6..].try_into().expect("length checked"),
        })
    }
}

/// Derives a `session_id` from a hex SHA-256 certificate fingerprint.
///
/// Returns `None` when the first 8 characters are not valid hex.
pub fn session_id_from_fingerprint(fingerprint_hex: &str) -> Option<[u8; 4]> {
    let head = fingerprint_hex.get(..8)?;
    let mut id = [0u8; 4];
    for (i, byte) in id.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&head[i * 2..i * 2 + 2], 16).ok()?;
    }
    Some(id)
}

/// Accepts each announcement once within a time window.
///
/// Someone could record the sound and play it back later. Remembering recent
/// nonces makes the second playback fail.
pub struct ReplayGuard {
    window: Duration,
    seen: HashMap<[u8; 6], Instant>,
}

impl ReplayGuard {
    /// Creates a guard that remembers nonces for `window`.
    pub fn new(window: Duration) -> Self {
        Self { window, seen: HashMap::new() }
    }

    /// Returns `true` the first time a nonce is seen inside the window.
    pub fn accept(&mut self, announcement: &Announcement, now: Instant) -> bool {
        let window = self.window;
        self.seen.retain(|_, at| now.duration_since(*at) < window);
        // A rejected replay must not refresh the timestamp, or the nonce never expires.
        match self.seen.entry(announcement.nonce) {
            std::collections::hash_map::Entry::Occupied(_) => false,
            std::collections::hash_map::Entry::Vacant(slot) => {
                slot.insert(now);
                true
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Announcement {
        Announcement { session_id: [1, 2, 3, 4], port: 53317, nonce: [9, 8, 7, 6, 5, 4] }
    }

    #[test]
    fn round_trip() {
        let a = sample();
        assert_eq!(Announcement::decode(&a.encode()), Ok(a));
    }

    #[test]
    fn session_id_comes_from_fingerprint_prefix() {
        assert_eq!(session_id_from_fingerprint("0A1B2C3Dffff"), Some([0x0a, 0x1b, 0x2c, 0x3d]));
        assert_eq!(session_id_from_fingerprint("zz"), None);
        assert_eq!(session_id_from_fingerprint("0A1B2CZZ"), None);
    }

    #[test]
    fn rejects_wrong_length() {
        assert_eq!(Announcement::decode(&[0; 11]), Err(DecodeError::WrongLength(11)));
    }

    #[test]
    fn replay_is_rejected_then_allowed_after_window() {
        let mut guard = ReplayGuard::new(Duration::from_secs(30));
        let t0 = Instant::now();
        assert!(guard.accept(&sample(), t0));
        assert!(!guard.accept(&sample(), t0 + Duration::from_secs(5)));
        assert!(guard.accept(&sample(), t0 + Duration::from_secs(31)));
    }
}
