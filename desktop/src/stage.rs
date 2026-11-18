//! Puts the spotlight on the window and plays its moves. Everything here runs on the interface thread.
//!
//! The spotlight takes a snapshot of the window, blurs it more or less as the card appears or leaves, and moves the
//! card. The timing is in `spotlight.rs`, the blur is in `effect.rs`.

use crate::effect::Effect;
use crate::spotlight::{self, Pose};
use crate::MainWindow;
use slint::{ComponentHandle, Image, Rgba8Pixel, SharedPixelBuffer, TimerMode};
use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;
use std::time::{Duration, Instant};

/// How long a card that was just received stays before the spotlight closes.
const RECEIVED_STAYS: Duration = Duration::from_millis(1_800);

/// What the card shows.
#[derive(Clone)]
pub struct Card {
    pub title: String,
    pub detail: String,
    /// The big letters shown when the file has no picture, for example `PDF`.
    pub extension: String,
    /// A picture of the file, when it is an image.
    pub preview: Option<PathBuf>,
}

thread_local! {
    /// The snapshot of the window, kept while the spotlight is shown.
    static BACKDROP: RefCell<Option<Rc<Effect>>> = const { RefCell::new(None) };
    /// The timer that plays the current move. Slint timers belong to the interface thread.
    static TIMER: RefCell<Option<slint::Timer>> = const { RefCell::new(None) };
}

/// The chosen file appears, large, and waits to be grabbed.
pub fn show(ui: &MainWindow, card: &Card) {
    if open(ui, card) {
        let target = ui.as_weak();
        play(target, spotlight::APPEAR_MS, spotlight::appear, |ui| {
            ui.set_spot_waiting(true)
        });
    }
}

/// The hand closed: the card shrinks and slides away.
pub fn grab(ui: &MainWindow) {
    ui.set_spot_waiting(false);
    play(ui.as_weak(), spotlight::GRAB_MS, spotlight::grab, close);
}

/// The hand opened on this PC: the card comes back, stays a moment, then the spotlight closes.
pub fn release(ui: &MainWindow, card: &Card) {
    if open(ui, card) {
        play(
            ui.as_weak(),
            spotlight::RELEASE_MS,
            spotlight::release,
            |ui| {
                let weak = ui.as_weak();
                slint::Timer::single_shot(RECEIVED_STAYS, move || {
                    if let Some(ui) = weak.upgrade() {
                        close(&ui);
                    }
                });
            },
        );
    }
}

/// Closes the spotlight at once.
pub fn close(ui: &MainWindow) {
    TIMER.with(|slot| {
        if let Some(timer) = slot.borrow().as_ref() {
            timer.stop();
        }
    });
    ui.set_spot_active(false);
    ui.set_spot_waiting(false);
    BACKDROP.with(|slot| *slot.borrow_mut() = None);
}

/// Takes the snapshot of the window, without any spotlight in it, and puts the card in place.
fn open(ui: &MainWindow, card: &Card) -> bool {
    ui.set_spot_active(false);
    let Ok(snapshot) = ui.window().take_snapshot() else {
        return false;
    };
    let effect = Effect::new(
        snapshot.as_bytes(),
        snapshot.width() as usize,
        snapshot.height() as usize,
    );
    BACKDROP.with(|slot| *slot.borrow_mut() = Some(Rc::new(effect)));

    ui.set_spot_title(card.title.clone().into());
    ui.set_spot_detail(card.detail.clone().into());
    ui.set_spot_extension(card.extension.clone().into());
    let preview = card
        .preview
        .as_ref()
        .and_then(|path| Image::load_from_path(path).ok());
    ui.set_spot_has_preview(preview.is_some());
    if let Some(preview) = preview {
        ui.set_spot_preview(preview);
    }
    // The first picture is in place before the spotlight shows, so it never flashes.
    apply(
        ui,
        Pose {
            scale: 0.82,
            dx: 0.0,
            dy: 0.0,
            opacity: 0.0,
            blur: 0.0,
        },
    );
    ui.set_spot_active(true);
    true
}

fn apply(ui: &MainWindow, pose: Pose) {
    ui.set_spot_scale(pose.scale);
    ui.set_spot_dx(pose.dx);
    ui.set_spot_dy(pose.dy);
    ui.set_spot_opacity(pose.opacity);
    BACKDROP.with(|slot| {
        if let Some(effect) = slot.borrow().as_ref() {
            let (width, height) = effect.size();
            let mut buffer = SharedPixelBuffer::<Rgba8Pixel>::new(width as u32, height as u32);
            effect.frosted(pose.blur, buffer.make_mut_bytes());
            ui.set_spot_background(Image::from_rgba8(buffer));
        }
    });
}

/// Plays a move for `duration_ms`, then calls `done`.
fn play(
    weak: slint::Weak<MainWindow>,
    duration_ms: f32,
    pose: fn(f32) -> Pose,
    done: impl Fn(&MainWindow) + 'static,
) {
    let started = Instant::now();
    let timer = slint::Timer::default();
    timer.start(TimerMode::Repeated, Duration::from_millis(16), move || {
        let Some(ui) = weak.upgrade() else { return };
        let elapsed = started.elapsed().as_secs_f32() * 1000.0;
        apply(&ui, pose(elapsed));
        if elapsed >= duration_ms {
            TIMER.with(|slot| {
                if let Some(timer) = slot.borrow().as_ref() {
                    timer.stop();
                }
            });
            done(&ui);
        }
    });
    TIMER.with(|slot| *slot.borrow_mut() = Some(timer));
}
