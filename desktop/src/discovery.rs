//! Finds phones, decides which one is meant, and tells the interface. It is the desktop counterpart of
//! `NearbyDiscovery.kt` on Android, built on the same pairing engine of `flick-proto`.
//!
//! A PC makes no gesture. The phone does: its next Bluetooth frame has the gesture flag up, this PC hears it,
//! and the engine pairs this PC with that phone, so both sides start together.

use crate::bluetooth::Heard;
use crate::wifi::Hold;
use flick_core::link::{plan, LinkPlan};
use flick_core::{target_from, Node, Target};
use flick_proto::pairing::{Outcome, Pairing, PeerView};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, watch};

const TICK: Duration = Duration::from_millis(100);
/// How long to wait before asking a phone that did not answer again.
const DESCRIBE_RETRY: Duration = Duration::from_millis(1_500);
const DESCRIBE_TIMEOUT: Duration = Duration::from_secs(4);
/// The effect starts at most this long after the pairing, even when the phone has not answered yet.
const ASSOCIATE_AFTER: Duration = Duration::from_millis(1_200);
/// After this many failed answers the phone is probably on another network.
const UNREACHABLE_AFTER: u32 = 3;
/// The interface is told about the phones this often.
const PHONES_EVERY_TICKS: u32 = 3;
/// A phone that fails to answer this many times is not on a network this PC is on, so the PC joins the network the phone makes.
const JOIN_AFTER_FAILURES: u32 = 2;
/// Only a phone at least this strong, about three metres, is worth joining.
const JOIN_RSSI_DBM: f32 = -75.0;
/// After a failed join, wait this long before trying again.
const JOIN_RETRY: Duration = Duration::from_secs(3);
/// The PC returns to its own network when no phone has been heard for this long.
const LEAVE_AFTER: Duration = Duration::from_secs(12);

/// A phone as the interface shows it.
#[derive(Clone, Debug)]
pub struct Phone {
    pub id: String,
    pub name: String,
    /// True once the phone answered over the network, so a transfer can start.
    pub ready: bool,
    /// 1 when the phones touch, 0 at two metres or more.
    pub proximity: f32,
    pub target: Target,
}

pub enum Event {
    Phones(Vec<Phone>),
    /// This PC and the phone were paired. The effect plays, and a send starts if files are chosen.
    Associated(Phone),
    /// Something the user can fix, or an empty string to clear it.
    Hint(String),
}

#[derive(Default)]
struct Known {
    name: Option<String>,
    describing: bool,
    retry_at: Option<Instant>,
    failures: u32,
    paired_at: Option<Instant>,
    associated: bool,
}

