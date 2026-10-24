//! One running Flick device: a LocalSend HTTPS server that receives files, and
//! a client that sends them.
//!
//! Receiving is closed by default. A sender is accepted only when its
//! certificate fingerprint starts with a prefix the user just heard in an
//! ultrasonic announcement (see [`Node::trust`]). Anyone else is declined.

use crate::identity::Identity;
use anyhow::{anyhow, bail, Context, Result};
use futures_util::StreamExt;
use localsend::http::client::v2::LsHttpClientV2;
use localsend::http::client::ClientError;
use localsend::http::dto_v2::{PrepareUploadRequestDtoV2, RegisterDtoV2};
use localsend::http::server::common::save::FileUploadTarget;
use localsend::http::server::v2::{PrepareUploadDecisionV2, ServerEventV2};
use localsend::http::server::web::WebConfig;
use localsend::http::server::{start_with_port, ServerConfigV2, ServerHandle};
use localsend::http::state::ClientInfo;
use localsend::model::discovery::{DeviceType, ProtocolType, PROTOCOL_VERSION_V2};
use localsend::model::transfer::{FileContent, FileDto};
use localsend::reqwest;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, oneshot};
use tokio_util::sync::CancellationToken;

/// How long a heard announcement allows its sender to upload.
const TRUST_WINDOW: Duration = Duration::from_secs(60);

/// What happened, for the app to show.
#[derive(Clone, Debug, PartialEq)]
pub enum Event {
    /// A trusted device offered files. Receiving starts without further input.
    IncomingStarted { session_id: String, sender: String, file_names: Vec<String>, total_bytes: u64 },
    /// Cumulative bytes moved in this session, sent or received.
    Progress { session_id: String, done_bytes: u64, total_bytes: u64 },
    /// One file is complete on disk.
    FileReceived { session_id: String, path: PathBuf },
    /// The session is over. `ok` is false if anything failed or was cancelled.
    Finished { session_id: String, ok: bool },
    /// An unknown device tried to send and was refused.
    Declined { sender: String },
}

/// Receives events from a [`Node`]. Called from background threads.
pub trait Observer: Send + Sync + 'static {
    fn on_event(&self, event: Event);
}

/// Where to send to, as decoded from an announcement.
#[derive(Clone, Debug)]
pub struct Target {
    pub host: String,
    pub port: u16,
    /// Uppercase hex prefix of the certificate fingerprint, at least 16 characters.
    pub fingerprint_prefix: String,
}

/// A file to send. `path` can be any readable path, for example `/proc/self/fd/N`
/// on Android, so the real `name` and `size` are passed separately.
#[derive(Clone, Debug)]
pub struct OutgoingFile {
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    /// SHA-256 as hex. The receiver checks the bytes against it. When `None` it is computed before sending.
    pub sha256: Option<String>,
}

struct Session {
    sender: String,
    total_bytes: u64,
    /// Bytes of files already complete.
    finished_bytes: u64,
    /// Files accepted and not yet complete.
    open_files: usize,
    failed: bool,
    ended: bool,
}

type Sessions = Arc<Mutex<HashMap<String, Session>>>;

/// A running device. Dropping it stops the server.
pub struct Node {
    identity: Arc<Identity>,
    alias: String,
    port: u16,
    trusted: Arc<Mutex<HashMap<String, Instant>>>,
    _server: ServerHandle,
    stop_tx: Option<oneshot::Sender<()>>,
}

