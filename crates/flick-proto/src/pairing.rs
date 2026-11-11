//! Decides which nearby phone the user means.
//!
//! Phones advertise over Bluetooth all the time. One phone is enough to start a pairing, in three ways:
//!
//! 1. The user made the pairing gesture here. The closest phone that is near enough is paired, even if
//!    its owner did nothing.
//! 2. A phone that is near enough made the gesture. This phone pairs back with it, so both phones start
//!    together even though only one of them was moved.
//! 3. A phone is held against this one. A signal as strong as touching phones give, held for a short
//!    moment, pairs without any gesture, like two iPhones brought top to top.
//!
//! If two phones are about as close, the answer is ambiguous and nothing is paired, rather than pairing
//! the wrong phone. A phone that made the gesture counts as closer than one that did not.
//!
//! Distance comes from the signal strength. It is noisy, so each phone's readings pass through a
//! Kalman filter, and the distance is a rough estimate from a path loss model, not a measurement.
//! All times are passed in, so the logic has no clock and tests are exact.

use crate::ble::BleFrame;
use crate::Announcement;
use std::collections::HashMap;

/// Signal strength, in dBm, measured one metre from a typical phone advertising at medium power.
const RSSI_AT_ONE_METRE: f32 = -59.0;
/// How fast the signal fades with distance. 2 is open air, indoors it is higher.
const PATH_LOSS_EXPONENT: f32 = 2.2;
/// A gesture made here waits this many milliseconds for a phone to be near, and counts for a phone that made one.
const GESTURE_WINDOW_MS: u64 = 2_500;
/// A gesture made on the other phone is answered within this many milliseconds, or it is old news.
const HEARD_GESTURE_WINDOW_MS: u64 = 1_500;
/// A phone weaker than this, about two metres, is too far to be the one the user means.
const NEAR_RSSI_DBM: f32 = -65.0;
/// A phone this strong is touching. Held for [`CONTACT_HOLD_MS`] it pairs without any gesture.
const CONTACT_RSSI_DBM: f32 = -52.0;
const CONTACT_HOLD_MS: u64 = 400;
/// A phone must fall below this before a contact can pair again, so phones lying side by side do not pair in a loop.
const CONTACT_REARM_RSSI_DBM: f32 = -62.0;
/// A phone not heard for this long is no longer nearby.
const SEEN_WITHIN_MS: u64 = 3_000;
/// The best phone must beat the second by this many dB, about 1.6 times closer, or the pairing is ambiguous.
const MARGIN_DB: f32 = 6.0;

/// Smooths noisy signal strength readings. One dimension Kalman filter.
#[derive(Clone, Copy, Debug)]
pub struct Smoother {
    estimate: f32,
    variance: f32,
    started: bool,
}

impl Default for Smoother {
    fn default() -> Self {
        Self::new()
    }
}

impl Smoother {
    /// How much the true value is allowed to move between two readings, in dB squared.
    const DRIFT: f32 = 0.5;
    /// How noisy one reading is, in dB squared. Bluetooth readings vary by about 4 dB.
    const NOISE: f32 = 16.0;

    pub fn new() -> Self {
        Self {
            estimate: 0.0,
            variance: 0.0,
            started: false,
        }
    }

    /// Adds a reading and returns the smoothed value.
    pub fn update(&mut self, reading: f32) -> f32 {
        if !self.started {
            *self = Self {
                estimate: reading,
                variance: Self::NOISE,
                started: true,
            };
            return reading;
        }
        self.variance += Self::DRIFT;
        let gain = self.variance / (self.variance + Self::NOISE);
        self.estimate += gain * (reading - self.estimate);
        self.variance *= 1.0 - gain;
        self.estimate
    }
}

/// Rough distance in metres for a signal strength, from the log distance path loss model.
pub fn distance_m(rssi_dbm: f32) -> f32 {
    10f32.powf((RSSI_AT_ONE_METRE - rssi_dbm) / (10.0 * PATH_LOSS_EXPONENT))
}

