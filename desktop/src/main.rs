//! Flick for Windows and Linux. The phone makes the gesture, this PC hears it over Bluetooth, and the
//! files travel over the shared Wi-Fi, with the same pairing engine and the same transfer core as the phone.

mod advertise;
mod airdrop;
mod bluetooth;
mod discovery;
mod effect;
mod identity;
mod spotlight;
mod stage;
mod wifi;
mod wifi_text;

slint::include_modules!();

use anyhow::{Context, Result};
use discovery::{Event, Phone};
use flick_core::{announcement_for, Identity, Node, Observer, OutgoingFile};
use flick_proto::ble::BleFrame;
use slint::{ComponentHandle, Image, ModelRc, Rgba8Pixel, SharedPixelBuffer, VecModel, Weak};
use std::cell::RefCell;
use std::net::IpAddr;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::runtime::Handle;
use tokio::sync::{mpsc, watch};
use tokio_util::sync::CancellationToken;

/// How long after an effect ends before another may start.
const EFFECT_COOLDOWN: Duration = Duration::from_millis(1_500);
const MAX_RECENTS: usize = 30;

#[derive(Clone)]
struct Picked {
    path: PathBuf,
    name: String,
    size: u64,
}

#[derive(Clone)]
struct Recent {
    name: String,
    detail: String,
    path: PathBuf,
}

#[derive(Default)]
struct State {
    files: Vec<Picked>,
    phones: Vec<Phone>,
    recents: Vec<Recent>,
    cancel: Option<CancellationToken>,
    sending: bool,
    effect_free_at: Option<Instant>,
    /// The file grabbed on this PC, waiting to be released on another one.
    holding: Option<stage::Card>,
}

type Shared = Arc<Mutex<State>>;

thread_local! {
    /// The timer that drives the effect. Slint timers belong to the interface thread.
    static EFFECT_TIMER: RefCell<Option<slint::Timer>> = const { RefCell::new(None) };
}

fn main() -> Result<()> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    let handle = runtime.handle().clone();

    let config = dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("flick");
    let inbox = dirs::download_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Flick");
    std::fs::create_dir_all(&config).context("cannot create the settings folder")?;

    let identity = identity::load_or_create(&config)?;
    let alias = load_name(&config);
    let profile_id = format!("flk#{}", &flick_core_prefix(&identity)[..5]);

    let shared: Shared = Shared::default();
    let window = MainWindow::new()?;
    let observer = Arc::new(UiObserver {
        weak: window.as_weak(),
        shared: shared.clone(),
    });
    let node =
        Arc::new(runtime.block_on(Node::start(identity, alias.clone(), inbox, 0, observer))?);

    window.set_profile_initial(
        alias
            .chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .unwrap_or_else(|| "F".to_string())
            .into(),
    );
    window.set_profile_name(alias.into());
    window.set_profile_id(profile_id.into());

    // Bluetooth: listen for phones, and make this PC visible to them.
    let (heard_tx, heard_rx) = mpsc::channel(256);
    let (events_tx, events_rx) = mpsc::unbounded_channel::<Event>();
    let (multi_tx, multi_rx) = watch::channel(false);
    handle.spawn(listen_forever(heard_tx, events_tx.clone()));
    handle.spawn(advertise_forever(node.clone(), events_tx.clone()));
    let hold = Arc::new(wifi::Hold::default());
    handle.spawn(discovery::run(
        node.clone(),
        heard_rx,
        multi_rx,
        hold.clone(),
        events_tx,
    ));
    handle.spawn(pump_events(
        events_rx,
        handle.clone(),
        node.clone(),
        shared.clone(),
        window.as_weak(),
    ));

    wire(&window, &handle, &node, &shared, multi_tx, &config);
    window.run()?;
    // Leaving the window returns this PC to its own network, if it had joined the network of a phone.
    runtime.block_on(hold.release());
    Ok(())
}

// ---------------------------------------------------------------- interface callbacks