impl Node {
    /// Starts the server. `port` 0 picks a free port. Received files go to `inbox`.
    pub async fn start(
        identity: Identity,
        alias: String,
        inbox: PathBuf,
        port: u16,
        observer: Arc<dyn Observer>,
    ) -> Result<Self> {
        std::fs::create_dir_all(&inbox).context("cannot create the inbox folder")?;
        let identity = Arc::new(identity);
        let (event_tx, event_rx) = mpsc::channel::<ServerEventV2>(16);
        let (stop_tx, stop_rx) = oneshot::channel();
        let info = ClientInfo {
            alias: alias.clone(),
            version: PROTOCOL_VERSION_V2.to_string(),
            device_model: Some("Flick".to_string()),
            device_type: Some(DeviceType::Mobile),
            token: identity.fingerprint.clone(),
        };
        let server = start_with_port(
            port,
            Some(localsend::http::server::TlsConfig {
                cert: identity.cert_pem.clone(),
                private_key: identity.key_pem.clone(),
            }),
            info,
            None,
            Some(ServerConfigV2 { pin: None, verify_checksums: true, event_tx }),
            WebConfig::default(),
            stop_rx,
        )
        .await?;
        let bound = server.port();

        let trusted: Arc<Mutex<HashMap<String, Instant>>> = Arc::default();
        tokio::spawn(receive_loop(event_rx, trusted.clone(), inbox, observer));

        Ok(Self { identity, alias, port: bound, trusted, _server: server, stop_tx: Some(stop_tx) })
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn identity(&self) -> &Identity {
        &self.identity
    }

    /// Lets the device whose certificate starts with `fingerprint_prefix` upload
    /// for the next minute. Call it when its announcement is heard.
    pub fn trust(&self, fingerprint_prefix: &str) {
        self.trusted.lock().unwrap().insert(fingerprint_prefix.to_ascii_uppercase(), Instant::now());
    }

    /// Sends files to `target` and returns when all of them arrived.
    ///
    /// `on_progress` receives cumulative bytes sent and the total. The
    /// receiver's certificate must match the fingerprint prefix in `target`,
    /// otherwise the handshake fails before any data leaves this device.
    pub async fn send(
        &self,
        target: &Target,
        outgoing: &[OutgoingFile],
        on_progress: impl Fn(u64, u64) + Send + Sync + 'static,
        cancel: CancellationToken,
    ) -> Result<()> {
        let mut files = HashMap::new();
        let mut sources = HashMap::new();
        for (i, file) in outgoing.iter().enumerate() {
            let id = i.to_string();
            let sha256 = match &file.sha256 {
                Some(known) => known.clone(),
                None => hash_file(&file.path).await.with_context(|| format!("cannot read {}", file.name))?,
            };
            files.insert(
                id.clone(),
                FileDto {
                    id: id.clone(),
                    file_name: file.name.clone(),
                    size: file.size,
                    file_type: "application/octet-stream".to_string(),
                    sha256: Some(sha256),
                    preview: None,
                    metadata: None,
                },
            );
            sources.insert(id, file.path.clone());
        }
        let total: u64 = files.values().map(|f| f.size).sum();

        let client = LsHttpClientV2::try_new(
            &self.identity.key_pem,
            &self.identity.cert_pem,
            Some(target.fingerprint_prefix.clone()),
            None,
        )?;
        let prepared = client
            .prepare_upload(
                ProtocolType::Https,
                &target.host,
                target.port,
                None,
                PrepareUploadRequestDtoV2 { info: self.register_dto(), files: files.clone() },
                None,
                cancel.clone(),
            )
            .await
            .map_err(|e| match e {
                ClientError::StatusCode(s) if s.status == 403 => anyhow!("the other device declined"),
                other => anyhow!("could not reach the other device: {other}"),
            })?;
        let response = prepared.response.context("the other device accepted no file")?;

        let on_progress = Arc::new(on_progress);
        let mut ids: Vec<&String> = response.files.keys().collect();
        ids.sort();
        let mut sent_before = 0u64;
        for id in ids {
            let token = &response.files[id];
            let file = files.get(id).context("unexpected file id in the response")?;
            let path = sources.get(id).context("unexpected file id in the response")?.clone();
            let base = sent_before;
            let report = on_progress.clone();
            let mut sent = 0u64;
            let stream = FileContent::Path(path).into_stream().map(move |chunk| {
                let chunk = chunk?;
                sent += chunk.len() as u64;
                report(base + sent, total);
                Ok::<bytes::Bytes, anyhow::Error>(chunk)
            });
            client
                .upload(
                    ProtocolType::Https,
                    &target.host,
                    target.port,
                    None,
                    &response.session_id,
                    id,
                    token,
                    reqwest::Body::wrap_stream(stream),
                    cancel.clone(),
                )
                .await
                .map_err(|e| anyhow!("upload of {} failed: {e}", file.file_name))?;
            sent_before += file.size;
            on_progress(sent_before, total);
        }
        Ok(())
    }

    /// Asks the device at `target` for its name. The certificate is checked like in [`Node::send`].
    pub async fn describe(&self, target: &Target) -> Result<String> {
        let client = LsHttpClientV2::try_new(
            &self.identity.key_pem,
            &self.identity.cert_pem,
            Some(target.fingerprint_prefix.clone()),
            Some(Duration::from_secs(3)),
        )?;
        let info = client
            .info(ProtocolType::Https, &target.host, target.port)
            .await
            .map_err(|e| anyhow!("could not reach the other device: {e}"))?;
        Ok(info.alias)
    }

    fn register_dto(&self) -> RegisterDtoV2 {
        RegisterDtoV2 {
            alias: self.alias.clone(),
            version: PROTOCOL_VERSION_V2.to_string(),
            device_model: Some("Flick".to_string()),
            device_type: Some(DeviceType::Mobile),
            fingerprint: self.identity.fingerprint.clone(),
            port: self.port,
            protocol: ProtocolType::Https,
            download: false,
        }
    }
}

impl Drop for Node {
    fn drop(&mut self) {
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
    }
}

/// SHA-256 of a file as hex, read in 1 MiB steps so large files never sit in memory.
async fn hash_file(path: &Path) -> Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<String> {
        let mut file = std::fs::File::open(path)?;
        let mut hasher = Sha256::new();
        let mut buffer = vec![0u8; 1 << 20];
        loop {
            let n = file.read(&mut buffer)?;
            if n == 0 {
                break;
            }
            hasher.update(&buffer[..n]);
        }
        Ok(hasher.finalize().iter().map(|byte| format!("{byte:02x}")).collect())
    })
    .await?
}