/// 1 when touching, 0 at two metres or more.
pub fn proximity(distance_m: f32) -> f32 {
    (1.0 - (distance_m - 0.1) / 1.9).clamp(0.0, 1.0)
}

/// A phone heard recently.
#[derive(Clone, Debug)]
pub struct PeerView {
    /// Fingerprint prefix as uppercase hex.
    pub id: String,
    pub announcement: Announcement,
    /// Smoothed signal strength.
    pub rssi_dbm: f32,
    pub distance_m: f32,
    pub proximity: f32,
}

/// The answer to "did the user pair with someone".
#[derive(Clone, Debug)]
pub enum Outcome {
    /// No gesture here, or no phone made one yet.
    Pending,
    /// Two or more phones are about as close. Nothing is paired.
    Ambiguous,
    Paired(PeerView),
}

struct PeerState {
    announcement: Announcement,
    rssi: Smoother,
    smoothed: f32,
    last_seen_ms: u64,
    last_seq: u8,
    gesture_seen_ms: Option<u64>,
    /// The gesture time already answered, so one gesture pairs once.
    gesture_answered_ms: Option<u64>,
    /// Since when the signal has been as strong as touching phones give.
    contact_since_ms: Option<u64>,
    /// True once a contact paired, until the phone moves away.
    contact_used: bool,
}

/// Follows the phones nearby and decides when the user paired with one.
#[derive(Default)]
pub struct Pairing {
    peers: HashMap<[u8; 8], PeerState>,
    own_gesture_ms: Option<u64>,
}

impl Pairing {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a frame heard at `now_ms` with the signal strength it arrived with.
    pub fn on_frame(&mut self, frame: &BleFrame, rssi_dbm: i32, now_ms: u64) {
        let key = frame.announcement.fingerprint_prefix;
        let first = !self.peers.contains_key(&key);
        let peer = self.peers.entry(key).or_insert_with(|| PeerState {
            announcement: frame.announcement,
            rssi: Smoother::new(),
            smoothed: rssi_dbm as f32,
            last_seen_ms: now_ms,
            last_seq: frame.gesture_seq,
            gesture_seen_ms: None,
            gesture_answered_ms: None,
            contact_since_ms: None,
            contact_used: false,
        });
        peer.announcement = frame.announcement;
        peer.smoothed = peer.rssi.update(rssi_dbm as f32);
        peer.last_seen_ms = now_ms;
        // A gesture is new when its counter moved. A phone first seen with the flag already up made the gesture just before.
        let new_gesture = frame.gesture && (frame.gesture_seq != peer.last_seq || first);
        if new_gesture {
            peer.gesture_seen_ms = Some(now_ms);
        }
        peer.last_seq = frame.gesture_seq;
    }

    /// Records that the user made the pairing gesture on this phone.
    pub fn on_own_gesture(&mut self, now_ms: u64) {
        self.own_gesture_ms = Some(now_ms);
    }

    /// The phones heard recently, closest first.
    pub fn peers(&self, now_ms: u64) -> Vec<PeerView> {
        let mut views: Vec<PeerView> = self
            .peers
            .iter()
            .filter(|(_, p)| now_ms.saturating_sub(p.last_seen_ms) <= SEEN_WITHIN_MS)
            .map(|(key, p)| view(key, p))
            .collect();
        views.sort_by(|a, b| b.rssi_dbm.total_cmp(&a.rssi_dbm));
        views
    }

