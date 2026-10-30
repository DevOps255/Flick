//! Detects the pairing gesture: tilting the phone, as when bringing two iPhones together for AirDrop.
//!
//! The gyroscope reports how fast the phone turns. Adding that up over the last moments gives how far
//! it turned. A deliberate tilt turns the phone clearly in one direction. Walking, shaking and hand
//! tremor turn it back and forth, so the total stays small and they are ignored.

use std::collections::VecDeque;

/// One gyroscope reading.
#[derive(Clone, Copy, Debug)]
pub struct GyroSample {
    /// Monotonic timestamp in milliseconds.
    pub t_ms: u64,
    /// Angular speed around x, y and z, in rad/s.
    pub rate: [f32; 3],
}

/// Tuning values. The defaults need field testing on real phones.
#[derive(Clone, Copy, Debug)]
pub struct TiltConfig {
    /// How far back the turn is added up, in milliseconds.
    pub window_ms: u64,
    /// Smallest net turn, in radians, that counts. 0.45 rad is about 26 degrees.
    pub min_angle: f32,
    /// The turn must reach at least this speed, in rad/s. Slow drifting is not a gesture.
    pub min_peak_rate: f32,
    /// The other two axes may turn at most this share of the main one.
    pub max_off_axis: f32,
    /// Minimum time between two tilts.
    pub cooldown_ms: u64,
}

impl Default for TiltConfig {
    fn default() -> Self {
        Self {
            window_ms: 700,
            min_angle: 0.45,
            min_peak_rate: 1.0,
            max_off_axis: 0.7,
            cooldown_ms: 1_500,
        }
    }
}

/// A detected tilt.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Tilt {
    pub t_ms: u64,
    /// Net turn in radians, always positive.
    pub angle: f32,
    /// Index of the axis the phone turned around: 0 x, 1 y, 2 z.
    pub axis: usize,
}

/// Turns a stream of [`GyroSample`]s into [`Tilt`]s.
pub struct TiltDetector {
    cfg: TiltConfig,
    window: VecDeque<GyroSample>,
    last_fired: Option<u64>,
}

impl TiltDetector {
    pub fn new(cfg: TiltConfig) -> Self {
        Self {
            cfg,
            window: VecDeque::new(),
            last_fired: None,
        }
    }

    /// Feeds one reading. Returns a [`Tilt`] when the gesture just completed.
    pub fn push(&mut self, sample: GyroSample) -> Option<Tilt> {
        self.window.push_back(sample);
        while self
            .window
            .front()
            .is_some_and(|s| sample.t_ms - s.t_ms > self.cfg.window_ms)
        {
            self.window.pop_front();
        }
        if self
            .last_fired
            .is_some_and(|t| sample.t_ms - t < self.cfg.cooldown_ms)
        {
            return None;
        }
        let mut net = [0f32; 3];
        let mut peak = 0f32;
        for pair in self.window.iter().zip(self.window.iter().skip(1)) {
            let dt = (pair.1.t_ms - pair.0.t_ms) as f32 / 1000.0;
            for (axis, total) in net.iter_mut().enumerate() {
                *total += pair.1.rate[axis] * dt;
            }
        }
        for s in &self.window {
            peak = peak.max((s.rate[0].powi(2) + s.rate[1].powi(2) + s.rate[2].powi(2)).sqrt());
        }
        let (axis, main) = net
            .iter()
            .map(|v| v.abs())
            .enumerate()
            .fold(
                (0, 0f32),
                |best, cur| if cur.1 > best.1 { cur } else { best },
            );
        let off_axis = net
            .iter()
            .enumerate()
            .filter(|(i, _)| *i != axis)
            .map(|(_, v)| v.abs())
            .fold(0f32, f32::max);
        if main >= self.cfg.min_angle
            && peak >= self.cfg.min_peak_rate
            && off_axis <= main * self.cfg.max_off_axis
        {
            self.last_fired = Some(sample.t_ms);
            self.window.clear();
            return Some(Tilt {
                t_ms: sample.t_ms,
                angle: main,
                axis,
            });
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const STEP: u64 = 20;

    fn run(det: &mut TiltDetector, from_ms: u64, rates: &[[f32; 3]]) -> Vec<Tilt> {
        rates
            .iter()
            .enumerate()
            .filter_map(|(i, &rate)| {
                det.push(GyroSample {
                    t_ms: from_ms + i as u64 * STEP,
                    rate,
                })
            })
            .collect()
    }

    fn rest(n: usize) -> Vec<[f32; 3]> {
        vec![[0.0; 3]; n]
    }

    #[test]
    fn a_phone_held_still_reports_nothing() {
        let mut d = TiltDetector::new(TiltConfig::default());
        assert!(run(&mut d, 0, &rest(300)).is_empty());
    }

    #[test]
    fn a_deliberate_tilt_is_detected_once() {
        let mut d = TiltDetector::new(TiltConfig::default());
        let mut rates = rest(30);
        rates.extend(vec![[2.0, 0.1, 0.0]; 15]); // 2 rad/s for 0.3 s turns the phone about 0.6 rad
        rates.extend(rest(40));
        let tilts = run(&mut d, 0, &rates);
        assert_eq!(tilts.len(), 1);
        assert_eq!(tilts[0].axis, 0);
        assert!(tilts[0].angle >= 0.45);
    }

    #[test]
    fn slow_drifting_is_ignored() {
        let mut d = TiltDetector::new(TiltConfig::default());
        assert!(run(&mut d, 0, &vec![[0.2, 0.0, 0.0]; 400]).is_empty());
    }

    #[test]
    fn walking_and_shaking_turn_back_and_forth_and_are_ignored() {
        let mut d = TiltDetector::new(TiltConfig::default());
        let walking: Vec<[f32; 3]> = (0..400)
            .map(|i| {
                [
                    0.8 * (i as f32 * 0.02 * 2.0 * std::f32::consts::TAU).sin(),
                    0.0,
                    0.0,
                ]
            })
            .collect();
        assert!(run(&mut d, 0, &walking).is_empty());
        let shaking: Vec<[f32; 3]> = (0..400)
            .map(|i| {
                [
                    5.0 * (i as f32 * 0.02 * 5.0 * std::f32::consts::TAU).sin(),
                    0.0,
                    0.0,
                ]
            })
            .collect();
        assert!(run(&mut d, 10_000, &shaking).is_empty());
    }

    #[test]
    fn spinning_around_several_axes_at_once_is_ignored() {
        let mut d = TiltDetector::new(TiltConfig::default());
        let tumble = vec![[1.5, 1.5, 1.5]; 25];
        assert!(run(&mut d, 0, &tumble).is_empty());
    }

    #[test]
    fn two_tilts_inside_the_cooldown_count_once() {
        let mut d = TiltDetector::new(TiltConfig::default());
        let mut rates = rest(10);
        for _ in 0..2 {
            rates.extend(vec![[2.0, 0.0, 0.0]; 15]);
            rates.extend(rest(10));
        }
        rates.extend(rest(40));
        assert_eq!(run(&mut d, 0, &rates).len(), 1);
    }
}
