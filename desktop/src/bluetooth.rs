//! Listens for the Bluetooth frames of Flick phones.

use anyhow::{anyhow, Result};
use btleplug::api::{Central, CentralEvent, Manager as _, Peripheral as _, ScanFilter};
use btleplug::platform::Manager;
use flick_proto::ble::{BleFrame, MANUFACTURER_ID};
use futures::StreamExt;
use tokio::sync::mpsc;

/// One frame heard over the air, with the signal strength it arrived with.
pub struct Heard {
    pub frame: BleFrame,
    /// In dBm. A value closer to zero is a closer phone.
    pub rssi: i32,
}

/// Scans until the receiver is dropped or the adapter goes away, and sends every Flick frame it hears.
pub async fn listen(out: mpsc::Sender<Heard>) -> Result<()> {
    let manager = Manager::new().await?;
    let adapter = manager
        .adapters()
        .await?
        .into_iter()
        .next()
        .ok_or_else(|| {
            anyhow!("No Bluetooth adapter found. Switch Bluetooth on, or plug in a Bluetooth key.")
        })?;

    let mut events = adapter.events().await?;
    adapter.start_scan(ScanFilter::default()).await?;

    while let Some(event) = events.next().await {
        let CentralEvent::ManufacturerDataAdvertisement {
            id,
            manufacturer_data,
        } = event
        else {
            continue;
        };
        let Some(frame) = manufacturer_data
            .get(&MANUFACTURER_ID)
            .and_then(|bytes| BleFrame::decode(bytes))
        else {
            continue;
        };
        let Some(rssi) = adapter
            .peripheral(&id)
            .await?
            .properties()
            .await?
            .and_then(|p| p.rssi)
        else {
            continue;
        };
        if out
            .send(Heard {
                frame,
                rssi: i32::from(rssi),
            })
            .await
            .is_err()
        {
            break;
        }
    }
    Ok(())
}
