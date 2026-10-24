//! Sends and receives files between nearby devices on top of LocalSend's core.
//!
//! LocalSend already gives every device a TLS certificate and identifies it by
//! the SHA-256 of that certificate. Flick reuses that identity: the sound only
//! carries the first 8 bytes, and the TLS handshake proves the rest. A recorded
//! announcement is useless to an attacker who does not hold the certificate key.

mod identity;
pub mod link;
mod node;

pub use identity::Identity;
pub use node::{check_prefix, Event, Node, Observer, OutgoingFile, Target};

use flick_proto::Announcement;

/// Builds the announcement a device plays: who it is and where to reach it.
pub fn announcement_for(identity: &Identity, ipv4: [u8; 4], port: u16, nonce: [u8; 4]) -> Option<Announcement> {
    Some(Announcement { fingerprint_prefix: identity.prefix().ok()?, ipv4, port, nonce })
}

/// Turns a heard announcement into the target to send to.
pub fn target_from(announcement: &Announcement) -> Target {
    let (ip, port) = announcement.address();
    Target { host: ip.to_string(), port, fingerprint_prefix: announcement.fingerprint_prefix_hex() }
}