/// Runs until `heard` is closed. `multi` says whether every phone nearby may send here, or only the closest.
pub async fn run(
    node: Arc<Node>,
    mut heard: mpsc::Receiver<Heard>,
    multi: watch::Receiver<bool>,
    hold: Arc<Hold>,
    events: mpsc::UnboundedSender<Event>,
) {
    let started = Instant::now();
    let mut pairing = Pairing::new();
    let mut known: HashMap<String, Known> = HashMap::new();
    let (answers_tx, mut answers_rx) = mpsc::unbounded_channel::<(String, Option<String>)>();
    let mut ticker = tokio::time::interval(TICK);
    let mut ticks = 0u32;
    let mut last_hint = String::new();
    let own_prefix = node.identity().prefix().ok();
    let (joins_tx, mut joins_rx) = mpsc::unbounded_channel::<Result<(), String>>();
    let mut joining = false;
    let mut next_join_at = Instant::now();
    let mut join_error = String::new();
    let mut empty_since: Option<Instant> = None;

    loop {
        tokio::select! {
            _ = ticker.tick() => {}
            frame = heard.recv() => {
                let Some(h) = frame else { break };
                pairing.on_frame(&h.frame, h.rssi, now_ms(started));
                continue;
            }
            Some(result) = joins_rx.recv() => {
                joining = false;
                match result {
                    Ok(()) => {
                        join_error.clear();
                        // The phone can be reached now. Ask again at once.
                        for entry in known.values_mut() {
                            entry.failures = 0;
                            entry.retry_at = None;
                        }
                    }
                    Err(message) => {
                        join_error = message;
                        next_join_at = Instant::now() + JOIN_RETRY;
                    }
                }
                continue;
            }
            Some((id, name)) = answers_rx.recv() => {
                if let Some(entry) = known.get_mut(&id) {
                    entry.describing = false;
                    match name {
                        Some(name) => {
                            entry.name = Some(name);
                            entry.failures = 0;
                        }
                        None => {
                            entry.failures += 1;
                            entry.retry_at = Some(Instant::now() + DESCRIBE_RETRY);
                        }
                    }
                }
                continue;
            }
        }

        let now = now_ms(started);
        let views = pairing.peers(now);
        known.retain(|id, _| views.iter().any(|v| &v.id == id));

        for view in &views {
            let entry = known.entry(view.id.clone()).or_default();
            let due = entry.retry_at.is_none_or(|at| Instant::now() >= at);
            if entry.name.is_none() && !entry.describing && due {
                entry.describing = true;
                ask(node.clone(), view, answers_tx.clone());
            }
        }

        trust_closest(&node, &views, *multi.borrow());

        // Two devices with no network between them meet in the network the phone makes.
        if let Some(own) = own_prefix {
            if let Some(closest) = views
                .iter()
                .max_by(|a, b| a.rssi_dbm.total_cmp(&b.rssi_dbm))
            {
                empty_since = None;
                let stuck = known
                    .get(&closest.id)
                    .is_some_and(|k| k.name.is_none() && k.failures >= JOIN_AFTER_FAILURES);
                if stuck
                    && !joining
                    && Instant::now() >= next_join_at
                    && closest.rssi_dbm >= JOIN_RSSI_DBM
                {
                    // A phone always makes the network, because this PC's prefix is the smaller. Another PC may be
                    // the smaller one, and then this PC makes it.
                    match plan(&own, &[], &closest.announcement) {
                        LinkPlan::JoinGroup(credentials) => {
                            joining = true;
                            let (hold, done) = (hold.clone(), joins_tx.clone());
                            tokio::spawn(async move {
                                let result =
                                    hold.join(&credentials.ssid, &credentials.passphrase).await;
                                let _ = done.send(result.map_err(|error| error.to_string()));
                            });
                        }
                        LinkPlan::CreateGroup(credentials) => {
                            joining = true;
                            let (hold, done) = (hold.clone(), joins_tx.clone());
                            tokio::spawn(async move {
                                let result =
                                    hold.host(&credentials.ssid, &credentials.passphrase).await;
                                let _ = done.send(result.map_err(|error| error.to_string()));
                            });
                        }
                        LinkPlan::SameNetwork => {}
                    }
                }
            } else {
                let since = *empty_since.get_or_insert_with(Instant::now);
                if since.elapsed() >= LEAVE_AFTER && hold.is_joined().await {
                    // No phone is near any more: this PC goes back to its own network.
                    hold.release().await;
                }
            }
        }

        let outcome = pairing.poll(now);
        let unreachable = views.iter().any(|v| {
            known
                .get(&v.id)
                .is_some_and(|k| k.failures >= UNREACHABLE_AFTER)
        });
        let hint = match &outcome {
            Outcome::Ambiguous => {
                "Two phones are about as close. Hold the phone nearer to this PC.".to_string()
            }
            _ if joining => "Setting up the private network between the two devices.".to_string(),
            _ if !join_error.is_empty() => {
                format!("The private network could not be set up: {join_error}")
            }
            Outcome::Pending if unreachable => {
                "A phone is near but cannot be reached. Hold it closer to this PC.".to_string()
            }
            _ => String::new(),
        };
        set_hint(&events, &mut last_hint, &hint);
        if let Outcome::Paired(view) = outcome {
            if let Some(entry) = known.get_mut(&view.id) {
                entry.paired_at.get_or_insert_with(Instant::now);
            }
        }

        for view in &views {
            let Some(entry) = known.get_mut(&view.id) else {
                continue;
            };
            let waited_long_enough = entry
                .paired_at
                .is_some_and(|at| at.elapsed() >= ASSOCIATE_AFTER);
            if entry.paired_at.is_some()
                && !entry.associated
                && (entry.name.is_some() || waited_long_enough)
            {
                entry.associated = true;
                let _ = events.send(Event::Associated(phone_of(view, entry)));
            }
        }

        ticks = ticks.wrapping_add(1);
        if ticks.is_multiple_of(PHONES_EVERY_TICKS) {
            let mut phones: Vec<Phone> = views
                .iter()
                .filter_map(|v| known.get(&v.id).map(|k| phone_of(v, k)))
                .collect();
            phones.sort_by(|a, b| b.proximity.total_cmp(&a.proximity));
            let _ = events.send(Event::Phones(phones));
        }
    }
}

fn now_ms(started: Instant) -> u64 {
    started.elapsed().as_millis() as u64
}

/// Asks a phone for its name. That is also the proof that it can be reached and that its certificate matches.
fn ask(node: Arc<Node>, view: &PeerView, answers: mpsc::UnboundedSender<(String, Option<String>)>) {
    let target = target_from(&view.announcement);
    let id = view.id.clone();
    tokio::spawn(async move {
        let name = tokio::time::timeout(DESCRIBE_TIMEOUT, node.describe(&target))
            .await
            .ok()
            .and_then(Result::ok);
        let _ = answers.send((id, name));
    });
}

/// Only the closest phone may send here, unless the user chose to share with several.
fn trust_closest(node: &Node, views: &[PeerView], multi: bool) {
    let mut ordered: Vec<&PeerView> = views.iter().collect();
    ordered.sort_by(|a, b| b.rssi_dbm.total_cmp(&a.rssi_dbm));
    let allowed: Vec<String> = if multi {
        ordered.iter().map(|v| v.id.clone()).collect()
    } else {
        ordered.first().map(|v| v.id.clone()).into_iter().collect()
    };
    node.set_trusted(&allowed);
}

fn phone_of(view: &PeerView, known: &Known) -> Phone {
    Phone {
        id: view.id.clone(),
        name: known
            .name
            .clone()
            .unwrap_or_else(|| "Nearby phone".to_string()),
        ready: known.name.is_some(),
        proximity: view.proximity,
        target: target_from(&view.announcement),
    }
}

fn set_hint(events: &mpsc::UnboundedSender<Event>, last: &mut String, hint: &str) {
    if last != hint {
        *last = hint.to_string();
        let _ = events.send(Event::Hint(hint.to_string()));
    }
}
