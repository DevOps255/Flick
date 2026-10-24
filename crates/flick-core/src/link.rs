//! Decides how two phones that heard each other reach each other.
//!
//! If both are already on one network (the same Wi-Fi, or one phone's hotspot)
//! nothing is needed. Otherwise they form a Wi-Fi Direct group. One phone must
//! create the group and the other must join it, and both must agree on the
//! group's name and password without talking to each other first.
//!
//! They agree because both derive everything from the two fingerprint
//! prefixes they just exchanged by sound: the phone with the larger prefix
//! creates the group, and the name and password are a hash of both prefixes.
//! The password is not a secret, anyone who heard both announcements can
//! compute it. It does not need to be: every transfer still has to pass the
//! TLS certificate check.

use flick_proto::Announcement;
use localsend::crypto::hash::sha256;

/// Smallest subnet prefix length accepted. Anything shorter would call the whole internet one network.
const MIN_PREFIX_LEN: u8 = 8;

/// An address this phone has on a local network.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LocalLink {
    pub ip: [u8; 4],
    /// Number of leading bits shared by every address on the network, for example 24.
    pub prefix_len: u8,
}

/// Name and password of the Wi-Fi Direct group.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Credentials {
    /// Starts with `DIRECT-FK-`, the form Android requires for a group name.
    pub ssid: String,
    pub passphrase: String,
}

/// What this phone has to do to reach the one it heard.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LinkPlan {
    /// The peer is on a network this phone already has.
    SameNetwork,
    /// Create the group. The peer joins it.
    CreateGroup(Credentials),
    /// Join the group the peer creates.
    JoinGroup(Credentials),
}

/// True when both addresses fall in the same subnet of `prefix_len` bits.
pub fn same_subnet(a: [u8; 4], b: [u8; 4], prefix_len: u8) -> bool {
    if !(MIN_PREFIX_LEN..=32).contains(&prefix_len) {
        return false;
    }
    let mask = u32::MAX.checked_shl(32 - u32::from(prefix_len)).unwrap_or(0);
    (u32::from_be_bytes(a) & mask) == (u32::from_be_bytes(b) & mask)
}

/// Name and password for the group between two phones. Identical on both, whatever the argument order.
pub fn credentials(a: &[u8; 8], b: &[u8; 8]) -> Credentials {
    let (low, high) = if a <= b { (a, b) } else { (b, a) };
    let mut input = b"flick-link-v1".to_vec();
    input.extend_from_slice(low);
    input.extend_from_slice(high);
    let hex: String = sha256(&input).iter().map(|byte| format!("{byte:02X}")).collect();
    Credentials { ssid: format!("DIRECT-FK-{}", &hex[..8]), passphrase: hex[8..24].to_string() }
}

/// Chooses what this phone does about `peer`, given the networks it is on.
pub fn plan(own_prefix: &[u8; 8], own_links: &[LocalLink], peer: &Announcement) -> LinkPlan {
    let reachable = peer.ipv4 != [0; 4]
        && own_links.iter().any(|link| link.ip != [0; 4] && same_subnet(link.ip, peer.ipv4, link.prefix_len));
    if reachable {
        return LinkPlan::SameNetwork;
    }
    let creds = credentials(own_prefix, &peer.fingerprint_prefix);
    if own_prefix > &peer.fingerprint_prefix {
        LinkPlan::CreateGroup(creds)
    } else {
        LinkPlan::JoinGroup(creds)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ME: [u8; 8] = [9, 9, 9, 9, 9, 9, 9, 9];
    const YOU: [u8; 8] = [3, 3, 3, 3, 3, 3, 3, 3];

    fn heard(ipv4: [u8; 4]) -> Announcement {
        Announcement { fingerprint_prefix: YOU, ipv4, port: 53317, nonce: [0; 4] }
    }

    fn wifi() -> LocalLink {
        LocalLink { ip: [192, 168, 1, 20], prefix_len: 24 }
    }

    #[test]
    fn same_wifi_needs_no_link() {
        assert_eq!(plan(&ME, &[wifi()], &heard([192, 168, 1, 77])), LinkPlan::SameNetwork);
    }

    #[test]
    fn different_subnet_needs_a_link() {
        assert!(matches!(plan(&ME, &[wifi()], &heard([192, 168, 2, 77])), LinkPlan::CreateGroup(_)));
    }

    #[test]
    fn no_address_on_either_side_needs_a_link() {
        assert!(matches!(plan(&ME, &[], &heard([0; 4])), LinkPlan::CreateGroup(_)));
        assert!(matches!(plan(&ME, &[wifi()], &heard([0; 4])), LinkPlan::CreateGroup(_)));
        assert!(matches!(plan(&ME, &[LocalLink { ip: [0; 4], prefix_len: 24 }], &heard([0; 4])), LinkPlan::CreateGroup(_)));
    }

    #[test]
    fn already_in_the_same_direct_group_needs_nothing() {
        let group = LocalLink { ip: [192, 168, 49, 1], prefix_len: 24 };
        assert_eq!(plan(&ME, &[wifi(), group], &heard([192, 168, 49, 5])), LinkPlan::SameNetwork);
    }

    #[test]
    fn the_two_phones_take_opposite_roles_and_agree_on_the_group() {
        let mine = plan(&ME, &[], &heard([0; 4]));
        let theirs = plan(
            &YOU,
            &[],
            &Announcement { fingerprint_prefix: ME, ipv4: [0; 4], port: 1, nonce: [0; 4] },
        );
        match (mine, theirs) {
            (LinkPlan::CreateGroup(a), LinkPlan::JoinGroup(b)) => assert_eq!(a, b),
            other => panic!("expected one creator and one joiner, got {other:?}"),
        }
    }

    #[test]
    fn credentials_have_the_shape_android_requires() {
        let c = credentials(&ME, &YOU);
        assert_eq!(c, credentials(&YOU, &ME));
        assert!(c.ssid.starts_with("DIRECT-FK-") && c.ssid.len() <= 32);
        assert!((8..=63).contains(&c.passphrase.len()));
        assert_ne!(c, credentials(&ME, &[4; 8]), "another pair gets another group");
    }

    #[test]
    fn subnet_edges() {
        assert!(same_subnet([10, 0, 0, 1], [10, 0, 255, 254], 16));
        assert!(!same_subnet([10, 0, 0, 1], [10, 1, 0, 1], 16));
        assert!(same_subnet([192, 168, 1, 1], [192, 168, 1, 1], 32));
        assert!(!same_subnet([192, 168, 1, 1], [192, 168, 1, 2], 32));
        assert!(!same_subnet([1, 1, 1, 1], [200, 2, 2, 2], 0), "a zero length prefix is refused");
        assert!(!same_subnet([1, 1, 1, 1], [1, 1, 1, 1], 33));
    }
}