fn wire(
    window: &MainWindow,
    handle: &Handle,
    node: &Arc<Node>,
    shared: &Shared,
    multi: watch::Sender<bool>,
    config: &Path,
) {
    window.on_choose_files({
        let (handle, shared, weak) = (handle.clone(), shared.clone(), window.as_weak());
        move || {
            let (shared, weak) = (shared.clone(), weak.clone());
            handle.spawn(async move {
                let Some(picked) = rfd::AsyncFileDialog::new()
                    .set_title("Choose files to send")
                    .pick_files()
                    .await
                else {
                    return;
                };
                let files: Vec<Picked> = picked
                    .iter()
                    .filter_map(|file| {
                        let path = file.path().to_path_buf();
                        let size = std::fs::metadata(&path).ok()?.len();
                        Some(Picked {
                            name: file.file_name(),
                            path,
                            size,
                        })
                    })
                    .collect();
                let card = card_for(&files);
                shared.lock().unwrap().files = files;
                refresh(&weak, &shared);
                // The chosen file is shown large, with the rest of the window blurred.
                if let Some(card) = card {
                    let _ = weak.upgrade_in_event_loop(move |ui| stage::show(&ui, &card));
                }
            });
        }
    });

    window.on_remove_file({
        let (shared, weak) = (shared.clone(), window.as_weak());
        move |index| {
            {
                let mut state = shared.lock().unwrap();
                if (index as usize) < state.files.len() {
                    state.files.remove(index as usize);
                }
            }
            refresh(&weak, &shared);
        }
    });

    window.on_send_to({
        let (handle, node, shared, weak) = (
            handle.clone(),
            node.clone(),
            shared.clone(),
            window.as_weak(),
        );
        move |index| {
            let phone = shared.lock().unwrap().phones.get(index as usize).cloned();
            if let Some(phone) = phone {
                start_send(&handle, &node, &shared, &weak, phone);
            }
        }
    });

    window.on_open_recent({
        let shared = shared.clone();
        move |index| {
            let path = shared
                .lock()
                .unwrap()
                .recents
                .get(index as usize)
                .map(|r| r.path.clone());
            if let Some(path) = path {
                let _ = open::that_detached(path);
            }
        }
    });

    window.on_remove_recent({
        let (shared, weak) = (shared.clone(), window.as_weak());
        move |index| {
            {
                let mut state = shared.lock().unwrap();
                if (index as usize) < state.recents.len() {
                    state.recents.remove(index as usize);
                }
            }
            refresh(&weak, &shared);
        }
    });

    window.on_grab({
        let (shared, weak) = (shared.clone(), window.as_weak());
        move || {
            let Some(ui) = weak.upgrade() else { return };
            let card = card_for(&shared.lock().unwrap().files);
            shared.lock().unwrap().holding = card;
            ui.set_holding(true);
            stage::grab(&ui);
        }
    });

    window.on_release({
        let (shared, weak) = (shared.clone(), window.as_weak());
        move || {
            let Some(ui) = weak.upgrade() else { return };
            let held = shared.lock().unwrap().holding.take();
            ui.set_holding(false);
            if let Some(card) = held {
                stage::release(&ui, &card);
            }
        }
    });

    window.on_dismiss_spotlight({
        let weak = window.as_weak();
        move || {
            if let Some(ui) = weak.upgrade() {
                stage::close(&ui);
            }
        }
    });

    window.on_multi_changed(move |on| {
        let _ = multi.send(on);
    });

    window.on_cancel_transfer({
        let shared = shared.clone();
        move || {
            if let Some(cancel) = shared.lock().unwrap().cancel.clone() {
                cancel.cancel();
            }
        }
    });

    window.on_dismiss_card({
        let weak = window.as_weak();
        move || {
            if let Some(ui) = weak.upgrade() {
                ui.set_card_visible(false);
            }
        }
    });

    window.on_save_name({
        let config = config.to_path_buf();
        move |name| {
            let name = name.trim();
            if !name.is_empty() {
                let _ = std::fs::write(config.join("name.txt"), name);
            }
        }
    });
}

// ---------------------------------------------------------------- showing state

/// Puts the chosen files, the phones and the recent files on the screen.
fn refresh(weak: &Weak<MainWindow>, shared: &Shared) {
    let (files, phones, recents) = {
        let state = shared.lock().unwrap();
        (
            state.files.clone(),
            state.phones.clone(),
            state.recents.clone(),
        )
    };
    let _ = weak.upgrade_in_event_loop(move |ui| {
        let file_items: Vec<FileItem> = files
            .iter()
            .map(|f| FileItem {
                name: f.name.clone().into(),
                size: format_bytes(f.size).into(),
            })
            .collect();
        let phone_items: Vec<PhoneItem> = phones.iter().map(phone_item).collect();
        let recent_items: Vec<RecentItem> = recents
            .iter()
            .map(|r| RecentItem {
                name: r.name.clone().into(),
                detail: r.detail.clone().into(),
            })
            .collect();
        ui.set_files(ModelRc::new(VecModel::from(file_items)));
        ui.set_phones(ModelRc::new(VecModel::from(phone_items)));
        ui.set_recents(ModelRc::new(VecModel::from(recent_items)));
    });
}