    /// Checks whether a pairing happened. A pairing is reported once.
    pub fn poll(&mut self, now_ms: u64) -> Outcome {
        self.track_contacts(now_ms);

        // 1. The user made the gesture here. The closest near phone is the one meant.
        if let Some(own) = self.own_gesture_ms {
            if now_ms.saturating_sub(own) > GESTURE_WINDOW_MS {
                self.own_gesture_ms = None;
            } else {
                match self.decide(now_ms, own, |_| true) {
                    Decision::Paired(key) => {
                        self.own_gesture_ms = None;
                        return self.paired(key);
                    }
                    Decision::Ambiguous => return Outcome::Ambiguous,
                    Decision::Nobody => return Outcome::Pending,
                }
            }
        }

        // 2. A near phone made the gesture. Pair back, so both phones start together.
        let heard = self
            .peers
            .iter()
            .filter(|(_, p)| p.gesture_answered_ms != p.gesture_seen_ms)
            .filter_map(|(_, p)| p.gesture_seen_ms)
            .filter(|g| now_ms.saturating_sub(*g) <= HEARD_GESTURE_WINDOW_MS)
            .max();
        if let Some(gesture) = heard {
            if let Decision::Paired(key) =
                self.decide(now_ms, gesture, |p| p.gesture_seen_ms == Some(gesture))
            {
                return self.paired(key);
            }
        }

        // 3. A phone is held against this one.
        let touching: Vec<[u8; 8]> = self
            .peers
            .iter()
            .filter(|(_, p)| !p.contact_used)
            .filter(|(_, p)| {
                p.contact_since_ms
                    .is_some_and(|since| now_ms.saturating_sub(since) >= CONTACT_HOLD_MS)
            })
            .map(|(key, _)| *key)
            .collect();
        if !touching.is_empty() {
            if let Decision::Paired(key) = self.decide(now_ms, now_ms, |_| true) {
                if touching.contains(&key) {
                    return self.paired(key);
                }
            }
        }
        Outcome::Pending
    }

    /// Follows which phones are touching this one.
    fn track_contacts(&mut self, now_ms: u64) {
        for p in self.peers.values_mut() {
            if p.smoothed >= CONTACT_RSSI_DBM
                && now_ms.saturating_sub(p.last_seen_ms) <= SEEN_WITHIN_MS
            {
                p.contact_since_ms.get_or_insert(now_ms);
            } else if p.smoothed < CONTACT_REARM_RSSI_DBM
                || now_ms.saturating_sub(p.last_seen_ms) > SEEN_WITHIN_MS
            {
                p.contact_since_ms = None;
                p.contact_used = false;
            }
        }
    }

    /// Picks the phone meant among those near, heard recently, and accepted by `allowed`.
    /// A phone that made a gesture around `reference_ms` counts as closer by the margin.
    fn decide(
        &self,
        now_ms: u64,
        reference_ms: u64,
        allowed: impl Fn(&PeerState) -> bool,
    ) -> Decision {
        let mut near: Vec<([u8; 8], f32, bool)> = self
            .peers
            .iter()
            .filter(|(_, p)| now_ms.saturating_sub(p.last_seen_ms) <= SEEN_WITHIN_MS)
            .filter(|(_, p)| p.smoothed >= NEAR_RSSI_DBM)
            .map(|(key, p)| {
                let gestured = p
                    .gesture_seen_ms
                    .is_some_and(|g| g.abs_diff(reference_ms) <= GESTURE_WINDOW_MS);
                (
                    *key,
                    p.smoothed + if gestured { MARGIN_DB } else { 0.0 },
                    allowed(p),
                )
            })
            .collect();
        near.sort_by(|a, b| b.1.total_cmp(&a.1));
        match near.as_slice() {
            [] => Decision::Nobody,
            [(key, _, true)] => Decision::Paired(*key),
            [(_, _, false)] => Decision::Nobody,
            [(key, best, true), (_, second, _), ..] if best - second >= MARGIN_DB => {
                Decision::Paired(*key)
            }
            [(_, _, false), ..] => Decision::Nobody,
            _ => Decision::Ambiguous,
        }
    }

    /// Marks a pairing as reported and builds its answer.
    fn paired(&mut self, key: [u8; 8]) -> Outcome {
        let Some(peer) = self.peers.get_mut(&key) else {
            return Outcome::Pending;
        };
        peer.gesture_answered_ms = peer.gesture_seen_ms;
        peer.contact_used = true;
        Outcome::Paired(view(&key, peer))
    }
}

