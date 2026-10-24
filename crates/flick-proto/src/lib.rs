//! Wire format of the ultrasonic announcement that starts a Flick session.
//!
//! A device broadcasts an [`Announcement`] as sound. A nearby device that
//! decodes it learns where to connect and which certificate to expect.
//! The sound carries no secret: the TLS handshake proves the sender holds the
//! certificate whose fingerprint starts with `fingerprint_prefix`.
//!
//! Eight bytes of the fingerprint are sent, not four. Four bytes (32 bits) can
//! be forged by trying certificates for a few seconds. Eight bytes (64 bits)
//! cannot be forged in practice.

use std::collections::HashMap;
use std::net::Ipv4Addr;
use std::time::{Duration, Instant};

/// Size in bytes of an encoded [`Announcement`].
pub const ANNOUNCEMENT_LEN: usize = 18;

/// What a device says out loud: which certificate it holds and where to reach it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Announcement {
    /// First 8 bytes of the SHA-256 of the sender's TLS certificate.
    pub fingerprint_prefix: [u8; 8],
    /// IPv4 address of the sender on the shared link (Wi-Fi, hotspot or Wi-Fi Direct).
    pub ipv4: [u8; 4],
    /// TCP port of the sender's transfer server.
    pub port: u16,
    /// Random value, different on every broadcast. Used to reject replays.
    pub nonce: [u8; 4],
}

/// Why a byte slice could not be decoded as an [`Announcement`].
#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    /// The slice is not exactly [`ANNOUNCEMENT_LEN`] bytes long.
    WrongLength(usize),
}

impl Announcement {
    /// Serializes to `fingerprint_prefix | ipv4 | port (big endian) | nonce`.
    pub fn encode(&self) -> [u8; ANNOUNCEMENT_LEN] {
        let mut out = [0u8; ANNOUNCEMENT_LEN];
        out[..8].copy_from_slice(&self.fingerprint_prefix);
        out[8..12].copy_from_slice(&self.ipv4);
        out[12..14].copy_from_slice(&self.port.to_be_bytes());
        out[14..].copy_from_slice(&self.nonce);
        out
    }

    /// Parses bytes produced by [`Announcement::encode`].
    pub fn decode(bytes: &[u8]) -> Result<Self, DecodeError> {
        if bytes.len() != ANNOUNCEMENT_LEN {
            return Err(DecodeError::WrongLength(bytes.len()));
        }
        Ok(Self {
            fingerprint_prefix: bytes[..8].try_into().expect("length checked"),
            ipv4: bytes[8..12].try_into().expect("length checked"),
            port: u16::from_be_bytes([bytes[12], bytes[13]]),
            nonce: bytes[14..].try_into().expect("length checked"),
        })
    }

    /// The address to connect to.
    pub fn address(&self) -> (Ipv4Addr, u16) {
        (Ipv4Addr::from(self.ipv4), self.port)
    }

    /// The prefix as uppercase hex, the form LocalSend uses to pin a certificate.
    pub fn fingerprint_prefix_hex(&self) -> String {
        self.fingerprint_prefix.iter().map(|b| format!("{b:02X}")).collect()
    }
}

/// Reads the first 8 bytes of a hex SHA-256 fingerprint.
///
/// Returns `None` when the first 16 characters are not valid hex.
pub fn fingerprint_prefix_from_hex(fingerprint_hex: &str) -> Option<[u8; 8]> {
    let head = fingerprint_hex.get(..16)?;
    let mut out = [0u8; 8];
    for (i, byte) in out.iter_mut().enumerate() {
        *byte = u8::from_str_radix(head.get(i * 2..i * 2 + 2)?, 16).ok()?;
    }
    Some(out)
}

/// Accepts each announcement once within a time window.
///
/// Someone could record the sound and play it back later. Remembering recent
/// nonces makes the second playback fail.
pub struct ReplayGuard {
    window: Duration,
    seen: HashMap<[u8; 4], Instant>,
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
        Announcement {
            fingerprint_prefix: [1, 2, 3, 4, 5, 6, 7, 8],
            ipv4: [192, 168, 49, 1],
            port: 53317,
            nonce: [9, 8, 7, 6],
        }
    }

    #[test]
    fn round_trip() {
        let a = sample();
        assert_eq!(Announcement::decode(&a.encode()), Ok(a));
    }

    #[test]
    fn exposes_address_and_hex_pin() {
        let a = sample();
        assert_eq!(a.address(), (Ipv4Addr::new(192, 168, 49, 1), 53317));
        assert_eq!(a.fingerprint_prefix_hex(), "0102030405060708");
    }

    #[test]
    fn prefix_comes_from_fingerprint() {
        assert_eq!(
            fingerprint_prefix_from_hex("0A1B2C3D4E5F6071FFFF"),
            Some([0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x60, 0x71])
        );
        assert_eq!(fingerprint_prefix_from_hex("zz"), None);
        assert_eq!(fingerprint_prefix_from_hex("0A1B2C3D4E5F60ZZ"), None);
        assert_eq!(fingerprint_prefix_from_hex("0A1B2C3D"), None);
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
