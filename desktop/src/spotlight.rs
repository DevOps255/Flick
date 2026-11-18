//! The spotlight: the chosen file shown large, with everything else blurred, like the pause menu of a game.
//!
//! Three moves, each a pose of the file card over time:
//! - **appear**: the card grows into the middle while the window behind it blurs.
//! - **grab**: the hand closes, the card shrinks and slides away toward the bottom, like a window closing on a Mac,
//!   and the blur clears as it leaves.
//! - **release**: the hand opens on the other PC, and the card comes back the same way in reverse.
//!
//! This file only holds the timing, so it can be tested. The picture of the card and the blur are drawn elsewhere.

pub const APPEAR_MS: f32 = 350.0;
pub const GRAB_MS: f32 = 450.0;
pub const RELEASE_MS: f32 = 450.0;

/// Where the card is, as fractions: `scale` of its resting size, offsets as a fraction of the window size, and the
/// blur of the background from 0 to 1.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Pose {
    pub scale: f32,
    pub dx: f32,
    pub dy: f32,
    pub opacity: f32,
    pub blur: f32,
}

/// The card at rest, in the middle, with the background fully blurred.
#[cfg(test)]
pub const REST: Pose = Pose {
    scale: 1.0,
    dx: 0.0,
    dy: 0.0,
    opacity: 1.0,
    blur: 1.0,
};

/// The size of the card when it is a dot at the bottom, and how far down it goes.
const SMALL: f32 = 0.08;
const SLIDE_DOWN: f32 = 0.55;

fn ease_out(t: f32) -> f32 {
    1.0 - (1.0 - t).powi(3)
}

fn ease_in(t: f32) -> f32 {
    t.powi(3)
}

fn progress(ms: f32, total: f32) -> f32 {
    (ms / total).clamp(0.0, 1.0)
}

fn lerp(from: f32, to: f32, t: f32) -> f32 {
    from + (to - from) * t
}

/// The card growing in from the middle of the window.
pub fn appear(ms: f32) -> Pose {
    let t = ease_out(progress(ms, APPEAR_MS));
    Pose {
        scale: lerp(0.82, 1.0, t),
        dx: 0.0,
        dy: 0.0,
        opacity: t,
        blur: t,
    }
}

/// The card leaving, shrinking toward the bottom. It speeds up as it goes, so it is pulled away.
pub fn grab(ms: f32) -> Pose {
    let t = ease_in(progress(ms, GRAB_MS));
    Pose {
        scale: lerp(1.0, SMALL, t),
        dx: 0.0,
        dy: lerp(0.0, SLIDE_DOWN, t),
        // It stays whole most of the way and fades only at the very end.
        opacity: 1.0 - progress(ms, GRAB_MS).powi(6),
        blur: lerp(1.0, 0.0, t),
    }
}

/// The card coming back, from a dot at the bottom into the middle. It is the grab, played backwards, slowing as it arrives.
pub fn release(ms: f32) -> Pose {
    let t = ease_out(progress(ms, RELEASE_MS));
    Pose {
        scale: lerp(SMALL, 1.0, t),
        dx: 0.0,
        dy: lerp(SLIDE_DOWN, 0.0, t),
        // It is whole from early on, the grab played backwards.
        opacity: 1.0 - (1.0 - progress(ms, RELEASE_MS)).powi(6),
        blur: t,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn appearing_ends_at_rest() {
        assert_eq!(appear(APPEAR_MS), REST);
        assert_eq!(appear(0.0).opacity, 0.0);
    }

    #[test]
    fn grabbing_starts_at_rest_and_ends_gone() {
        assert_eq!(grab(0.0), REST);
        let end = grab(GRAB_MS);
        assert!(end.opacity < 0.01 && end.scale < 0.1 && end.blur < 0.01);
        assert!(end.dy > 0.5, "the card ends low in the window");
    }

    #[test]
    fn releasing_ends_at_rest_and_starts_where_grabbing_ended() {
        assert_eq!(release(RELEASE_MS), REST);
        let start = release(0.0);
        let gone = grab(GRAB_MS);
        assert!((start.scale - gone.scale).abs() < 1e-5);
        assert!((start.dy - gone.dy).abs() < 1e-5);
        assert!((start.blur - gone.blur).abs() < 1e-5);
        assert!((start.opacity - gone.opacity).abs() < 1e-5);
    }

    #[test]
    fn the_card_is_pulled_away_faster_and_faster() {
        let first_half = grab(GRAB_MS / 2.0).dy - grab(0.0).dy;
        let second_half = grab(GRAB_MS).dy - grab(GRAB_MS / 2.0).dy;
        assert!(second_half > first_half * 3.0);
    }

    #[test]
    fn the_card_slows_down_as_it_comes_back() {
        let first_half = release(RELEASE_MS / 2.0).dy - release(0.0).dy;
        let second_half = release(RELEASE_MS).dy - release(RELEASE_MS / 2.0).dy;
        assert!(first_half.abs() > second_half.abs() * 3.0);
    }
}