fn phone_item(phone: &Phone) -> PhoneItem {
    const COLORS: [(u8, u8, u8); 6] = [
        (0xFF, 0x8A, 0x3D),
        (0x22, 0xC7, 0xB8),
        (0xF2, 0x65, 0x5A),
        (0xE5, 0x48, 0x6F),
        (0x3B, 0x82, 0xF6),
        (0x8B, 0x5C, 0xF6),
    ];
    let hash = phone.name.bytes().fold(0usize, |acc, b| {
        acc.wrapping_mul(31).wrapping_add(b as usize)
    });
    let (r, g, b) = COLORS[hash % COLORS.len()];
    PhoneItem {
        id: phone.id.clone().into(),
        name: phone.name.clone().into(),
        initials: phone
            .name
            .chars()
            .next()
            .map(|c| c.to_uppercase().to_string())
            .unwrap_or_default()
            .into(),
        status: (if phone.ready { "Ready" } else { "Connecting" }).into(),
        ready: phone.ready,
        proximity: phone.proximity,
        color: slint::Color::from_rgb_u8(r, g, b),
    }
}

#[allow(clippy::too_many_arguments)]
fn show_card(
    weak: &Weak<MainWindow>,
    title: String,
    detail: String,
    progress: f32,
    finished: bool,
    ok: bool,
    can_cancel: bool,
) {
    let _ = weak.upgrade_in_event_loop(move |ui| {
        ui.set_card_title(title.into());
        ui.set_card_detail(detail.into());
        ui.set_card_progress(progress);
        ui.set_card_finished(finished);
        ui.set_card_ok(ok);
        ui.set_card_can_cancel(can_cancel);
        ui.set_card_visible(true);
    });
}

// ---------------------------------------------------------------- events from the phones

