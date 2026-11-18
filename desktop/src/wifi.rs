//! Joins the private network that a phone creates, or creates one for another PC, and gives the PC its own Wi-Fi back afterwards.
//!
//! A phone and a PC with no router between them meet in a network the phone creates (a Wi-Fi Direct group, which any
//! Wi-Fi card sees as an ordinary network with a name and a password). The name and the password are computed from the
//! fingerprints of both devices, so nothing has to be exchanged. The PC joins that network like any other, through
//! the tools of the system: NetworkManager on Linux and `netsh` on Windows.
//!
//! Two PCs have no phone to make the network, so one of them makes it: a hotspot with NetworkManager on Linux, a
//! Wi-Fi Direct group owner with legacy settings on Windows. Which one is decided by the same rule as for phones, the larger
//! fingerprint prefix creates, so both PCs agree without a word.
//!
//! Joining leaves the network the PC was on. The PC remembers it and returns to it when the phone goes away.

use anyhow::Result;
use tokio::sync::Mutex;

/// The private network this PC is in, whether it created it or joined it, and the connection to return to.
struct Joined {
    ssid: String,
    previous: Option<String>,
    /// Keeps the network alive while this PC hosts it.
    hosting: Option<platform::Hosting>,
}

#[derive(Default)]
pub struct Hold {
    joined: Mutex<Option<Joined>>,
}

impl Hold {
    pub async fn is_joined(&self) -> bool {
        self.joined.lock().await.is_some()
    }

    /// Joins the network that another device made. Does nothing when already in it.
    pub async fn join(&self, ssid: &str, passphrase: &str) -> Result<()> {
        let mut joined = self.joined.lock().await;
        if joined.as_ref().is_some_and(|j| j.ssid == ssid) {
            return Ok(());
        }
        let previous = platform::join(ssid, passphrase).await?;
        *joined = Some(Joined {
            ssid: ssid.to_string(),
            previous,
            hosting: None,
        });
        Ok(())
    }

    /// Creates the network that another device will join. Two PCs have no phone to make it, so one of them must.
    /// Does nothing when already hosting it.
    pub async fn host(&self, ssid: &str, passphrase: &str) -> Result<()> {
        let mut joined = self.joined.lock().await;
        if joined.as_ref().is_some_and(|j| j.ssid == ssid) {
            return Ok(());
        }
        let (previous, hosting) = platform::host(ssid, passphrase).await?;
        *joined = Some(Joined {
            ssid: ssid.to_string(),
            previous,
            hosting: Some(hosting),
        });
        Ok(())
    }

    /// Leaves the private network, or stops hosting it, and goes back to the network before. Safe to call when not in one.
    pub async fn release(&self) {
        if let Some(joined) = self.joined.lock().await.take() {
            platform::leave(&joined.ssid, joined.previous.as_deref(), joined.hosting).await;
        }
    }
}

#[cfg(target_os = "linux")]
mod platform {
    use crate::wifi_text::{active_wifi_connection, wifi_device};
    use anyhow::{bail, Context, Result};
    use std::time::Duration;
    use tokio::process::Command;

    async fn nmcli(args: &[&str]) -> Result<String> {
        let output = Command::new("nmcli")
            .args(args)
            .output()
            .await
            .context("NetworkManager (nmcli) is needed to join the phone's network")?;
        if !output.status.success() {
            bail!("{}", String::from_utf8_lossy(&output.stderr).trim());
        }
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    /// Joins the network and returns the Wi-Fi connection that was active before.
    pub async fn join(ssid: &str, passphrase: &str) -> Result<Option<String>> {
        let previous = nmcli(&["-t", "-f", "NAME,TYPE", "connection", "show", "--active"])
            .await
            .ok()
            .and_then(|output| active_wifi_connection(&output));
        // The network was created a moment ago. A new scan makes sure it is in the list.
        let _ = nmcli(&["device", "wifi", "rescan"]).await;
        tokio::time::sleep(Duration::from_secs(2)).await;
        nmcli(&[
            "-w", "25", "device", "wifi", "connect", ssid, "password", passphrase,
        ])
        .await?;
        Ok(previous)
    }

    /// Nothing to keep alive: NetworkManager runs the hotspot until it is told to stop.
    pub struct Hosting;

    /// Creates the network and returns the Wi-Fi connection that was active before.
    ///
    /// The 5 GHz band is tried first, on channels without radar sharing, because it is the fast one and the devices are close.
    /// A card that cannot host there is asked for the 2.4 GHz band.
    pub async fn host(ssid: &str, passphrase: &str) -> Result<(Option<String>, Hosting)> {
        let previous = nmcli(&["-t", "-f", "NAME,TYPE", "connection", "show", "--active"])
            .await
            .ok()
            .and_then(|output| active_wifi_connection(&output));
        let device = nmcli(&["-t", "-f", "DEVICE,TYPE", "device"])
            .await
            .ok()
            .and_then(|output| wifi_device(&output))
            .context("this PC has no Wi-Fi card")?;
        for (band, channel) in [("a", "149"), ("a", "36"), ("bg", "6")] {
            let made = nmcli(&[
                "device", "wifi", "hotspot", "ifname", &device, "con-name", ssid, "ssid", ssid,
                "band", band, "channel", channel, "password", passphrase,
            ])
            .await;
            if made.is_ok() {
                return Ok((previous, Hosting));
            }
        }
        bail!("the Wi-Fi card of this PC cannot create a network")
    }

    pub async fn leave(ssid: &str, previous: Option<&str>, _hosting: Option<Hosting>) {
        // The saved network holds the password. It is not kept. Deleting a hotspot also stops it.
        let _ = nmcli(&["connection", "delete", "id", ssid]).await;
        if let Some(previous) = previous {
            let _ = nmcli(&["connection", "up", "id", previous]).await;
        }
    }
}

#[cfg(windows)]
mod platform {
    use crate::wifi_text::{connected_profile, windows_profile_xml};
    use anyhow::{bail, Context, Result};
    use std::time::Duration;
    use tokio::process::Command;
    use windows::core::HSTRING;
    use windows::Devices::WiFiDirect::WiFiDirectAdvertisementPublisher;
    use windows::Security::Credentials::PasswordCredential;

