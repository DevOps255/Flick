//! The timeline of the pairing effect. It is the same as on Android (`AirDropEffect.kt`): the glow climbs,
//! gathers at the top, then a wave strikes down. Everything only grows, so nothing ever moves back.

/// The glow climbs.
pub const RISE_MS: f32 = 1_400.0;
/// The glow gathers at the top and gets brighter.
pub const ACCUMULATE_MS: f32 = 600.0;
/// The wave strikes down from the top and wipes the effect away behind it.
pub const STRIKE_MS: f32 = 450.0;
pub const TOTAL_MS: f32 = RISE_MS + ACCUMULATE_MS + STRIKE_MS;

/// The four numbers that describe the effect at one instant, each from 0 to 1.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Frame {
    /// How high the glow has climbed.
    pub rise: f32,
    /// How bright and how blurred the screen is.
    pub energy: f32,
    /// How much the top of the screen is lit.
    pub accumulate: f32,
    /// How far the wave front has gone, from the top to past the bottom.
    pub wave: f32,
}

fn smooth_step(from: f32, to: f32, x: f32) -> f32 {
    let t = ((x - from) / (to - from)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// The effect after `elapsed_ms` milliseconds.
pub fn frame(elapsed_ms: f32) -> Frame {
    let ms = elapsed_ms.clamp(0.0, TOTAL_MS);
    let strike = ((ms - (RISE_MS + ACCUMULATE_MS)) / STRIKE_MS).clamp(0.0, 1.0);
    let climb = (ms / RISE_MS).clamp(0.0, 1.0);
    Frame {
        // The glow moves at a steady pace and slows only when it reaches the top, so the climb is easy to follow.
        rise: 1.0 - (1.0 - climb).powf(1.6),
        energy: smooth_step(0.0, RISE_MS + ACCUMULATE_MS, ms).powf(0.75),
        accumulate: smooth_step(RISE_MS * 0.6, RISE_MS + ACCUMULATE_MS, ms),
        wave: strike.powf(2.2),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_clean_and_ends_with_the_wave_gone() {
        let first = frame(0.0);
        assert_eq!((first.rise, first.energy, first.wave), (0.0, 0.0, 0.0));
        let last = frame(TOTAL_MS);
        assert_eq!(
            (last.rise, last.energy, last.accumulate, last.wave),
            (1.0, 1.0, 1.0, 1.0)
        );
    }

    #[test]
    fn nothing_ever_moves_back() {
        let mut before = frame(0.0);
        for step in 1..=500 {
            let now = frame(step as f32 / 500.0 * TOTAL_MS);
            assert!(now.rise >= before.rise && now.energy >= before.energy);
            assert!(now.accumulate >= before.accumulate && now.wave >= before.wave);
            before = now;
        }
    }

    #[test]
    fn the_wave_waits_for_the_glow_to_gather() {
        let strike_start = RISE_MS + ACCUMULATE_MS;
        assert_eq!(frame(strike_start - 1.0).wave, 0.0);
        assert_eq!(frame(strike_start).rise, 1.0);
        assert_eq!(frame(strike_start).accumulate, 1.0);
    }

    #[test]
    fn the_wave_speeds_up_as_it_falls() {
        let half = frame(RISE_MS + ACCUMULATE_MS + STRIKE_MS / 2.0).wave;
        assert!(half < 0.3, "wave was {half} at half the strike time");
    }
}
