//! The pairing effect, drawn from a snapshot of the window.
//!
//! Slint cannot run a custom shader over its own screen, so the effect takes a snapshot of the window when it
//! starts, runs the same maths as the phone's shader (`AirDropShader.kt`) on it, one frame after another, and shows
//! the result over the window. The window does not change during the effect, so the snapshot is enough.
//!
//! The work is done at half the size of the window: the effect is soft by nature, and the frame must be ready in
//! a few milliseconds. The blurred copy of the snapshot is made once, because only the mix between the sharp and
//! the blurred copy changes from frame to frame.

use crate::airdrop::Frame;

/// The effect works at this fraction of the window size.
const SCALE: usize = 2;

/// One picture, three floats from 0 to 1 for each pixel.
struct Picture {
    width: usize,
    height: usize,
    pixels: Vec<[f32; 3]>,
}

impl Picture {
    /// Reads the picture at a point given from 0 to 1, y upward. Outside the picture the nearest edge is read.
    fn sample(&self, u: f32, v: f32) -> [f32; 3] {
        let x = (u.clamp(0.0, 1.0) * self.width as f32 - 0.5).clamp(0.0, (self.width - 1) as f32);
        let y = ((1.0 - v).clamp(0.0, 1.0) * self.height as f32 - 0.5)
            .clamp(0.0, (self.height - 1) as f32);
        let (x0, y0) = (x.floor() as usize, y.floor() as usize);
        let (x1, y1) = ((x0 + 1).min(self.width - 1), (y0 + 1).min(self.height - 1));
        let (fx, fy) = (x - x0 as f32, y - y0 as f32);
        let at = |px: usize, py: usize| self.pixels[py * self.width + px];
        let mut out = [0.0; 3];
        for (channel, value) in out.iter_mut().enumerate() {
            let top = at(x0, y0)[channel] * (1.0 - fx) + at(x1, y0)[channel] * fx;
            let bottom = at(x0, y1)[channel] * (1.0 - fx) + at(x1, y1)[channel] * fx;
            *value = top * (1.0 - fy) + bottom * fy;
        }
        out
    }

    /// A gaussian blur, done as a horizontal pass then a vertical one.
    fn blurred(&self, sigma: f32) -> Picture {
        let radius = (sigma * 3.0).ceil().max(1.0) as isize;
        let weights: Vec<f32> = (-radius..=radius)
            .map(|i| (-0.5 * (i * i) as f32 / (sigma * sigma)).exp())
            .collect();
        let total: f32 = weights.iter().sum();
        let pass = |source: &Picture, horizontal: bool| {
            let mut pixels = vec![[0.0f32; 3]; source.pixels.len()];
            for y in 0..source.height {
                for x in 0..source.width {
                    let mut sum = [0.0f32; 3];
                    for (k, weight) in weights.iter().enumerate() {
                        let offset = k as isize - radius;
                        let (px, py) = if horizontal {
                            (
                                (x as isize + offset).clamp(0, source.width as isize - 1) as usize,
                                y,
                            )
                        } else {
                            (
                                x,
                                (y as isize + offset).clamp(0, source.height as isize - 1) as usize,
                            )
                        };
                        let pixel = source.pixels[py * source.width + px];
                        for channel in 0..3 {
                            sum[channel] += pixel[channel] * weight;
                        }
                    }
                    pixels[y * source.width + x] = [sum[0] / total, sum[1] / total, sum[2] / total];
                }
            }
            Picture {
                width: source.width,
                height: source.height,
                pixels,
            }
        };
        pass(&pass(self, true), false)
    }
}

/// The snapshot, ready to be drawn with the effect at any moment of its timeline.
pub struct Effect {
    sharp: Picture,
    soft: Picture,
    /// Blurred much more than `soft`, for the background of the spotlight.
    deep: Picture,
}

