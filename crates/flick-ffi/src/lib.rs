//! Kotlin bindings for the shared Flick crates, generated with UniFFI.
//!
//! Nothing here holds logic of its own. It converts between types UniFFI can
//! pass to Kotlin and the crates that do the work.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

uniffi::setup_scaffolding!();

/// Things that can go wrong, reported to Kotlin as an exception.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FlickError {
    #[error("announcement must be 18 bytes, got {len}")]
    WrongLength { len: u32 },
    #[error("a field has the wrong size")]
    BadField,
    // Not called `message`: Kotlin exceptions already have a property with that name.
    #[error("{reason}")]
    Failed { reason: String },
}

fn failed(e: impl std::fmt::Display) -> FlickError {
    FlickError::Failed {
        reason: format!("{e:#}"),
    }
}

// Announcement and sound

/// The ultrasonic announcement, in a form UniFFI can pass to Kotlin.
#[derive(Clone, uniffi::Record)]
pub struct AnnouncementData {
    pub fingerprint_prefix: Vec<u8>,
    pub ipv4: Vec<u8>,
    pub port: u16,
    pub nonce: Vec<u8>,
}

impl From<flick_proto::Announcement> for AnnouncementData {
    fn from(a: flick_proto::Announcement) -> Self {
        Self {
            fingerprint_prefix: a.fingerprint_prefix.to_vec(),
            ipv4: a.ipv4.to_vec(),
            port: a.port,
            nonce: a.nonce.to_vec(),
        }
    }
}

impl TryFrom<AnnouncementData> for flick_proto::Announcement {
    type Error = FlickError;

    fn try_from(a: AnnouncementData) -> Result<Self, FlickError> {
        Ok(Self {
            fingerprint_prefix: a
                .fingerprint_prefix
                .try_into()
                .map_err(|_| FlickError::BadField)?,
            ipv4: a.ipv4.try_into().map_err(|_| FlickError::BadField)?,
            port: a.port,
            nonce: a.nonce.try_into().map_err(|_| FlickError::BadField)?,
        })
    }
}

/// Sample rate of every sound buffer exchanged with Kotlin, in Hz.
#[uniffi::export]
pub fn sound_sample_rate() -> u32 {
    flick_acoustic::SAMPLE_RATE
}

/// Turns an announcement into mono f32 samples, ready to play.
///
/// `slow` picks the slower mode, 2.4 s instead of 1.8 s, which a weak microphone decodes more easily.
/// The receiver decodes both, so a phone can alternate them.
#[uniffi::export]
pub fn announcement_to_sound(
    announcement: AnnouncementData,
    slow: bool,
) -> Result<Vec<f32>, FlickError> {
    let a: flick_proto::Announcement = announcement.try_into()?;
    let protocol = if slow {
        flick_acoustic::ROBUST_PROTOCOL
    } else {
        flick_acoustic::PROTOCOL
    };
    flick_acoustic::encode_with(&a.encode(), protocol).map_err(|e| failed(format!("{e:?}")))
}

/// Listens to microphone samples and reports announcements, each one once.
#[derive(uniffi::Object)]
pub struct SoundListener {
    decoder: Mutex<flick_acoustic::Decoder>,
    replay: Mutex<flick_proto::ReplayGuard>,
    monitor: Mutex<flick_acoustic::ChannelMonitor>,
    busy: std::sync::atomic::AtomicBool,
}

#[uniffi::export]
impl SoundListener {
    #[uniffi::constructor]
    pub fn new() -> Result<Arc<Self>, FlickError> {
        Ok(Arc::new(Self {
            decoder: Mutex::new(
                flick_acoustic::Decoder::new().map_err(|e| failed(format!("{e:?}")))?,
            ),
            replay: Mutex::new(flick_proto::ReplayGuard::new(Duration::from_secs(30))),
            monitor: Mutex::new(flick_acoustic::ChannelMonitor::new()),
            busy: std::sync::atomic::AtomicBool::new(false),
        }))
    }

