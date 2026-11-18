//! Makes this PC visible to phones: it advertises the same Bluetooth frame a phone does, so a phone knows
//! where to reach it. The Bluetooth library used for scanning cannot advertise, so each system has its own code.

use anyhow::Result;
use flick_proto::ble::BleFrame;

/// A running advertisement. It stops when dropped.
pub struct Advertiser {
    _inner: platform::Handle,
}

impl Advertiser {
    pub async fn start(frame: BleFrame) -> Result<Self> {
        Ok(Self {
            _inner: platform::start(frame).await?,
        })
    }
}

#[cfg(target_os = "linux")]
mod platform {
    use anyhow::Result;
    use bluer::adv::{Advertisement, AdvertisementHandle, Type};
    use flick_proto::ble::{BleFrame, MANUFACTURER_ID};
    use std::collections::BTreeMap;

    /// BlueZ keeps advertising while the handle and the session live.
    pub struct Handle {
        _session: bluer::Session,
        _advertisement: AdvertisementHandle,
    }

    pub async fn start(frame: BleFrame) -> Result<Handle> {
        let session = bluer::Session::new().await?;
        let adapter = session.default_adapter().await?;
        adapter.set_powered(true).await?;
        let advertisement = Advertisement {
            advertisement_type: Type::Peripheral,
            manufacturer_data: BTreeMap::from([(MANUFACTURER_ID, frame.encode().to_vec())]),
            discoverable: Some(true),
            ..Default::default()
        };
        let handle = adapter.advertise(advertisement).await?;
        Ok(Handle {
            _session: session,
            _advertisement: handle,
        })
    }
}

#[cfg(windows)]
mod platform {
    use anyhow::{anyhow, Result};
    use flick_proto::ble::{BleFrame, MANUFACTURER_ID};
    use std::sync::mpsc;
    use windows::Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisement, BluetoothLEAdvertisementPublisher, BluetoothLEManufacturerData,
    };
    use windows::Storage::Streams::DataWriter;

    /// The publisher lives on a thread of its own, so it never has to be moved between threads.
    /// Dropping the handle ends that thread, which stops the advertisement.
    pub struct Handle {
        _stop: mpsc::Sender<()>,
    }

    fn publish(frame: &BleFrame) -> windows::core::Result<BluetoothLEAdvertisementPublisher> {
        let writer = DataWriter::new()?;
        writer.WriteBytes(&frame.encode())?;
        let data = BluetoothLEManufacturerData::new()?;
        data.SetCompanyId(MANUFACTURER_ID)?;
        data.SetData(&writer.DetachBuffer()?)?;
        let advertisement = BluetoothLEAdvertisement::new()?;
        advertisement.ManufacturerData()?.Append(&data)?;
        let publisher = BluetoothLEAdvertisementPublisher::Create(&advertisement)?;
        publisher.Start()?;
        Ok(publisher)
    }

    pub async fn start(frame: BleFrame) -> Result<Handle> {
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let (ready_tx, ready_rx) = mpsc::channel::<Result<(), String>>();
        std::thread::spawn(move || match publish(&frame) {
            Ok(publisher) => {
                let _ = ready_tx.send(Ok(()));
                // Waits until the handle is dropped.
                let _ = stop_rx.recv();
                let _ = publisher.Stop();
            }
            Err(error) => {
                let _ = ready_tx.send(Err(error.to_string()));
            }
        });
        ready_rx
            .recv()
            .map_err(|_| anyhow!("the Bluetooth thread ended"))?
            .map_err(|message| anyhow!(message))?;
        Ok(Handle { _stop: stop_tx })
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod platform {
    use anyhow::{bail, Result};
    use flick_proto::ble::BleFrame;

    pub struct Handle;

    pub async fn start(_frame: BleFrame) -> Result<Handle> {
        bail!("Advertising over Bluetooth is available on Windows and Linux")
    }
}
