//! Sends and receives short messages as ultrasound, built on ggwave.
//!
//! This crate only turns bytes into audio samples and back. Playing and
//! recording audio is the platform's job: Android feeds microphone samples to a
//! [`Decoder`] and plays the samples returned by [`encode`].

use std::os::raw::c_void;

/// Sample rate of every waveform produced and accepted here, in Hz.
pub const SAMPLE_RATE: u32 = 48_000;

/// ggwave protocol used by [`encode`]: ultrasound fast.
///
/// The faster protocol (5) ends messages early for some payloads, depending on where the sound
/// falls in the stream: 25 of 40 alignments were wrong in testing. Protocol 4 got 40 of 40.
pub const PROTOCOL: i32 = 4;

/// ggwave protocol that is slower and easier to decode: ultrasound normal, 2.4 s for 18 bytes.
/// It was as reliable as [`PROTOCOL`] in testing. Alternating the two helps phones with a weak microphone.
pub const ROBUST_PROTOCOL: i32 = 3;

/// Output level, 0 to 100. The peak sample is `VOLUME / 100`, so 80 peaks at 0.80 without clipping.
/// The first version used 25, a quarter of full scale, which a weak microphone could not hear
/// because phone speakers lose much of their power between 15 and 19 kHz.
pub const VOLUME: i32 = 80;

/// Largest message the decoder returns, in bytes.
pub const MAX_MESSAGE_LEN: usize = 64;

extern "C" {
    fn fa_encode(
        payload: *const u8,
        len: i32,
        out: *mut f32,
        cap: i32,
        protocol: i32,
        volume: i32,
    ) -> i32;
    fn fa_decoder_new() -> *mut c_void;
    fn fa_decoder_push(
        handle: *mut c_void,
        samples: *const f32,
        n: i32,
        out: *mut u8,
        cap: i32,
    ) -> i32;
    fn fa_decoder_free(handle: *mut c_void);
}

/// Why encoding or decoder creation failed.
#[derive(Debug, PartialEq, Eq)]
pub enum AcousticError {
    /// ggwave refused the payload, usually because it is empty or too long.
    Encode,
    /// The decoder could not be created.
    Decoder,
}

/// Turns `payload` into mono f32 samples at [`SAMPLE_RATE`], ready to play.
pub fn encode(payload: &[u8]) -> Result<Vec<f32>, AcousticError> {
    encode_with(payload, PROTOCOL)
}

/// Same as [`encode`] with an explicit ggwave ultrasound protocol id (3 normal, 4 fast, 5 fastest).
pub fn encode_with(payload: &[u8], protocol: i32) -> Result<Vec<f32>, AcousticError> {
    let len = i32::try_from(payload.len()).map_err(|_| AcousticError::Encode)?;
    // SAFETY: a null output asks only for the size; payload is valid for `len` bytes.
    let needed = unsafe {
        fa_encode(
            payload.as_ptr(),
            len,
            std::ptr::null_mut(),
            0,
            protocol,
            VOLUME,
        )
    };
    if needed <= 0 {
        return Err(AcousticError::Encode);
    }
    let mut samples = vec![0f32; needed as usize];
    // SAFETY: `samples` holds `needed` floats, the capacity passed in.
    let written = unsafe {
        fa_encode(
            payload.as_ptr(),
            len,
            samples.as_mut_ptr(),
            needed,
            protocol,
            VOLUME,
        )
    };
    if written != needed {
        return Err(AcousticError::Encode);
    }
    Ok(samples)
}

/// Listens to a stream of microphone samples and returns messages as they complete.
pub struct Decoder {
    handle: *mut c_void,
}

// SAFETY: the handle is owned by one Decoder and only used through &mut self.
unsafe impl Send for Decoder {}

impl Decoder {
    pub fn new() -> Result<Self, AcousticError> {
        // SAFETY: plain constructor, returns null on failure.
        let handle = unsafe { fa_decoder_new() };
        if handle.is_null() {
            Err(AcousticError::Decoder)
        } else {
            Ok(Self { handle })
        }
    }

    /// Feeds mono f32 samples at [`SAMPLE_RATE`]. Chunks may have any size.
    /// Returns a message when this chunk completed one.
    pub fn push(&mut self, samples: &[f32]) -> Option<Vec<u8>> {
        let mut out = [0u8; MAX_MESSAGE_LEN];
        // SAFETY: samples and out are valid for the lengths passed.
        let n = unsafe {
            fa_decoder_push(
                self.handle,
                samples.as_ptr(),
                samples.len() as i32,
                out.as_mut_ptr(),
                out.len() as i32,
            )
        };
        (n > 0).then(|| out[..n as usize].to_vec())
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        // SAFETY: the handle came from fa_decoder_new and is freed once.
        unsafe { fa_decoder_free(self.handle) }
    }
}