impl Effect {
    /// `rgba` is the snapshot, four bytes per pixel, `width` by `height`.
    pub fn new(rgba: &[u8], width: usize, height: usize) -> Self {
        let (w, h) = ((width / SCALE).max(1), (height / SCALE).max(1));
        let mut pixels = Vec::with_capacity(w * h);
        for y in 0..h {
            for x in 0..w {
                // The average of the block of window pixels that this pixel stands for.
                let mut sum = [0.0f32; 3];
                let mut count = 0.0;
                for dy in 0..SCALE {
                    for dx in 0..SCALE {
                        let (sx, sy) = (
                            (x * SCALE + dx).min(width - 1),
                            (y * SCALE + dy).min(height - 1),
                        );
                        let index = (sy * width + sx) * 4;
                        for channel in 0..3 {
                            sum[channel] += f32::from(rgba[index + channel]) / 255.0;
                        }
                        count += 1.0;
                    }
                }
                pixels.push([sum[0] / count, sum[1] / count, sum[2] / count]);
            }
        }
        let sharp = Picture {
            width: w,
            height: h,
            pixels,
        };
        // About 20 pixels on a 1080 pixel wide window, the same as the phone.
        let sigma = 20.0 * w as f32 / 1080.0 + 1.0;
        let soft = sharp.blurred(sigma);
        let deep = sharp.blurred(sigma * 2.5);
        Effect { sharp, soft, deep }
    }

    /// The size of the frames that [`Effect::render`] makes.
    pub fn size(&self) -> (usize, usize) {
        (self.sharp.width, self.sharp.height)
    }

