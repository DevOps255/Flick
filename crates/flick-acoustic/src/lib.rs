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

/// Largest message the decoder returns, in bytes.
pub const MAX_MESSAGE_LEN: usize = 64;

extern "C" {
    fn fa_encode(payload: *const u8, len: i32, out: *mut f32, cap: i32, protocol: i32) -> i32;
    fn fa_decoder_new() -> *mut c_void;
    fn fa_decoder_push(handle: *mut c_void, samples: *const f32, n: i32, out: *mut u8, cap: i32) -> i32;
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
    let needed = unsafe { fa_encode(payload.as_ptr(), len, std::ptr::null_mut(), 0, protocol) };
    if needed <= 0 {
        return Err(AcousticError::Encode);
    }
    let mut samples = vec![0f32; needed as usize];
    // SAFETY: `samples` holds `needed` floats, the capacity passed in.
    let written = unsafe { fa_encode(payload.as_ptr(), len, samples.as_mut_ptr(), needed, protocol) };
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
            fa_decoder_push(self.handle, samples.as_ptr(), samples.len() as i32, out.as_mut_ptr(), out.len() as i32)
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
        println!("18 bytes take {:.2} s of sound", wave.len() as f32 / SAMPLE_RATE as f32);

        let mut seed = 7;
        let mut heard: Vec<f32> = (0..SAMPLE_RATE / 2).map(|_| noise(&mut seed, 0.02)).collect();
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
}