/// Frequencies, in Hz, checked to tell whether someone is sending. They span the band ggwave uses (15 to 19.5 kHz).
const WATCHED_HZ: [f32; 7] = [
    15_000.0, 15_750.0, 16_500.0, 17_250.0, 18_000.0, 18_750.0, 19_250.0,
];
const BLOCK: usize = 1024;
/// A block is loud when its energy is this many times the ambient level.
const LOUDER_THAN_AMBIENT: f32 = 8.0;
/// Quiet blocks needed before the channel counts as free again, about 0.3 s.
const HOLD_BLOCKS: u32 = 14;
/// A source that never stops, longer than any message (1.8 s), is noise. It becomes the new ambient level.
const MAX_BUSY_BLOCKS: u32 = 140;

/// Tells whether another phone is sending sound right now, so this phone can wait its turn.
///
/// It watches the energy in the band the messages use and compares it with the
/// ambient level, which it learns by itself. Steady background noise therefore
/// never blocks the channel.
pub struct ChannelMonitor {
    pending: Vec<f32>,
    ambient: Option<f32>,
    busy_blocks: u32,
    quiet_blocks: u32,
    busy: bool,
}

impl Default for ChannelMonitor {
    fn default() -> Self {
        Self::new()
    }
}

impl ChannelMonitor {
    pub fn new() -> Self {
        Self {
            pending: Vec::new(),
            ambient: None,
            busy_blocks: 0,
            quiet_blocks: HOLD_BLOCKS,
            busy: false,
        }
    }

    /// Feeds mono f32 samples at [`SAMPLE_RATE`]. Returns whether the channel is busy now.
    pub fn push(&mut self, samples: &[f32]) -> bool {
        self.pending.extend_from_slice(samples);
        let mut used = 0;
        while self.pending.len() - used >= BLOCK {
            let energy = band_energy(&self.pending[used..used + BLOCK]);
            used += BLOCK;
            self.update(energy);
        }
        self.pending.drain(..used);
        self.busy
    }

    fn update(&mut self, energy: f32) {
        let ambient = *self.ambient.get_or_insert(energy);
        if energy > ambient * LOUDER_THAN_AMBIENT && energy > 1e-9 {
            self.busy_blocks += 1;
            self.quiet_blocks = 0;
            if self.busy_blocks > MAX_BUSY_BLOCKS {
                self.ambient = Some(energy);
                self.busy_blocks = 0;
            }
        } else {
            self.busy_blocks = 0;
            self.quiet_blocks = (self.quiet_blocks + 1).min(HOLD_BLOCKS);
            self.ambient = Some(0.95 * ambient + 0.05 * energy);
        }
        self.busy = self.quiet_blocks < HOLD_BLOCKS;
    }
}

