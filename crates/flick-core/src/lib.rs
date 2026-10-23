//! Joins the ultrasonic announcement to LocalSend's authenticated transfer.
//!
//! LocalSend already gives every device a TLS certificate and identifies it by
//! the SHA-256 of that certificate. Flick reuses that identity: the sound only
//! carries the first 4 bytes, and the TLS handshake proves the rest. A recorded
//! announcement is useless to an attacker who does not hold the certificate key.

use flick_proto::{session_id_from_fingerprint, Announcement};
use localsend::crypto::cert::fingerprint_from_cert_der;

/// Builds the announcement a device plays for its own certificate.
///
/// `port` is where the LocalSend HTTPS server listens. `nonce` must be random
/// and fresh for every broadcast.
pub fn announcement_for_cert(cert_der: &[u8], port: u16, nonce: [u8; 6]) -> Option<Announcement> {
    let session_id = session_id_from_fingerprint(&fingerprint_from_cert_der(cert_der))?;
    Some(Announcement { session_id, port, nonce })
}

/// Checks that the certificate a peer presented is the one it announced.
///
/// Call this after the TLS handshake and before sending any file.
pub fn cert_matches_announcement(cert_der: &[u8], announcement: &Announcement) -> bool {
    session_id_from_fingerprint(&fingerprint_from_cert_der(cert_der)) == Some(announcement.session_id)
}
