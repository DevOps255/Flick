//! The identity of this PC: a certificate whose fingerprint starts with zeros.
//!
//! Two devices with no router between them need a network, and one of them must create it. A phone can. A PC cannot create
//! the kind of network a phone makes, it can only join one. The rule that decides who creates is fixed by the fingerprints: the
//! device with the larger prefix creates. A PC whose prefix starts with zeros is smaller than any phone, so the phone always
//! creates the network and the PC always joins it, with no extra message and no change to the phone.
//!
//! About one certificate in 4096 has such a prefix, so finding one takes a few seconds, once, at the first start.

use anyhow::{Context, Result};
use flick_core::Identity;
use std::path::Path;

/// A prefix that starts with a zero byte and then a byte below 16 is smaller than all but one phone in 4000.
pub fn is_hosted_by_phones(prefix: &[u8; 8]) -> bool {
    prefix[0] == 0 && prefix[1] < 0x10
}

/// Loads the saved identity, or makes a new one with a small prefix and saves it.
pub fn load_or_create(dir: &Path) -> Result<Identity> {
    let file = dir.join("identity.pem");
    if let Ok(text) = std::fs::read_to_string(&file) {
        if let Ok(identity) = Identity::from_pem(&text) {
            if identity
                .prefix()
                .is_ok_and(|prefix| is_hosted_by_phones(&prefix))
            {
                return Ok(identity);
            }
        }
    }
    let identity = loop {
        let candidate = Identity::generate()?;
        if candidate
            .prefix()
            .is_ok_and(|prefix| is_hosted_by_phones(&prefix))
        {
            break candidate;
        }
    };
    std::fs::write(&file, identity.to_pem()).context("cannot save the identity")?;
    Ok(identity)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_prefix_of_zeros_is_hosted_by_phones() {
        assert!(is_hosted_by_phones(&[0, 3, 200, 1, 1, 1, 1, 1]));
        assert!(is_hosted_by_phones(&[
            0, 0x0F, 255, 255, 255, 255, 255, 255
        ]));
    }

    #[test]
    fn other_prefixes_are_not() {
        assert!(!is_hosted_by_phones(&[1, 0, 0, 0, 0, 0, 0, 0]));
        assert!(!is_hosted_by_phones(&[0, 0x10, 0, 0, 0, 0, 0, 0]));
    }
}