/// Energy of one block at the watched frequencies, by the Goertzel method.
fn band_energy(block: &[f32]) -> f32 {
    WATCHED_HZ
        .iter()
        .map(|hz| {
            let w = 2.0 * std::f32::consts::PI * hz / SAMPLE_RATE as f32;
            let coeff = 2.0 * w.cos();
            let (mut s1, mut s2) = (0f32, 0f32);
            for &x in block {
                let s0 = x + coeff * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (block.len() as f32).powi(2)
        })
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Deterministic noise, so the test does not need a random number crate.
    fn noise(seed: &mut u32, amplitude: f32) -> f32 {
        *seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        ((*seed >> 8) as f32 / (1u32 << 24) as f32 - 0.5) * 2.0 * amplitude
    }

    fn hear(samples: &[f32], chunk: usize) -> Option<Vec<u8>> {
        let mut decoder = Decoder::new().unwrap();
        samples.chunks(chunk).find_map(|c| decoder.push(c))
    }

    #[test]
    fn message_survives_noise_and_silence() {
        let payload: Vec<u8> = (1..=18).collect();
        let wave = encode(&payload).unwrap();
        println!(
            "18 bytes take {:.2} s of sound",
            wave.len() as f32 / SAMPLE_RATE as f32
        );

        let mut seed = 7;
        let mut heard: Vec<f32> = (0..SAMPLE_RATE / 2)
            .map(|_| noise(&mut seed, 0.02))
            .collect();
        heard.extend(wave.iter().map(|s| s + noise(&mut seed, 0.02)));
        heard.extend((0..SAMPLE_RATE / 2).map(|_| noise(&mut seed, 0.02)));

        assert_eq!(hear(&heard, 1024), Some(payload));
    }

    #[test]
    fn works_with_small_microphone_buffers() {
        let payload = vec![0xAB; 18];
        let wave = encode(&payload).unwrap();
        let mut heard = vec![0f32; 4000];
        heard.extend(&wave);
        heard.extend(vec![0f32; 4000]);
        assert_eq!(hear(&heard, 333), Some(payload));
    }

    /// Phones start recording at any moment, so the sound lands at a random offset in the stream.
    #[test]
    fn decodes_exactly_at_every_alignment() {
        let payload: Vec<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8, 192, 168, 49, 1, 208, 69, 9, 8, 7, 6];
        let wave = encode(&payload).unwrap();
        for k in 0..40usize {
            let mut heard = vec![0f32; 1500 + k * 337];
            heard.extend(&wave);
            heard.extend(vec![0f32; 6000]);
            assert_eq!(hear(&heard, 1024), Some(payload.clone()), "offset {k}");
        }
    }

    #[test]
    fn silence_decodes_nothing() {
        assert_eq!(hear(&vec![0f32; SAMPLE_RATE as usize * 2], 1024), None);
    }

    #[test]
    fn empty_payload_is_refused() {
        assert_eq!(encode(&[]), Err(AcousticError::Encode));
    }

    fn noise_vec(len: usize, amplitude: f32, seed: &mut u32) -> Vec<f32> {
        (0..len).map(|_| noise(seed, amplitude)).collect()
    }

    fn busy_per_chunk(monitor: &mut ChannelMonitor, samples: &[f32]) -> Vec<bool> {
        samples.chunks(1024).map(|c| monitor.push(c)).collect()
    }

    #[test]
    fn monitor_is_free_in_silence_and_in_steady_noise() {
        let mut seed = 3;
        let mut monitor = ChannelMonitor::new();
        assert!(
            busy_per_chunk(&mut monitor, &vec![0f32; SAMPLE_RATE as usize])
                .iter()
                .all(|b| !b)
        );
        let mut noisy = ChannelMonitor::new();
        assert!(busy_per_chunk(
            &mut noisy,
            &noise_vec(SAMPLE_RATE as usize * 2, 0.05, &mut seed)
        )
        .iter()
        .all(|b| !b));
    }

    #[test]
    fn monitor_is_busy_during_a_message_and_free_after_it() {
        let mut seed = 5;
        let wave = encode(&(1..=18).collect::<Vec<u8>>()).unwrap();
        let rate = SAMPLE_RATE as usize;
        let mut heard = noise_vec(rate, 0.02, &mut seed);
        let start = heard.len();
        heard.extend(wave.iter().map(|s| s + noise(&mut seed, 0.02)));
        let end = heard.len();
        heard.extend(noise_vec(rate, 0.02, &mut seed));

        let mut monitor = ChannelMonitor::new();
        let flags = busy_per_chunk(&mut monitor, &heard);
        let at = |sample: usize| flags[sample / 1024];
        assert!(!at(start - 2048), "free before the message");
        assert!(
            at(start + rate / 2) && at(start + rate) && at(end - 4096),
            "busy during the message"
        );
        assert!(!at(end + rate / 2 + 4096), "free again shortly after");
    }

    #[test]
    fn monitor_ignores_a_source_that_never_stops() {
        // A steady ultrasonic tone, longer than any message, is treated as ambient noise.
        let tone: Vec<f32> = (0..SAMPLE_RATE as usize * 6)
            .map(|i| {
                0.3 * (2.0 * std::f32::consts::PI * 17_250.0 * i as f32 / SAMPLE_RATE as f32).sin()
            })
            .collect();
        let mut monitor = ChannelMonitor::new();
        let mut seed = 9;
        monitor.push(&noise_vec(SAMPLE_RATE as usize / 2, 0.01, &mut seed));
        let flags = busy_per_chunk(&mut monitor, &tone);
        assert!(flags[10], "busy at first");
        assert!(
            !flags[flags.len() - 1],
            "free once it is clearly a constant source"
        );
    }

    #[test]
    fn signal_is_loud_without_clipping() {
        let payload: Vec<u8> = (1..=18).collect();
        for protocol in [PROTOCOL, ROBUST_PROTOCOL] {
            let peak = encode_with(&payload, protocol)
                .unwrap()
                .iter()
                .fold(0f32, |m, s| m.max(s.abs()));
            assert!(
                (0.6..0.95).contains(&peak),
                "protocol {protocol} peaks at {peak}"
            );
        }
    }

    #[test]
    fn both_protocols_decode_a_weak_signal_at_every_alignment() {
        let payload: Vec<u8> = vec![1, 2, 3, 4, 5, 6, 7, 8, 192, 168, 49, 1, 208, 69, 9, 8, 7, 6];
        let mut seed = 21;
        for protocol in [PROTOCOL, ROBUST_PROTOCOL] {
            let wave = encode_with(&payload, protocol).unwrap();
            for k in 0..20usize {
                // The speaker is far or weak: a tenth of the level, over some noise.
                let mut heard = noise_vec(1500 + k * 337, 0.003, &mut seed);
                heard.extend(wave.iter().map(|s| s * 0.1 + noise(&mut seed, 0.003)));
                heard.extend(noise_vec(6000, 0.003, &mut seed));
                assert_eq!(
                    hear(&heard, 1024),
                    Some(payload.clone()),
                    "protocol {protocol}, offset {k}"
                );
            }
        }
    }
}