    /// Feeds mono f32 samples at [`sound_sample_rate`]. Returns an announcement when one just completed.
    /// Garbled messages and replays return nothing.
    pub fn push(&self, samples: Vec<f32>) -> Option<AnnouncementData> {
        let busy = self.monitor.lock().unwrap().push(&samples);
        self.busy.store(busy, std::sync::atomic::Ordering::Relaxed);
        let bytes = self.decoder.lock().unwrap().push(&samples)?;
        let announcement = flick_proto::Announcement::decode(&bytes).ok()?;
        self.replay
            .lock()
            .unwrap()
            .accept(&announcement, Instant::now())
            .then(|| announcement.into())
    }

    /// True while another phone is sending sound. Wait for false before playing an announcement,
    /// so two phones do not talk over each other.
    pub fn channel_busy(&self) -> bool {
        self.busy.load(std::sync::atomic::Ordering::Relaxed)
    }
}

// Bluetooth frame, pairing and tilt

/// What a phone advertises over Bluetooth.
#[derive(Clone, uniffi::Record)]
pub struct BleFrameData {
    pub announcement: AnnouncementData,
    pub gesture: bool,
    pub gesture_seq: u8,
}

/// Manufacturer id to put the frame under in the advertisement and to filter scans on.
#[uniffi::export]
pub fn ble_manufacturer_id() -> u16 {
    flick_proto::ble::MANUFACTURER_ID
}

/// First byte of every frame. Use it to filter scan results.
#[uniffi::export]
pub fn ble_magic() -> u8 {
    flick_proto::ble::MAGIC
}

/// Serializes a frame for the advertisement.
#[uniffi::export]
pub fn ble_frame_encode(frame: BleFrameData) -> Result<Vec<u8>, FlickError> {
    let announcement: flick_proto::Announcement = frame.announcement.try_into()?;
    let frame = flick_proto::ble::BleFrame {
        announcement,
        gesture: frame.gesture,
        gesture_seq: frame.gesture_seq,
    };
    Ok(frame.encode().to_vec())
}

/// Reads manufacturer data from a scan. Returns nothing for data that is not a Flick frame.
#[uniffi::export]
pub fn ble_frame_decode(bytes: Vec<u8>) -> Option<BleFrameData> {
    flick_proto::ble::BleFrame::decode(&bytes).map(|f| BleFrameData {
        announcement: f.announcement.into(),
        gesture: f.gesture,
        gesture_seq: f.gesture_seq,
    })
}

/// A phone heard over Bluetooth.
#[derive(Clone, uniffi::Record)]
pub struct NearbyPhone {
    pub id: String,
    pub announcement: AnnouncementData,
    pub rssi_dbm: f32,
    pub distance_m: f32,
    /// 1 when touching, 0 at two metres or more.
    pub proximity: f32,
}

impl From<flick_proto::pairing::PeerView> for NearbyPhone {
    fn from(v: flick_proto::pairing::PeerView) -> Self {
        Self {
            id: v.id,
            announcement: v.announcement.into(),
            rssi_dbm: v.rssi_dbm,
            distance_m: v.distance_m,
            proximity: v.proximity,
        }
    }
}

/// Whether the user paired with a phone.
#[derive(uniffi::Enum)]
pub enum PairingResult {
    /// No gesture here, or the other phone has not made one yet.
    Pending,
    /// Several phones are about as close. Nothing is paired.
    Ambiguous,
    Paired {
        phone: NearbyPhone,
    },
}

/// Follows the phones heard over Bluetooth and decides which one the user paired with.
#[derive(uniffi::Object)]
pub struct PairingEngine {
    inner: Mutex<flick_proto::pairing::Pairing>,
}