enum Decision {
    Nobody,
    Ambiguous,
    Paired([u8; 8]),
}

fn view(key: &[u8; 8], p: &PeerState) -> PeerView {
    let distance = distance_m(p.smoothed);
    PeerView {
        id: key.iter().map(|b| format!("{b:02X}")).collect(),
        announcement: p.announcement,
        rssi_dbm: p.smoothed,
        distance_m: distance,
        proximity: proximity(distance),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(id: u8, gesture: bool, seq: u8) -> BleFrame {
        BleFrame {
            announcement: Announcement {
                fingerprint_prefix: [id; 8],
                ipv4: [0; 4],
                port: 1,
                nonce: [0; 4],
            },
            gesture,
            gesture_seq: seq,
        }
    }

    #[test]
    fn smoother_calms_a_noisy_signal() {
        let mut s = Smoother::new();
        let noisy = [
            -60.0, -68.0, -55.0, -66.0, -57.0, -64.0, -58.0, -63.0, -59.0, -62.0, -60.0, -61.0,
        ];
        let out: Vec<f32> = noisy.iter().map(|r| s.update(*r)).collect();
        let spread = |v: &[f32]| {
            v.iter().cloned().fold(f32::MIN, f32::max) - v.iter().cloned().fold(f32::MAX, f32::min)
        };
        assert!(
            spread(&out[4..]) < spread(&noisy[4..]) / 2.0,
            "smoothed readings vary less"
        );
        assert!(
            (out[11] + 61.0).abs() < 3.0,
            "and settle near the true level"
        );
    }

    #[test]
    fn distance_grows_as_the_signal_fades() {
        assert!((distance_m(-59.0) - 1.0).abs() < 1e-3);
        assert!(distance_m(-45.0) < 0.5 && distance_m(-80.0) > 5.0);
        assert!(proximity(0.1) == 1.0 && proximity(2.0) == 0.0 && proximity(1.0) > 0.0);
    }

    #[test]
    fn one_gesture_here_pairs_the_closest_phone() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -50, 1_000);
        p.on_own_gesture(2_000);
        assert!(
            matches!(p.poll(2_100), Outcome::Paired(v) if v.id == "0101010101010101"),
            "the other phone did nothing and is still paired"
        );
        assert!(matches!(p.poll(2_200), Outcome::Pending), "reported once");
    }

    #[test]
    fn a_gesture_waits_for_the_phone_to_be_heard() {
        let mut p = Pairing::new();
        p.on_own_gesture(2_000);
        assert!(matches!(p.poll(2_100), Outcome::Pending));
        p.on_frame(&frame(1, false, 0), -50, 2_300);
        assert!(matches!(p.poll(2_400), Outcome::Paired(_)));
    }

    #[test]
    fn a_phone_that_is_too_far_is_not_paired() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -80, 1_000);
        p.on_own_gesture(1_100);
        assert!(matches!(p.poll(1_200), Outcome::Pending));
    }

    #[test]
    fn a_near_phone_that_gestured_pairs_this_phone_back() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -50, 1_000);
        p.on_frame(&frame(1, true, 1), -50, 1_300);
        assert!(
            matches!(p.poll(1_400), Outcome::Paired(v) if v.id == "0101010101010101"),
            "nothing was done on this phone"
        );
        assert!(
            matches!(p.poll(1_500), Outcome::Pending),
            "one gesture pairs once"
        );
    }

    #[test]
    fn a_far_phone_that_gestured_does_not_pair_this_phone() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -85, 1_000);
        p.on_frame(&frame(1, true, 1), -85, 1_300);
        assert!(matches!(p.poll(1_400), Outcome::Pending));
    }

    #[test]
    fn the_phone_that_gestured_first_still_pairs() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -50, 1_000);
        p.on_frame(&frame(1, true, 1), -50, 1_500);
        p.on_own_gesture(2_200);
        assert!(matches!(p.poll(2_300), Outcome::Paired(_)));
    }

    #[test]
    fn a_flag_that_stays_up_is_one_gesture_not_many() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, true, 3), -50, 1_000);
        assert!(matches!(p.poll(1_100), Outcome::Paired(_)));
        p.on_frame(&frame(1, true, 3), -50, 5_000);
        assert!(
            matches!(p.poll(5_100), Outcome::Pending),
            "the flag is still up but the gesture counter did not move"
        );
    }

    #[test]
    fn an_old_gesture_of_the_other_phone_is_not_answered() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, true, 1), -50, 1_000);
        assert!(matches!(p.poll(4_000), Outcome::Pending));
    }

    #[test]
    fn a_phone_held_against_this_one_pairs_after_a_short_moment() {
        let mut p = Pairing::new();
        for t in (1_000..1_300).step_by(100) {
            p.on_frame(&frame(1, false, 0), -45, t);
            assert!(
                matches!(p.poll(t), Outcome::Pending),
                "not yet held long enough at {t}"
            );
        }
        p.on_frame(&frame(1, false, 0), -45, 1_500);
        assert!(matches!(p.poll(1_500), Outcome::Paired(v) if v.id == "0101010101010101"));
        p.on_frame(&frame(1, false, 0), -45, 2_500);
        assert!(
            matches!(p.poll(2_500), Outcome::Pending),
            "phones lying side by side do not pair in a loop"
        );
    }

    #[test]
    fn a_phone_that_moved_away_and_came_back_pairs_again() {
        let mut p = Pairing::new();
        for t in (1_000..=1_600).step_by(100) {
            p.on_frame(&frame(1, false, 0), -45, t);
            p.poll(t);
        }
        for t in (2_000..=3_000).step_by(100) {
            p.on_frame(&frame(1, false, 0), -75, t);
            p.poll(t);
        }
        let mut paired = false;
        for t in (4_000..=6_500).step_by(100) {
            p.on_frame(&frame(1, false, 0), -45, t);
            paired |= matches!(p.poll(t), Outcome::Paired(_));
        }
        assert!(paired);
    }

    #[test]
    fn two_phones_touching_are_not_paired_by_contact() {
        let mut p = Pairing::new();
        for t in (1_000..=2_000).step_by(100) {
            p.on_frame(&frame(1, false, 0), -45, t);
            p.on_frame(&frame(2, false, 0), -46, t);
            assert!(!matches!(p.poll(t), Outcome::Paired(_)), "ambiguous at {t}");
        }
    }

    #[test]
    fn the_closest_of_two_gesturing_phones_wins_when_clearly_closer() {
        let mut p = Pairing::new();
        for t in [1_000, 1_100, 1_200] {
            p.on_frame(&frame(1, false, 0), -48, t);
            p.on_frame(&frame(2, false, 0), -70, t);
        }
        p.on_frame(&frame(1, true, 1), -48, 1_300);
        p.on_frame(&frame(2, true, 1), -70, 1_300);
        p.on_own_gesture(1_400);
        assert!(matches!(p.poll(1_500), Outcome::Paired(v) if v.id == "0101010101010101"));
    }

    #[test]
    fn two_equally_close_phones_are_ambiguous() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -55, 1_000);
        p.on_frame(&frame(2, false, 0), -57, 1_000);
        p.on_frame(&frame(1, true, 1), -55, 1_300);
        p.on_frame(&frame(2, true, 1), -57, 1_300);
        p.on_own_gesture(1_400);
        assert!(matches!(p.poll(1_500), Outcome::Ambiguous));
    }

    #[test]
    fn a_phone_that_went_away_is_forgotten() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, true, 1), -50, 1_000);
        assert_eq!(p.peers(2_000).len(), 1);
        assert!(p.peers(9_000).is_empty());
    }
}
