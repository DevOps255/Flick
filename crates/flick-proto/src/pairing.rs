//! Decides which nearby phone the user means.
//!
//! Phones advertise over Bluetooth all the time. Pairing needs two things at once: the user made
//! the pairing gesture here, and that phone made it too, within a couple of seconds. Of the phones
//! that did, the closest one wins. If two are about as close, the answer is ambiguous and nothing
//! is paired, rather than pairing the wrong phone.
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
/// A gesture on one phone counts for the other if they happen within this many milliseconds.
const GESTURE_WINDOW_MS: u64 = 2_500;
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

    /// Checks whether the user paired with a phone. A pairing is reported once.
    pub fn poll(&mut self, now_ms: u64) -> Outcome {
        let Some(own) = self.own_gesture_ms else {
            return Outcome::Pending;
        };
        if now_ms.saturating_sub(own) > GESTURE_WINDOW_MS {
            self.own_gesture_ms = None;
            return Outcome::Pending;
        }
        let mut candidates: Vec<PeerView> = self
            .peers
            .iter()
            .filter(|(_, p)| now_ms.saturating_sub(p.last_seen_ms) <= SEEN_WITHIN_MS)
            .filter(|(_, p)| {
                p.gesture_seen_ms
                    .is_some_and(|g| g.abs_diff(own) <= GESTURE_WINDOW_MS)
            })
            .map(|(key, p)| view(key, p))
            .collect();
        candidates.sort_by(|a, b| b.rssi_dbm.total_cmp(&a.rssi_dbm));
        match candidates.as_slice() {
            [] => Outcome::Pending,
            [only] => {
                self.own_gesture_ms = None;
                Outcome::Paired(only.clone())
            }
            [best, second, ..] if best.rssi_dbm - second.rssi_dbm >= MARGIN_DB => {
                self.own_gesture_ms = None;
                Outcome::Paired(best.clone())
            }
            _ => Outcome::Ambiguous,
        }
    }
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
    fn both_gestures_pair_the_phones() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, false, 0), -50, 1_000);
        p.on_own_gesture(2_000);
        assert!(
            matches!(p.poll(2_100), Outcome::Pending),
            "the other phone has not gestured"
        );
        p.on_frame(&frame(1, true, 1), -50, 2_300);
        assert!(matches!(p.poll(2_400), Outcome::Paired(v) if v.id == "0101010101010101"));
        assert!(matches!(p.poll(2_500), Outcome::Pending), "reported once");
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
    fn a_gesture_far_in_the_past_does_not_count() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, true, 1), -50, 1_000);
        p.on_own_gesture(10_000);
        p.on_frame(&frame(1, false, 1), -50, 10_100);
        assert!(matches!(p.poll(10_200), Outcome::Pending));
    }

    #[test]
    fn a_flag_that_stays_up_is_one_gesture_not_many() {
        let mut p = Pairing::new();
        p.on_frame(&frame(1, true, 3), -50, 1_000);
        p.on_frame(&frame(1, true, 3), -50, 5_000);
        p.on_own_gesture(5_100);
        assert!(
            matches!(p.poll(5_200), Outcome::Pending),
            "the gesture was at 1 s, too long ago"
        );
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