/// Handles everything the server reports. Runs until the server stops.
async fn receive_loop(
    mut events: mpsc::Receiver<ServerEventV2>,
    trusted: Arc<Mutex<HashMap<String, Instant>>>,
    inbox: PathBuf,
    observer: Arc<dyn Observer>,
) {
    let sessions: Sessions = Arc::default();
    while let Some(event) = events.recv().await {
        match event {
            ServerEventV2::PrepareUpload { session_id, info, cert_fingerprint, files, decision_tx, .. } => {
                let allowed = cert_fingerprint.as_deref().is_some_and(|fp| take_trust(&trusted, fp));
                if !allowed {
                    let _ = decision_tx.send(PrepareUploadDecisionV2::Decline);
                    observer.on_event(Event::Declined { sender: info.alias });
                    continue;
                }
                let total: u64 = files.values().map(|f| f.size).sum();
                let ids: HashSet<String> = files.keys().cloned().collect();
                let names = files.values().map(|f| f.file_name.clone()).collect();
                sessions.lock().unwrap().insert(
                    session_id.clone(),
                    Session {
                        sender: info.alias.clone(),
                        total_bytes: total,
                        finished_bytes: 0,
                        open_files: ids.len(),
                        failed: false,
                        ended: false,
                    },
                );
                let _ = decision_tx.send(PrepareUploadDecisionV2::Accept(ids));
                observer.on_event(Event::IncomingStarted {
                    session_id,
                    sender: info.alias,
                    file_names: names,
                    total_bytes: total,
                });
            }
            ServerEventV2::FileUpload { session_id, file, target_tx, .. } => {
                let path = unique_path(&inbox, &file.file_name);
                let (progress_tx, mut progress_rx) = mpsc::channel::<u64>(16);
                let (result_tx, result_rx) = oneshot::channel::<Result<(), String>>();
                if target_tx
                    .send(FileUploadTarget::Path { path: path.clone(), result_tx, progress_tx: Some(progress_tx) })
                    .is_err()
                {
                    continue;
                }
                let (base, total) = {
                    let map = sessions.lock().unwrap();
                    map.get(&session_id).map(|s| (s.finished_bytes, s.total_bytes)).unwrap_or((0, 0))
                };
                let progress_observer = observer.clone();
                let progress_session = session_id.clone();
                tokio::spawn(async move {
                    while let Some(written) = progress_rx.recv().await {
                        progress_observer.on_event(Event::Progress {
                            session_id: progress_session.clone(),
                            done_bytes: base + written,
                            total_bytes: total,
                        });
                    }
                });
                let observer = observer.clone();
                let sessions = sessions.clone();
                let size = file.size;
                tokio::spawn(async move {
                    let ok = matches!(result_rx.await, Ok(Ok(())));
                    {
                        let mut map = sessions.lock().unwrap();
                        if let Some(s) = map.get_mut(&session_id) {
                            s.open_files = s.open_files.saturating_sub(1);
                            if ok {
                                s.finished_bytes += size;
                            } else {
                                s.failed = true;
                            }
                        }
                    }
                    if !ok {
                        // A failed upload, for example a checksum mismatch, must not leave a damaged file behind.
                        let _ = std::fs::remove_file(&path);
                    }
                    if ok {
                        observer.on_event(Event::Progress {
                            session_id: session_id.clone(),
                            done_bytes: sessions.lock().unwrap().get(&session_id).map_or(0, |s| s.finished_bytes),
                            total_bytes: total,
                        });
                        observer.on_event(Event::FileReceived { session_id: session_id.clone(), path });
                    }
                    finish_if_done(&sessions, &session_id, observer.as_ref());
                });
            }
            ServerEventV2::SessionEnd { session_id, reason } => {
                if let Some(s) = sessions.lock().unwrap().get_mut(&session_id) {
                    s.ended = true;
                    s.failed |= reason != localsend::http::server::v2::SessionEndReasonV2::Finished;
                }
                finish_if_done(&sessions, &session_id, observer.as_ref());
            }
            _ => {}
        }
    }
}

