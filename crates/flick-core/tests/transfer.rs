//! End to end tests: two nodes on this machine exchange files over real TLS.

use flick_core::{target_from, Event, Identity, Node, Observer, OutgoingFile, Target};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio_util::sync::CancellationToken;

#[derive(Default)]
struct Recorder(Mutex<Vec<Event>>);

impl Observer for Recorder {
    fn on_event(&self, event: Event) {
        self.0.lock().unwrap().push(event);
    }
}

impl Recorder {
    fn events(&self) -> Vec<Event> {
        self.0.lock().unwrap().clone()
    }

    async fn wait_for(&self, what: impl Fn(&Event) -> bool) -> Event {
        for _ in 0..100 {
            if let Some(e) = self.events().into_iter().find(|e| what(e)) {
                return e;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        panic!("event never arrived, got {:?}", self.events());
    }
}

struct Device {
    node: Node,
    seen: Arc<Recorder>,
    inbox: tempfile::TempDir,
}

async fn device(name: &str) -> Device {
    let seen = Arc::new(Recorder::default());
    let inbox = tempfile::tempdir().unwrap();
    let node = Node::start(Identity::generate().unwrap(), name.into(), inbox.path().into(), 0, seen.clone())
        .await
        .unwrap();
    Device { node, seen, inbox }
}

fn target_of(receiver: &Device) -> Target {
    Target {
        host: "127.0.0.1".into(),
        port: receiver.node.port(),
        fingerprint_prefix: receiver.node.identity().fingerprint[..16].to_string(),
    }
}

fn file_of(size: usize, name: &str, dir: &tempfile::TempDir) -> OutgoingFile {
    let data: Vec<u8> = (0..size).map(|i| (i * 31 % 251) as u8).collect();
    let path = dir.path().join(format!("stored-under-another-name-{name}"));
    std::fs::write(&path, data).unwrap();
    OutgoingFile { path, name: name.to_string(), size: size as u64, sha256: None }
}

#[tokio::test]
async fn heard_sender_delivers_files_intact() {
    let sender = device("Sender").await;
    let receiver = device("Receiver").await;
    receiver.node.trust(&sender.node.identity().fingerprint[..16]);

    let source = tempfile::tempdir().unwrap();
    let big = file_of(5_000_000, "holiday.bin", &source);
    let small = file_of(1_234, "note.txt", &source);

    let last = Arc::new(AtomicU64::new(0));
    let monotonic = Arc::new(Mutex::new(true));
    let (l, m) = (last.clone(), monotonic.clone());
    sender
        .node
        .send(
            &target_of(&receiver),
            &[big.clone(), small.clone()],
            move |done, total| {
                assert_eq!(total, 5_001_234);
                if done < l.swap(done, Ordering::SeqCst) {
                    *m.lock().unwrap() = false;
                }
            },
            CancellationToken::new(),
        )
        .await
        .expect("send succeeds");

    assert!(*monotonic.lock().unwrap(), "sender progress never goes backwards");
    assert_eq!(last.load(Ordering::SeqCst), 5_001_234);

    let finished = receiver.seen.wait_for(|e| matches!(e, Event::Finished { .. })).await;
    assert!(matches!(finished, Event::Finished { ok: true, .. }));
    assert_eq!(std::fs::read(receiver.inbox.path().join("holiday.bin")).unwrap(), std::fs::read(&big.path).unwrap());
    assert_eq!(std::fs::read(receiver.inbox.path().join("note.txt")).unwrap(), std::fs::read(&small.path).unwrap());

    let events = receiver.seen.events();
    assert!(matches!(events.first(), Some(Event::IncomingStarted { total_bytes: 5_001_234, .. })));
    let progress: Vec<u64> = events
        .iter()
        .filter_map(|e| if let Event::Progress { done_bytes, .. } = e { Some(*done_bytes) } else { None })
        .collect();
    assert!(progress.windows(2).all(|w| w[0] <= w[1]), "receiver progress never goes backwards");
    assert_eq!(progress.last(), Some(&5_001_234));
}

#[tokio::test]
async fn unknown_sender_is_declined_and_nothing_is_written() {
    let sender = device("Stranger").await;
    let receiver = device("Receiver").await;
    let source = tempfile::tempdir().unwrap();
    let file = file_of(10_000, "spam.bin", &source);

    let result = sender.node.send(&target_of(&receiver), &[file], |_, _| {}, CancellationToken::new()).await;

    assert!(result.is_err());
    receiver.seen.wait_for(|e| matches!(e, Event::Declined { .. })).await;
    assert_eq!(std::fs::read_dir(receiver.inbox.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn wrong_certificate_is_rejected_before_any_data_is_sent() {
    let sender = device("Sender").await;
    let receiver = device("Receiver").await;
    receiver.node.trust(&sender.node.identity().fingerprint[..16]);
    let source = tempfile::tempdir().unwrap();
    let file = file_of(10_000, "secret.bin", &source);

    let mut target = target_of(&receiver);
    target.fingerprint_prefix = "0000000000000000".into();
    let result = sender.node.send(&target, &[file], |_, _| {}, CancellationToken::new()).await;

    assert!(result.is_err());
    assert!(receiver.seen.events().is_empty(), "the receiver never saw a request");
    assert_eq!(std::fs::read_dir(receiver.inbox.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn announcement_leads_to_a_working_target() {
    let receiver = device("Receiver").await;
    let heard = flick_core::announcement_for(receiver.node.identity(), [127, 0, 0, 1], receiver.node.port(), [1, 2, 3, 4])
        .unwrap();
    let target = target_from(&heard);
    assert_eq!(target.host, "127.0.0.1");
    assert_eq!(target.port, receiver.node.port());
    assert_eq!(target.fingerprint_prefix, receiver.node.identity().fingerprint[..16]);
}

#[tokio::test]
async fn sender_can_read_the_receivers_name() {
    let sender = device("Sender").await;
    let receiver = device("Alice's phone").await;
    assert_eq!(sender.node.describe(&target_of(&receiver)).await.unwrap(), "Alice's phone");

    let mut wrong = target_of(&receiver);
    wrong.fingerprint_prefix = "0000000000000000".into();
    assert!(sender.node.describe(&wrong).await.is_err());
}

#[tokio::test]
async fn damaged_file_is_rejected_and_nothing_is_kept() {
    let sender = device("Sender").await;
    let receiver = device("Receiver").await;
    receiver.node.trust(&sender.node.identity().fingerprint[..16]);
    let source = tempfile::tempdir().unwrap();
    let mut file = file_of(200_000, "photo.jpg", &source);
    // The sender claims a checksum that does not match the bytes it sends.
    file.sha256 = Some("0".repeat(64));

    let result = sender.node.send(&target_of(&receiver), &[file], |_, _| {}, CancellationToken::new()).await;

    assert!(result.is_err(), "the transfer must fail");
    let finished = receiver.seen.wait_for(|e| matches!(e, Event::Finished { .. })).await;
    assert!(matches!(finished, Event::Finished { ok: false, .. }));
    assert!(!receiver.seen.events().iter().any(|e| matches!(e, Event::FileReceived { .. })));
    assert_eq!(std::fs::read_dir(receiver.inbox.path()).unwrap().count(), 0, "no damaged file is left");
}
