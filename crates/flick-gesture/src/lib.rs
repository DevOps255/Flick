//! Detects the Flick gesture from raw accelerometer samples.
//!
//! A Flick is a short, sharp push of the phone. Gravity is estimated with a
//! low pass filter and removed, so tilting the phone slowly never counts.
//! A Flick is reported when the remaining acceleration crosses a threshold
//! and settles again within [`Config::max_burst_ms`].
//!
//! The detector is platform independent: Android and desktop feed it samples
//! and read back the result. It does no I/O and keeps no clock of its own.

/// One accelerometer reading, in m/s² including gravity.
#[derive(Clone, Copy, Debug)]
pub struct Sample {
    /// Monotonic timestamp in milliseconds.
    pub t_ms: u64,
    /// Acceleration along x, y and z.
    pub accel: [f32; 3],
}

/// Tuning values. The defaults suit a 50 Hz sensor and need field testing.
#[derive(Clone, Copy, Debug)]
pub struct Config {
    /// Weight of the previous gravity estimate. Closer to 1 adapts more slowly.
    pub gravity_alpha: f32,
    /// Acceleration without gravity, in m/s², that starts a burst.
    pub peak_threshold: f32,
    /// A burst longer than this is shaking or walking, not a Flick.
    pub max_burst_ms: u64,
    /// Minimum time between two reported Flicks.
    pub cooldown_ms: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self { gravity_alpha: 0.9, peak_threshold: 14.0, max_burst_ms: 250, cooldown_ms: 800 }
    }
}

/// A detected Flick.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Flick {
    /// Time the burst settled.
    pub t_ms: u64,
    /// Strongest acceleration of the burst, without gravity.
    pub peak: f32,
    /// Unit vector of the strongest sample, the direction of the push.
    pub direction: [f32; 3],
}

/// Turns a stream of [`Sample`]s into [`Flick`]s.
pub struct Detector {
    cfg: Config,
    gravity: Option<[f32; 3]>,
    burst_start: Option<u64>,
    /// False after an over long burst, until the signal calms down.
    armed: bool,
    peak: f32,
    direction: [f32; 3],
    last_fired: Option<u64>,
}

impl Detector {
    /// Creates a detector with the given tuning.
    pub fn new(cfg: Config) -> Self {
        Self {
            cfg,
            gravity: None,
            burst_start: None,
            armed: true,
            peak: 0.0,
            direction: [0.0; 3],
            last_fired: None,
        }
    }

    /// Feeds one sample. Returns a [`Flick`] when the gesture just completed.
    pub fn push(&mut self, s: Sample) -> Option<Flick> {
        let g = self.gravity.get_or_insert(s.accel);
        let a = self.cfg.gravity_alpha;
        for i in 0..3 {
            g[i] = a * g[i] + (1.0 - a) * s.accel[i];
        }
        let lin = [s.accel[0] - g[0], s.accel[1] - g[1], s.accel[2] - g[2]];
        let mag = (lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2]).sqrt();
        let calm = mag < self.cfg.peak_threshold / 2.0;

        if !self.armed {
            self.armed = calm;
            return None;
        }

        match self.burst_start {
            None if mag >= self.cfg.peak_threshold => {
                self.burst_start = Some(s.t_ms);
                self.record_peak(mag, lin);
                None
            }
            None => None,
            Some(start) if s.t_ms - start > self.cfg.max_burst_ms => {
                self.burst_start = None;
                self.armed = false;
                None
            }
            Some(_) if !calm => {
                self.record_peak(mag, lin);
                None
            }
            Some(_) => self.finish(s.t_ms),
        }
    }

    fn record_peak(&mut self, mag: f32, lin: [f32; 3]) {
        if mag > self.peak {
            self.peak = mag;
            self.direction = [lin[0] / mag, lin[1] / mag, lin[2] / mag];
        }
    }

    fn finish(&mut self, t_ms: u64) -> Option<Flick> {
        let flick = Flick { t_ms, peak: self.peak, direction: self.direction };
        self.burst_start = None;
        self.peak = 0.0;
        let cooled = self.last_fired.map_or(true, |t| t_ms - t >= self.cfg.cooldown_ms);
        if cooled {
            self.last_fired = Some(t_ms);
            Some(flick)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const STEP_MS: u64 = 20;

    /// Feeds `accel` readings back to back and collects every Flick.
    fn run(det: &mut Detector, start_ms: u64, readings: &[[f32; 3]]) -> Vec<Flick> {
        readings
            .iter()
            .enumerate()
            .filter_map(|(i, &accel)| det.push(Sample { t_ms: start_ms + i as u64 * STEP_MS, accel }))
            .collect()
    }

    fn rest(n: usize) -> Vec<[f32; 3]> {
        vec![[0.0, 0.0, 9.81]; n]
    }

    fn push_x(n: usize) -> Vec<[f32; 3]> {
        vec![[22.0, 0.0, 9.81]; n]
    }

    #[test]
    fn phone_at_rest_reports_nothing() {
        let mut det = Detector::new(Config::default());
        assert!(run(&mut det, 0, &rest(200)).is_empty());
    }

    #[test]
    fn short_push_is_one_flick_along_x() {
        let mut det = Detector::new(Config::default());
        let mut readings = rest(50);
        readings.extend(push_x(3));
        readings.extend(rest(20));
        let flicks = run(&mut det, 0, &readings);
        assert_eq!(flicks.len(), 1);
        assert!(flicks[0].direction[0] > 0.9);
        assert!(flicks[0].peak > 14.0);
    }

    #[test]
    fn slow_tilt_is_ignored() {
        let mut det = Detector::new(Config::default());
        let ramp: Vec<[f32; 3]> = (0..100).map(|i| [i as f32 * 0.2, 0.0, 9.81]).collect();
        assert!(run(&mut det, 0, &ramp).is_empty());
    }

    #[test]
    fn two_pushes_inside_cooldown_count_once() {
        let mut det = Detector::new(Config::default());
        let mut readings = rest(50);
        for _ in 0..2 {
            readings.extend(push_x(3));
            readings.extend(rest(8));
        }
        readings.extend(rest(30));
        assert_eq!(run(&mut det, 0, &readings).len(), 1);
    }

    #[test]
    fn long_shaking_is_ignored() {
        let mut det = Detector::new(Config::default());
        let mut readings = rest(50);
        for i in 0..40 {
            let sign = if i % 2 == 0 { 25.0 } else { -25.0 };
            readings.push([sign, 0.0, 9.81]);
        }
        readings.extend(rest(30));
        assert!(run(&mut det, 0, &readings).is_empty());
    }
}