#[uniffi::export]
impl PairingEngine {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(flick_proto::pairing::Pairing::new()),
        })
    }

    /// Records a frame heard at `now_ms` (any steady clock, in milliseconds) with its signal strength.
    pub fn on_frame(
        &self,
        frame: BleFrameData,
        rssi_dbm: i32,
        now_ms: u64,
    ) -> Result<(), FlickError> {
        let announcement: flick_proto::Announcement = frame.announcement.try_into()?;
        let frame = flick_proto::ble::BleFrame {
            announcement,
            gesture: frame.gesture,
            gesture_seq: frame.gesture_seq,
        };
        self.inner
            .lock()
            .unwrap()
            .on_frame(&frame, rssi_dbm, now_ms);
        Ok(())
    }

    /// The user made the pairing gesture on this phone.
    pub fn on_own_gesture(&self, now_ms: u64) {
        self.inner.lock().unwrap().on_own_gesture(now_ms);
    }

    /// The phones heard in the last seconds, closest first.
    pub fn phones(&self, now_ms: u64) -> Vec<NearbyPhone> {
        self.inner
            .lock()
            .unwrap()
            .peers(now_ms)
            .into_iter()
            .map(Into::into)
            .collect()
    }

    /// Call it often. A pairing is reported once.
    pub fn poll(&self, now_ms: u64) -> PairingResult {
        use flick_proto::pairing::Outcome;
        match self.inner.lock().unwrap().poll(now_ms) {
            Outcome::Pending => PairingResult::Pending,
            Outcome::Ambiguous => PairingResult::Ambiguous,
            Outcome::Paired(view) => PairingResult::Paired { phone: view.into() },
        }
    }
}

/// A deliberate tilt of the phone.
#[derive(uniffi::Record)]
pub struct TiltEvent {
    pub t_ms: u64,
    pub angle: f32,
    pub axis: u32,
}

/// Feed gyroscope readings in, get tilts out. One instance per sensor.
#[derive(uniffi::Object)]
pub struct TiltDetector {
    inner: Mutex<flick_gesture::tilt::TiltDetector>,
}

#[uniffi::export]
impl TiltDetector {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(flick_gesture::tilt::TiltDetector::new(Default::default())),
        })
    }

    /// Pushes one gyroscope reading in rad/s. Returns a tilt when one just finished.
    pub fn push(&self, t_ms: u64, x: f32, y: f32, z: f32) -> Option<TiltEvent> {
        let sample = flick_gesture::tilt::GyroSample {
            t_ms,
            rate: [x, y, z],
        };
        self.inner.lock().unwrap().push(sample).map(|t| TiltEvent {
            t_ms: t.t_ms,
            angle: t.angle,
            axis: t.axis as u32,
        })
    }
}

// Link between phones

/// An address this phone has on a local network.
#[derive(uniffi::Record)]
pub struct LocalLinkData {
    pub ip: Vec<u8>,
    pub prefix_len: u8,
}

/// What this phone must do to reach the one it heard.
#[derive(uniffi::Enum)]
pub enum LinkPlanData {
    /// Already on the same network, nothing to do.
    SameNetwork,
    /// Create a Wi-Fi Direct group with this name and password.
    CreateGroup { ssid: String, passphrase: String },
    /// Join the Wi-Fi Direct group the other phone creates.
    JoinGroup { ssid: String, passphrase: String },
}

/// Decides between the shared network and a Wi-Fi Direct group, and who creates the group.
#[uniffi::export]
pub fn plan_link(
    own_fingerprint_prefix: Vec<u8>,
    own_links: Vec<LocalLinkData>,
    peer: AnnouncementData,
) -> Result<LinkPlanData, FlickError> {
    use flick_core::link::{plan, LinkPlan, LocalLink};
    let own: [u8; 8] = own_fingerprint_prefix
        .try_into()
        .map_err(|_| FlickError::BadField)?;
    let links = own_links
        .into_iter()
        .map(|l| {
            Ok(LocalLink {
                ip: l.ip.try_into().map_err(|_| FlickError::BadField)?,
                prefix_len: l.prefix_len,
            })
        })
        .collect::<Result<Vec<_>, FlickError>>()?;
    let peer: flick_proto::Announcement = peer.try_into()?;
    Ok(match plan(&own, &links, &peer) {
        LinkPlan::SameNetwork => LinkPlanData::SameNetwork,
        LinkPlan::CreateGroup(c) => LinkPlanData::CreateGroup {
            ssid: c.ssid,
            passphrase: c.passphrase,
        },
        LinkPlan::JoinGroup(c) => LinkPlanData::JoinGroup {
            ssid: c.ssid,
            passphrase: c.passphrase,
        },
    })
}

// Gesture

