//! A device's TLS identity. The fingerprint of its certificate is what other
//! devices pin, and what the ultrasonic announcement carries a prefix of.

use anyhow::{anyhow, Context, Result};
use flick_proto::fingerprint_prefix_from_hex;
use localsend::crypto::cert::{fingerprint_from_cert_der, generate_self_signed};

#[derive(Clone)]
pub struct Identity {
    pub cert_pem: String,
    pub key_pem: String,
    /// SHA-256 of the certificate, uppercase hex.
    pub fingerprint: String,
}

impl Identity {
    /// Creates a new self-signed identity. Do this once and store it with [`Identity::to_pem`].
    pub fn generate() -> Result<Self> {
        let cert = generate_self_signed()?;
        Ok(Self { cert_pem: cert.certificate_pem, key_pem: cert.private_key_pem, fingerprint: cert.fingerprint })
    }

    /// Loads an identity saved with [`Identity::to_pem`].
    pub fn from_pem(text: &str) -> Result<Self> {
        let blocks = pem::parse_many(text)?;
        let cert = blocks.iter().find(|b| b.tag() == "CERTIFICATE").context("missing CERTIFICATE block")?;
        let key = blocks.iter().find(|b| b.tag().ends_with("PRIVATE KEY")).context("missing PRIVATE KEY block")?;
        Ok(Self {
            fingerprint: fingerprint_from_cert_der(cert.contents()),
            cert_pem: pem::encode(cert),
            key_pem: pem::encode(key),
        })
    }

    /// Certificate followed by private key. The file holds a secret, keep it private.
    pub fn to_pem(&self) -> String {
        format!("{}{}", self.cert_pem, self.key_pem)
    }

    /// The 8 byte prefix that goes into the announcement.
    pub fn prefix(&self) -> Result<[u8; 8]> {
        fingerprint_prefix_from_hex(&self.fingerprint).ok_or_else(|| anyhow!("fingerprint is not hex"))
    }
}