/// Reports the end of a session: right away after a failure, otherwise once every file arrived and the sender is done.
fn finish_if_done(sessions: &Sessions, session_id: &str, observer: &dyn Observer) {
    let mut map = sessions.lock().unwrap();
    let Some(s) = map.get(session_id) else { return };
    // A failure ends the session at once: the sender gives up, so no end message follows.
    if s.failed || (s.ended && s.open_files == 0) {
        let ok = !s.failed;
        let _ = &s.sender;
        map.remove(session_id);
        drop(map);
        observer.on_event(Event::Finished { session_id: session_id.to_string(), ok });
    }
}

/// True when `fingerprint` starts with a prefix heard recently. The prefix is used up.
fn take_trust(trusted: &Mutex<HashMap<String, Instant>>, fingerprint: &str) -> bool {
    let fingerprint = fingerprint.to_ascii_uppercase();
    let mut map = trusted.lock().unwrap();
    map.retain(|_, heard| heard.elapsed() < TRUST_WINDOW);
    let hit = map.keys().find(|prefix| fingerprint.starts_with(prefix.as_str())).cloned();
    match hit {
        Some(prefix) => {
            // Kept until it expires: one session may upload several files.
            map.insert(prefix, Instant::now());
            true
        }
        None => false,
    }
}

/// A path inside `dir` for `name` that does not overwrite anything.
/// Only the last component of `name` is used, so a sender cannot write outside `dir`.
fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let clean = Path::new(name).file_name().and_then(|n| n.to_str()).filter(|n| !n.is_empty()).unwrap_or("file");
    let first = dir.join(clean);
    if !first.exists() {
        return first;
    }
    let (stem, ext) = match clean.rsplit_once('.') {
        Some((s, e)) if !s.is_empty() => (s.to_string(), format!(".{e}")),
        _ => (clean.to_string(), String::new()),
    };
    (1..)
        .map(|n| dir.join(format!("{stem} ({n}){ext}")))
        .find(|p| !p.exists())
        .expect("an unused name exists")
}

/// Convenience used by tests and the FFI layer to refuse nonsense early.
pub fn check_prefix(prefix: &str) -> Result<()> {
    if prefix.len() < 16 || !prefix.chars().all(|c| c.is_ascii_hexdigit()) {
        bail!("fingerprint prefix must be at least 16 hex characters");
    }
    Ok(())
}

#[cfg(test)]
mod unit {
    use super::*;

    #[test]
    fn path_cannot_escape_the_inbox() {
        let dir = tempfile::tempdir().unwrap();
        let p = unique_path(dir.path(), "../../etc/passwd");
        assert_eq!(p, dir.path().join("passwd"));
    }

    #[test]
    fn existing_names_get_a_number() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a.txt"), b"x").unwrap();
        assert_eq!(unique_path(dir.path(), "a.txt"), dir.path().join("a (1).txt"));
    }

    #[test]
    fn short_or_non_hex_prefixes_are_refused() {
        assert!(check_prefix("ABCD").is_err());
        assert!(check_prefix("ZZZZZZZZZZZZZZZZ").is_err());
        assert!(check_prefix("0123456789ABCDEF").is_ok());
    }
}