    async fn netsh(args: &[String]) -> Result<String> {
        let mut command = Command::new("netsh");
        for arg in args {
            // netsh wants name="My network", with the quotes around the value only.
            command.raw_arg(arg);
        }
        let output = command
            .output()
            .await
            .context("the Windows Wi-Fi tool (netsh) did not start")?;
        if !output.status.success() {
            bail!("{}", String::from_utf8_lossy(&output.stdout).trim());
        }
        Ok(String::from_utf8_lossy(&output.stdout).into_owned())
    }

    fn args(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    pub async fn join(ssid: &str, passphrase: &str) -> Result<Option<String>> {
        let previous = netsh(&args(&["wlan", "show", "interfaces"]))
            .await
            .ok()
            .and_then(|output| connected_profile(&output));

        let file = std::env::temp_dir().join(format!("flick-{ssid}.xml"));
        std::fs::write(&file, windows_profile_xml(ssid, passphrase))
            .context("cannot write the network profile")?;
        let added = netsh(&args(&[
            "wlan",
            "add",
            "profile",
            &format!("filename=\"{}\"", file.display()),
            "user=current",
        ]))
        .await;
        // The password is in that file. It does not stay on the disk.
        let _ = std::fs::remove_file(&file);
        added?;

        netsh(&args(&[
            "wlan",
            "connect",
            &format!("name=\"{ssid}\""),
            &format!("ssid=\"{ssid}\""),
        ]))
        .await?;
        for _ in 0..40 {
            tokio::time::sleep(Duration::from_millis(500)).await;
            let state = netsh(&args(&["wlan", "show", "interfaces"]))
                .await
                .unwrap_or_default();
            if state.contains(ssid) {
                return Ok(previous);
            }
        }
        bail!("this PC could not join the network of the phone")
    }

    /// The network is hosted by a publisher that lives on a thread of its own, so it never moves between threads.
    /// Dropping this ends that thread, which stops the network.
    pub struct Hosting {
        _stop: std::sync::mpsc::Sender<()>,
    }

    fn publish(
        ssid: &str,
        passphrase: &str,
    ) -> windows::core::Result<WiFiDirectAdvertisementPublisher> {
        let publisher = WiFiDirectAdvertisementPublisher::new()?;
        let advertisement = publisher.Advertisement()?;
        // An autonomous group owner makes its network at once, and the legacy settings make it a network with a
        // name and a password that any Wi-Fi card can join, not only one that speaks Wi-Fi Direct.
        advertisement.SetIsAutonomousGroupOwnerEnabled(true)?;
        let legacy = advertisement.LegacySettings()?;
        legacy.SetIsEnabled(true)?;
        legacy.SetSsid(&HSTRING::from(ssid))?;
        let credential = PasswordCredential::new()?;
        credential.SetPassword(&HSTRING::from(passphrase))?;
        legacy.SetPassphrase(&credential)?;
        publisher.Start()?;
        Ok(publisher)
    }

    pub async fn host(ssid: &str, passphrase: &str) -> Result<(Option<String>, Hosting)> {
        let previous = netsh(&args(&["wlan", "show", "interfaces"]))
            .await
            .ok()
            .and_then(|output| connected_profile(&output));
        let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<(), String>>();
        let (ssid, passphrase) = (ssid.to_string(), passphrase.to_string());
        std::thread::spawn(move || match publish(&ssid, &passphrase) {
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
            .map_err(|_| anyhow::anyhow!("the network thread ended"))?
            .map_err(|message| {
                anyhow::anyhow!("the Wi-Fi card of this PC cannot create a network: {message}")
            })?;
        Ok((previous, Hosting { _stop: stop_tx }))
    }

    pub async fn leave(ssid: &str, previous: Option<&str>, hosting: Option<Hosting>) {
        // Stops the network if this PC was hosting it.
        drop(hosting);
        // Deleting the profile also drops the connection, and removes the saved password.
        let _ = netsh(&args(&[
            "wlan",
            "delete",
            "profile",
            &format!("name=\"{ssid}\""),
        ]))
        .await;
        if let Some(previous) = previous {
            let _ = netsh(&args(&["wlan", "connect", &format!("name=\"{previous}\"")])).await;
        }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod platform {
    use anyhow::{bail, Result};

    pub struct Hosting;

    pub async fn join(_ssid: &str, _passphrase: &str) -> Result<Option<String>> {
        bail!("Joining the network of another device is available on Windows and Linux")
    }

    pub async fn host(_ssid: &str, _passphrase: &str) -> Result<(Option<String>, Hosting)> {
        bail!("Creating a network is available on Windows and Linux")
    }

    pub async fn leave(_ssid: &str, _previous: Option<&str>, _hosting: Option<Hosting>) {}
}