async fn pump_events(
    mut events: mpsc::UnboundedReceiver<Event>,
    handle: Handle,
    node: Arc<Node>,
    shared: Shared,
    weak: Weak<MainWindow>,
) {
    while let Some(event) = events.recv().await {
        match event {
            Event::Phones(phones) => {
                shared.lock().unwrap().phones = phones;
                refresh(&weak, &shared);
            }
            Event::Hint(hint) => {
                let _ = weak.upgrade_in_event_loop(move |ui| ui.set_hint(hint.into()));
            }
            Event::Associated(phone) => {
                let files = shared.lock().unwrap().files.clone();
                if files.is_empty() {
                    play_effect(
                        &shared,
                        &weak,
                        format!("Connected to {}", phone.name),
                        "Ready to receive".to_string(),
                    );
                } else {
                    let title = match files.len() {
                        1 => files[0].name.clone(),
                        n => format!("{} and {} more", files[0].name, n - 1),
                    };
                    play_effect(&shared, &weak, title, format!("Sending to {}", phone.name));
                    // The files were chosen before the phones met: meeting is the whole gesture.
                    start_send(&handle, &node, &shared, &weak, phone);
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the effect

fn play_effect(shared: &Shared, weak: &Weak<MainWindow>, title: String, subtitle: String) {
    {
        let mut state = shared.lock().unwrap();
        // A run in progress, or one that just ended, is not started again.
        if state
            .effect_free_at
            .is_some_and(|free_at| Instant::now() < free_at)
        {
            return;
        }
        state.effect_free_at = Some(
            Instant::now() + Duration::from_millis(airdrop::TOTAL_MS as u64) + EFFECT_COOLDOWN,
        );
    }
    let weak = weak.clone();
    let shared = shared.clone();
    let _ = weak.clone().upgrade_in_event_loop(move |ui| {
        ui.set_fx_title(title.into());
        ui.set_fx_subtitle(subtitle.into());
        // The file card is in the picture for the instant of the snapshot, so the wave crosses it too.
        ui.set_fx_card(true);
        let snapshot = ui.window().take_snapshot();
        ui.set_fx_card(false);
        let Ok(snapshot) = snapshot else {
            shared.lock().unwrap().effect_free_at = None;
            return;
        };
        let effect = Rc::new(effect::Effect::new(
            snapshot.as_bytes(),
            snapshot.width() as usize,
            snapshot.height() as usize,
        ));
        let (width, height) = effect.size();
        let draw = move |ui: &MainWindow, elapsed_ms: f32| {
            let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(width as u32, height as u32);
            effect.render(&airdrop::frame(elapsed_ms), buffer.make_mut_bytes());
            ui.set_fx_image(Image::from_rgba8(buffer));
        };
        draw(&ui, 0.0);
        ui.set_fx_active(true);

        let started = Instant::now();
        let timer = slint::Timer::default();
        timer.start(
            slint::TimerMode::Repeated,
            Duration::from_millis(16),
            move || {
                let Some(ui) = weak.upgrade() else { return };
                let elapsed = started.elapsed().as_secs_f32() * 1000.0;
                if elapsed >= airdrop::TOTAL_MS {
                    ui.set_fx_active(false);
                    EFFECT_TIMER.with(|slot| {
                        if let Some(timer) = slot.borrow().as_ref() {
                            timer.stop();
                        }
                    });
                } else {
                    draw(&ui, elapsed);
                }
            },
        );
        EFFECT_TIMER.with(|slot| *slot.borrow_mut() = Some(timer));
    });
}

// ---------------------------------------------------------------- sending

fn start_send(
    handle: &Handle,
    node: &Arc<Node>,
    shared: &Shared,
    weak: &Weak<MainWindow>,
    phone: Phone,
) {
    let cancel = CancellationToken::new();
    let files = {
        let mut state = shared.lock().unwrap();
        if state.sending || state.files.is_empty() {
            return;
        }
        state.sending = true;
        state.cancel = Some(cancel.clone());
        state.files.clone()
    };
    let total: u64 = files.iter().map(|f| f.size).sum();
    show_card(
        weak,
        format!("Sending to {}", phone.name),
        format!("{} files, {}", files.len(), format_bytes(total)),
        0.0,
        false,
        false,
        true,
    );

    let (node, shared, weak) = (node.clone(), shared.clone(), weak.clone());
    handle.spawn(async move {
        let outgoing: Vec<OutgoingFile> = files
            .iter()
            .map(|f| OutgoingFile::from_path(&f.path, f.name.clone(), f.size))
            .collect();
        let last_percent = Arc::new(AtomicU32::new(u32::MAX));
        let progress_weak = weak.clone();
        let result = node
            .send(
                &phone.target,
                &outgoing,
                move |done, all| {
                    let progress = if all == 0 {
                        0.0
                    } else {
                        done as f32 / all as f32
                    };
                    // A transfer reports often. The screen needs a change of one percent to move.
                    let percent = (progress * 100.0) as u32;
                    if last_percent.swap(percent, Ordering::Relaxed) != percent {
                        let _ = progress_weak
                            .upgrade_in_event_loop(move |ui| ui.set_card_progress(progress));
                    }
                },
                cancel,
            )
            .await;

        {
            let mut state = shared.lock().unwrap();
            state.sending = false;
            state.cancel = None;
            if result.is_ok() {
                for file in &files {
                    state.recents.insert(
                        0,
                        Recent {
                            name: file.name.clone(),
                            detail: format!("To {} · {}", phone.name, format_bytes(file.size)),
                            path: file.path.clone(),
                        },
                    );
                }
                state.recents.truncate(MAX_RECENTS);
                state.files.clear();
            }
        }
        match result {
            Ok(()) => show_card(
                &weak,
                format!("Sent to {}", phone.name),
                format!("{} files", files.len()),
                1.0,
                true,
                true,
                false,
            ),
            Err(error) => show_card(
                &weak,
                "The transfer stopped".to_string(),
                error.to_string(),
                0.0,
                true,
                false,
                false,
            ),
        }
        refresh(&weak, &shared);
    });
}

// ---------------------------------------------------------------- receiving

/// Shows what the node receives. Files land in the Downloads folder, inside `Flick`.
struct UiObserver {
    weak: Weak<MainWindow>,
    shared: Shared,
}

impl Observer for UiObserver {
    fn on_event(&self, event: flick_core::Event) {
        use flick_core::Event as Node;
        match event {
            Node::IncomingStarted {
                sender,
                file_names,
                total_bytes,
                ..
            } => {
                let detail = match file_names.len() {
                    1 => format!("{}, {}", file_names[0], format_bytes(total_bytes)),
                    n => format!("{n} files, {}", format_bytes(total_bytes)),
                };
                show_card(
                    &self.weak,
                    format!("Receiving from {sender}"),
                    detail,
                    0.0,
                    false,
                    false,
                    false,
                );
            }
            Node::Progress {
                done_bytes,
                total_bytes,
                ..
            } => {
                let progress = if total_bytes == 0 {
                    0.0
                } else {
                    done_bytes as f32 / total_bytes as f32
                };
                let _ = self
                    .weak
                    .upgrade_in_event_loop(move |ui| ui.set_card_progress(progress));
            }
            Node::FileReceived { path, .. } => {
                let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
                let name = path
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default();
                {
                    let mut state = self.shared.lock().unwrap();
                    state.recents.insert(
                        0,
                        Recent {
                            name,
                            detail: format!("Received · {}", format_bytes(size)),
                            path,
                        },
                    );
                    state.recents.truncate(MAX_RECENTS);
                }
                refresh(&self.weak, &self.shared);
            }
            Node::Finished { ok, .. } => {
                let (title, detail) = if ok {
                    (
                        "Received".to_string(),
                        "Saved in Downloads/Flick".to_string(),
                    )
                } else {
                    (
                        "The transfer stopped".to_string(),
                        "Nothing was lost on this PC, try again.".to_string(),
                    )
                };
                show_card(
                    &self.weak,
                    title,
                    detail,
                    if ok { 1.0 } else { 0.0 },
                    true,
                    ok,
                    false,
                );
            }
            Node::Declined { .. } => {}
        }
    }
}

// ---------------------------------------------------------------- Bluetooth tasks

/// Listens for phones, and tries again later when Bluetooth is off or comes back.
async fn listen_forever(out: mpsc::Sender<bluetooth::Heard>, events: mpsc::UnboundedSender<Event>) {
    loop {
        if let Err(error) = bluetooth::listen(out.clone()).await {
            let _ = events.send(Event::Hint(error.to_string()));
        }
        if out.is_closed() {
            return;
        }
        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}

/// Keeps this PC advertised with its current address, and again when the network changes.
async fn advertise_forever(node: Arc<Node>, events: mpsc::UnboundedSender<Event>) {
    // With no address yet the frame carries zeros. The phone still sees this PC, and creates the network it will join.
    let mut current: Option<([u8; 4], advertise::Advertiser)> = None;
    loop {
        let address = local_ipv4().unwrap_or([0; 4]);
        if current.as_ref().map(|(ip, _)| *ip) != Some(address) {
            current = None;
            let nonce = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.subsec_nanos())
                .unwrap_or(0)
                .to_le_bytes();
            if let Some(announcement) =
                announcement_for(node.identity(), address, node.port(), nonce)
            {
                let frame = BleFrame {
                    announcement,
                    gesture: false,
                    gesture_seq: 0,
                };
                match advertise::Advertiser::start(frame).await {
                    Ok(advertiser) => current = Some((address, advertiser)),
                    Err(error) => {
                        let _ = events.send(Event::Hint(format!(
                            "This PC cannot be seen by phones: {error}"
                        )));
                    }
                }
            }
        }
        // The address changes the moment this PC joins the network of a phone, so it is checked often.
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

/// The address of this PC on the network it uses to reach the outside. No packet is sent: connecting a UDP
/// socket only makes the system choose a route.
fn local_ipv4() -> Option<[u8; 4]> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(ip) if !ip.is_loopback() => Some(ip.octets()),
        _ => None,
    }
}

// ---------------------------------------------------------------- identity and name

fn load_name(dir: &Path) -> String {
    if let Ok(name) = std::fs::read_to_string(dir.join("name.txt")) {
        if !name.trim().is_empty() {
            return name.trim().to_string();
        }
    }
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .or_else(|_| std::fs::read_to_string("/etc/hostname").map(|h| h.trim().to_string()))
        .unwrap_or_else(|_| "Flick PC".to_string())
}

fn flick_core_prefix(identity: &Identity) -> String {
    identity
        .prefix()
        .map(|p| p.iter().map(|b| format!("{b:02X}")).collect())
        .unwrap_or_else(|_| "00000000".to_string())
}

/// What the spotlight shows for the chosen files.
fn card_for(files: &[Picked]) -> Option<stage::Card> {
    let first = files.first()?;
    if files.len() > 1 {
        let total: u64 = files.iter().map(|f| f.size).sum();
        return Some(stage::Card {
            title: format!("{} files", files.len()),
            detail: format_bytes(total),
            extension: files.len().to_string(),
            preview: None,
        });
    }
    let extension = first
        .path
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase());
    let is_image = matches!(extension.as_deref(), Some("png" | "jpg" | "jpeg"));
    Some(stage::Card {
        title: first.name.clone(),
        detail: format_bytes(first.size),
        extension: extension
            .map(|e| e.to_uppercase())
            .unwrap_or_else(|| "FILE".to_string()),
        preview: is_image.then(|| first.path.clone()),
    })
}

fn format_bytes(bytes: u64) -> String {
    const UNITS: [&str; 4] = ["B", "KB", "MB", "GB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}