    /// The snapshot blurred by `amount`, from 0 to 1, and dimmed a little: the background of the spotlight, where
    /// the file is all that stays sharp. `out` has the size given by [`Effect::size`].
    pub fn frosted(&self, amount: f32, out: &mut [u8]) {
        let amount = amount.clamp(0.0, 1.0);
        let dim = 1.0 - 0.22 * amount;
        for (index, (sharp, deep)) in self.sharp.pixels.iter().zip(&self.deep.pixels).enumerate() {
            for channel in 0..3 {
                let mixed = (sharp[channel] + (deep[channel] - sharp[channel]) * amount) * dim;
                out[index * 4 + channel] = (mixed.clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
            }
            out[index * 4 + 3] = 255;
        }
    }

    /// Draws one frame into `out`, which holds four bytes per pixel and has the size given by [`Effect::size`].
    pub fn render(&self, frame: &Frame, out: &mut [u8]) {
        let (w, h) = self.size();
        let aspect = w as f32 / h as f32;
        let farthest = (0.25 * aspect * aspect + 1.0).sqrt();
        let front = frame.wave * (farthest + 0.45);
        let glow_y = -0.12 + 1.08 * frame.rise;

        for y in 0..h {
            for x in 0..w {
                let sx = (x as f32 + 0.5) / w as f32;
                let sy = 1.0 - (y as f32 + 0.5) / h as f32;
                let px = (sx - 0.5) * aspect;

                // The wave starts at the top middle. 1 ahead of its front, 0 behind it, where the screen is clean.
                let d = px.hypot(sy - 1.0);
                let ahead = smooth_step(front - 0.30, front, d);

                // Before the strike the picture bends toward the glow.
                let bend = (-sq((sy - glow_y) / 0.30)).exp();
                let mut v = sy + (glow_y - sy) * 0.10 * bend * frame.energy * ahead;
                let mut u = sx;

                // At the strike the picture is pushed outward from the notch, in a band just behind the front.
                let ring = if frame.wave > 0.0 {
                    (-sq((d - (front - 0.10)) / 0.11)).exp() * (1.0 - frame.wave * 0.3)
                } else {
                    0.0
                };
                let (ox, oy) = (px / d.max(0.0001), (sy - 1.0) / d.max(0.0001));
                u -= ox * 0.05 * ring / aspect;
                v -= oy * 0.05 * ring;

                let sharp = self.sharp.sample(u, v);
                let soft = self.soft.sample(u, v);
                let blur = frame.energy * (0.35 + 0.65 * smooth_step(0.0, 1.0, sy)) * ahead;

                let side = smooth_step(-0.4, 0.4, px);
                let glow = [1.0 - 0.55 * side, 0.45 + 0.20 * side, 0.70 + 0.30 * side];
                let body = (-(sq(px / 0.70) + sq((sy - glow_y) / 0.45))).exp();
                let crest = (-sq((sy - (glow_y + 0.20)) / 0.05)).exp()
                    * (-sq(px / 0.8)).exp()
                    * 0.30
                    * (1.0 - frame.accumulate)
                    * frame.energy;
                let notch = (-(sq(px / 0.40) + sq((sy - 1.0) / 0.14))).exp() * frame.accumulate;
                let light = ((body * frame.energy * 0.95 + crest + notch * 1.2) * ahead)
                    .clamp(0.0, 1.0)
                    * 0.85;

                let index = (y * w + x) * 4;
                for channel in 0..3 {
                    let mixed = sharp[channel] + (soft[channel] - sharp[channel]) * blur;
                    let lit = mixed + (glow[channel] - mixed) * light + ring * 0.18;
                    out[index + channel] = (lit.clamp(0.0, 1.0) * 255.0 + 0.5) as u8;
                }
                out[index + 3] = 255;
            }
        }
    }
}

fn sq(x: f32) -> f32 {
    x * x
}

fn smooth_step(from: f32, to: f32, x: f32) -> f32 {
    let t = ((x - from) / (to - from)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::airdrop;

    /// A picture with a bright left half and a dark right half, so blur and distortion change it.
    fn snapshot(width: usize, height: usize) -> Vec<u8> {
        let mut rgba = Vec::new();
        for _y in 0..height {
            for x in 0..width {
                let value = if x < width / 2 { 235 } else { 40 };
                rgba.extend_from_slice(&[value, value, value, 255]);
            }
        }
        rgba
    }

    fn frame_at(effect: &Effect, ms: f32) -> Vec<u8> {
        let (w, h) = effect.size();
        let mut out = vec![0u8; w * h * 4];
        effect.render(&airdrop::frame(ms), &mut out);
        out
    }

    #[test]
    fn the_first_frame_is_the_snapshot() {
        let effect = Effect::new(&snapshot(120, 240), 120, 240);
        let first = frame_at(&effect, 0.0);
        let (w, _) = effect.size();
        assert_eq!(first[(10 * w + 5) * 4], 235);
        assert_eq!(first[(10 * w + w - 5) * 4], 40);
    }

    #[test]
    fn the_last_frame_is_the_snapshot_again() {
        let effect = Effect::new(&snapshot(120, 240), 120, 240);
        let last = frame_at(&effect, airdrop::TOTAL_MS);
        let (w, h) = effect.size();
        for y in [0, h / 2, h - 1] {
            assert_eq!(last[(y * w + 5) * 4], 235, "left side at row {y}");
            assert_eq!(last[(y * w + w - 5) * 4], 40, "right side at row {y}");
        }
    }

    #[test]
    fn the_effect_changes_the_picture_in_between() {
        let effect = Effect::new(&snapshot(120, 240), 120, 240);
        let first = frame_at(&effect, 0.0);
        let middle = frame_at(&effect, airdrop::RISE_MS);
        assert_ne!(first, middle);
    }

    #[test]
    fn frosting_by_nothing_is_the_snapshot_and_frosting_fully_blurs_it() {
        let effect = Effect::new(&snapshot(120, 240), 120, 240);
        let (w, h) = effect.size();
        let mut clear = vec![0u8; w * h * 4];
        effect.frosted(0.0, &mut clear);
        assert_eq!(clear[(10 * w + 5) * 4], 235);
        let mut blurred = vec![0u8; w * h * 4];
        effect.frosted(1.0, &mut blurred);
        // Next to the edge between the bright and the dark half, the blur has mixed the two.
        let edge = (10 * w + w / 2 - 1) * 4;
        assert!(blurred[edge] < 235 && blurred[edge] > 40);
    }

    #[test]
    fn a_frame_has_the_size_of_the_snapshot_halved() {
        let effect = Effect::new(&snapshot(120, 240), 120, 240);
        assert_eq!(effect.size(), (60, 120));
    }
}