/// A detected Flick, with the direction of the push as a unit vector.
#[derive(uniffi::Record)]
pub struct FlickEvent {
    pub t_ms: u64,
    pub peak: f32,
    pub dx: f32,
    pub dy: f32,
    pub dz: f32,
}

/// Feed accelerometer readings in, get Flicks out. One instance per sensor.
#[derive(uniffi::Object)]
pub struct FlickDetector {
    inner: Mutex<flick_gesture::Detector>,
}

#[uniffi::export]
impl FlickDetector {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(flick_gesture::Detector::new(
                flick_gesture::Config::default(),
            )),
        })
    }

    /// Pushes one reading in m/s², gravity included. Returns a Flick when one just finished.
    pub fn push(&self, t_ms: u64, x: f32, y: f32, z: f32) -> Option<FlickEvent> {
        let mut det = self.inner.lock().expect("detector lock poisoned");
        det.push(flick_gesture::Sample {
            t_ms,
            accel: [x, y, z],
        })
        .map(|f| FlickEvent {
            t_ms: f.t_ms,
            peak: f.peak,
            dx: f.direction[0],
            dy: f.direction[1],
            dz: f.direction[2],
        })
    }
}

// Transfer

/// Creates a new device identity. Takes a second or two, so call it once and keep the text.
#[uniffi::export]
pub fn generate_identity() -> Result<String, FlickError> {
    Ok(flick_core::Identity::generate().map_err(failed)?.to_pem())
}

/// What happened on this device, for the app to show.
#[derive(uniffi::Enum)]
pub enum TransferEvent {
    IncomingStarted {
        session_id: String,
        sender: String,
        file_names: Vec<String>,
        total_bytes: u64,
    },
    Progress {
        session_id: String,
        done_bytes: u64,
        total_bytes: u64,
    },
    FileReceived {
        session_id: String,
        path: String,
    },
    Finished {
        session_id: String,
        ok: bool,
    },
    Declined {
        sender: String,
    },
}

impl From<flick_core::Event> for TransferEvent {
    fn from(e: flick_core::Event) -> Self {
        use flick_core::Event as E;
        match e {
            E::IncomingStarted {
                session_id,
                sender,
                file_names,
                total_bytes,
            } => Self::IncomingStarted {
                session_id,
                sender,
                file_names,
                total_bytes,
            },
            E::Progress {
                session_id,
                done_bytes,
                total_bytes,
            } => Self::Progress {
                session_id,
                done_bytes,
                total_bytes,
            },
            E::FileReceived { session_id, path } => Self::FileReceived {
                session_id,
                path: path.to_string_lossy().into(),
            },
            E::Finished { session_id, ok } => Self::Finished { session_id, ok },
            E::Declined { sender } => Self::Declined { sender },
        }
    }
}

/// A file to send, given as an open file descriptor.
///
/// On Android a picked file can only be read through the descriptor the system hands out.
/// Detach it in Kotlin with `ParcelFileDescriptor.detachFd()`: Rust takes ownership and closes it.
#[derive(uniffi::Record)]
pub struct OutgoingFileData {
    pub fd: i32,
    pub name: String,
    pub size: u64,
}

/// Implemented in Kotlin. Called from background threads.
#[uniffi::export(with_foreign)]
pub trait ReceiveObserver: Send + Sync {
    fn on_event(&self, event: TransferEvent);
}

/// Implemented in Kotlin. Reports bytes sent so far.
#[uniffi::export(with_foreign)]
pub trait SendObserver: Send + Sync {
    fn on_progress(&self, done_bytes: u64, total_bytes: u64);
}

struct Forward(Arc<dyn ReceiveObserver>);

impl flick_core::Observer for Forward {
    fn on_event(&self, event: flick_core::Event) {
        self.0.on_event(event.into());
    }
}

/// A running device: receives files from heard senders and sends to heard receivers.
#[derive(uniffi::Object)]
pub struct FlickNode {
    inner: flick_core::Node,
    cancel: Mutex<tokio_util::sync::CancellationToken>,
}

/// Starts the server. `identity_pem` comes from [`generate_identity`]. Received files go to `inbox_dir`.
#[uniffi::export(async_runtime = "tokio")]
pub async fn start_node(
    identity_pem: String,
    alias: String,
    inbox_dir: String,
    observer: Arc<dyn ReceiveObserver>,
) -> Result<Arc<FlickNode>, FlickError> {
    let identity = flick_core::Identity::from_pem(&identity_pem).map_err(failed)?;
    let inner = flick_core::Node::start(
        identity,
        alias,
        inbox_dir.into(),
        0,
        Arc::new(Forward(observer)),
    )
    .await
    .map_err(failed)?;
    Ok(Arc::new(FlickNode {
        inner,
        cancel: Mutex::new(Default::default()),
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl FlickNode {
    pub fn port(&self) -> u16 {
        self.inner.port()
    }

    /// The announcement this device plays. `ipv4` is its address on the shared link, `nonce` 4 fresh random bytes.
    pub fn announcement(
        &self,
        ipv4: Vec<u8>,
        nonce: Vec<u8>,
    ) -> Result<AnnouncementData, FlickError> {
        let ipv4: [u8; 4] = ipv4.try_into().map_err(|_| FlickError::BadField)?;
        let nonce: [u8; 4] = nonce.try_into().map_err(|_| FlickError::BadField)?;
        flick_core::announcement_for(self.inner.identity(), ipv4, self.inner.port(), nonce)
            .map(Into::into)
            .ok_or(FlickError::BadField)
    }

    /// Allows the device behind a heard announcement to send here for the next minute.
    pub fn trust(&self, announcement: AnnouncementData) -> Result<(), FlickError> {
        let a: flick_proto::Announcement = announcement.try_into()?;
        self.inner.trust(&a.fingerprint_prefix_hex());
        Ok(())
    }

    /// Replaces the list of phones allowed to send here. An empty list refuses everybody.
    pub fn set_trusted(&self, announcements: Vec<AnnouncementData>) -> Result<(), FlickError> {
        let prefixes = announcements
            .into_iter()
            .map(|a| flick_proto::Announcement::try_from(a).map(|a| a.fingerprint_prefix_hex()))
            .collect::<Result<Vec<_>, FlickError>>()?;
        self.inner.set_trusted(&prefixes);
        Ok(())
    }

    /// Sends files to the device behind a heard announcement. Returns when all arrived.
    pub async fn send(
        &self,
        to: AnnouncementData,
        files: Vec<OutgoingFileData>,
        observer: Arc<dyn SendObserver>,
    ) -> Result<(), FlickError> {
        let a: flick_proto::Announcement = to.try_into()?;
        // Sends that run at the same time share one token, so cancelling stops all of them.
        // After a cancel, the next send gets a fresh token.
        let token = {
            let mut shared = self.cancel.lock().unwrap();
            if shared.is_cancelled() {
                *shared = tokio_util::sync::CancellationToken::new();
            }
            shared.clone()
        };
        let files: Vec<flick_core::OutgoingFile> = files
            .into_iter()
            .map(|f| {
                // SAFETY: Kotlin detached the descriptor, so nobody else owns or closes it.
                let file = unsafe { <std::fs::File as std::os::fd::FromRawFd>::from_raw_fd(f.fd) };
                flick_core::OutgoingFile::from_file(file, f.name, f.size)
            })
            .collect();
        self.inner
            .send(
                &flick_core::target_from(&a),
                &files,
                move |done, total| observer.on_progress(done, total),
                token,
            )
            .await
            .map_err(failed)
    }

    /// The name the other device gave itself. Fails if its certificate is not the announced one.
    pub async fn describe(&self, peer: AnnouncementData) -> Result<String, FlickError> {
        let a: flick_proto::Announcement = peer.try_into()?;
        self.inner
            .describe(&flick_core::target_from(&a))
            .await
            .map_err(failed)
    }

    /// Stops every send in progress.
    pub fn cancel_send(&self) {
        self.cancel.lock().unwrap().cancel();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn announcement_survives_sound() {
        let a = AnnouncementData {
            fingerprint_prefix: vec![1, 2, 3, 4, 5, 6, 7, 8],
            ipv4: vec![192, 168, 49, 1],
            port: 53317,
            nonce: vec![9, 8, 7, 6],
        };
        let wave = announcement_to_sound(a.clone(), false).unwrap();
        let listener = SoundListener::new().unwrap();
        let mut heard = vec![0f32; 8000];
        heard.extend(wave);
        heard.extend(vec![0f32; 8000]);
        let got = heard
            .chunks(1024)
            .find_map(|c| listener.push(c.to_vec()))
            .expect("announcement heard");
        assert_eq!(
            (got.fingerprint_prefix, got.ipv4, got.port, got.nonce),
            (a.fingerprint_prefix, a.ipv4, a.port, a.nonce)
        );
    }

    #[test]
    fn listener_reports_a_busy_channel_while_a_message_plays() {
        let a = AnnouncementData {
            fingerprint_prefix: vec![1; 8],
            ipv4: vec![10, 0, 0, 1],
            port: 1,
            nonce: vec![1; 4],
        };
        let wave = announcement_to_sound(a, false).unwrap();
        let listener = SoundListener::new().unwrap();
        listener.push(vec![0.0; 24_000]);
        assert!(!listener.channel_busy());
        listener.push(wave[..48_000].to_vec());
        assert!(listener.channel_busy(), "busy during the message");
    }

    #[test]
    fn link_plan_crosses_the_ffi_boundary() {
        let peer = AnnouncementData {
            fingerprint_prefix: vec![3; 8],
            ipv4: vec![0; 4],
            port: 1,
            nonce: vec![0; 4],
        };
        let plan = plan_link(vec![9; 8], vec![], peer.clone()).unwrap();
        assert!(
            matches!(plan, LinkPlanData::CreateGroup { ref ssid, .. } if ssid.starts_with("DIRECT-FK-"))
        );
        let same = plan_link(
            vec![9; 8],
            vec![LocalLinkData {
                ip: vec![192, 168, 1, 5],
                prefix_len: 24,
            }],
            AnnouncementData {
                ipv4: vec![192, 168, 1, 9],
                ..peer
            },
        );
        assert!(matches!(same, Ok(LinkPlanData::SameNetwork)));
    }

    #[test]
    fn two_phones_pair_through_the_ffi() {
        let a = AnnouncementData {
            fingerprint_prefix: vec![5; 8],
            ipv4: vec![10, 0, 0, 2],
            port: 1,
            nonce: vec![0; 4],
        };
        let engine = PairingEngine::new();
        let bytes = ble_frame_encode(BleFrameData {
            announcement: a.clone(),
            gesture: false,
            gesture_seq: 0,
        })
        .unwrap();
        let heard = ble_frame_decode(bytes).expect("a frame");
        engine.on_frame(heard, -50, 1_000).unwrap();
        engine.on_own_gesture(1_500);
        assert!(matches!(engine.poll(1_600), PairingResult::Pending));
        engine
            .on_frame(
                BleFrameData {
                    announcement: a,
                    gesture: true,
                    gesture_seq: 1,
                },
                -50,
                1_700,
            )
            .unwrap();
        assert!(matches!(engine.poll(1_800), PairingResult::Paired { .. }));
        assert_eq!(engine.phones(1_800).len(), 1);
    }

    #[test]
    fn tilt_crosses_the_ffi_boundary() {
        let tilt = TiltDetector::new();
        let found = (0..80u64).find_map(|i| {
            tilt.push(
                i * 20,
                if (30..45).contains(&i) { 2.0 } else { 0.0 },
                0.0,
                0.0,
            )
        });
        assert!(found.is_some());
    }

    #[test]
    fn wrong_sizes_are_errors_not_panics() {
        let bad = AnnouncementData {
            fingerprint_prefix: vec![1],
            ipv4: vec![0; 4],
            port: 1,
            nonce: vec![0; 4],
        };
        assert!(matches!(
            announcement_to_sound(bad, false),
            Err(FlickError::BadField)
        ));
    }

    #[test]
    fn detector_reports_a_push() {
        let det = FlickDetector::new();
        let found = (0..80u64)
            .filter_map(|i| {
                det.push(
                    i * 20,
                    if (50..53).contains(&i) { 22.0 } else { 0.0 },
                    0.0,
                    9.81,
                )
            })
            .next();
        assert!(found.expect("a flick").dx > 0.9);
    }
}
