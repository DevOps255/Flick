//! The text that the system tools of Windows and Linux want and give, kept apart from the code that runs them so it can be tested.

#[cfg_attr(not(windows), allow(dead_code))]
/// The profile that makes Windows join a WPA2 network. `netsh wlan add profile` reads it.
pub fn windows_profile_xml(ssid: &str, passphrase: &str) -> String {
    format!(
        r#"<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
    <name>{ssid}</name>
    <SSIDConfig>
        <SSID>
            <name>{ssid}</name>
        </SSID>
    </SSIDConfig>
    <connectionType>ESS</connectionType>
    <connectionMode>manual</connectionMode>
    <MSM>
        <security>
            <authEncryption>
                <authentication>WPA2PSK</authentication>
                <encryption>AES</encryption>
                <useOneX>false</useOneX>
            </authEncryption>
            <sharedKey>
                <keyType>passPhrase</keyType>
                <protected>false</protected>
                <keyMaterial>{passphrase}</keyMaterial>
            </sharedKey>
        </security>
    </MSM>
</WLANProfile>
"#
    )
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
/// The name of the Wi-Fi connection that is active now, from the output of
/// `nmcli -t -f NAME,TYPE connection show --active`. Each line is `name:type`, and a colon inside the name is written `\:`.
pub fn active_wifi_connection(nmcli_output: &str) -> Option<String> {
    nmcli_output.lines().find_map(|line| {
        let (name, kind) = line.rsplit_once(':')?;
        (kind.trim() == "802-11-wireless").then(|| name.replace("\\:", ":"))
    })
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
/// The name of the first Wi-Fi card, from the output of `nmcli -t -f DEVICE,TYPE device`.
pub fn wifi_device(nmcli_output: &str) -> Option<String> {
    nmcli_output.lines().find_map(|line| {
        let (device, kind) = line.split_once(':')?;
        (kind.trim() == "wifi").then(|| device.to_string())
    })
}

#[cfg_attr(not(windows), allow(dead_code))]
/// The Wi-Fi profile in use now, from the output of `netsh wlan show interfaces`. The line is called "Profile"
/// in English, and has the same meaning in the other languages Windows is sold in.
pub fn connected_profile(netsh_output: &str) -> Option<String> {
    const LABELS: [&str; 6] = [
        "profile", "profil", "perfil", "profilo", "profiel", "profilu",
    ];
    netsh_output.lines().find_map(|line| {
        let (label, value) = line.split_once(':')?;
        let value = value.trim();
        (LABELS.contains(&label.trim().to_lowercase().as_str()) && !value.is_empty())
            .then(|| value.to_string())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_profile_names_the_network_and_holds_the_password() {
        let xml = windows_profile_xml("DIRECT-FK-ABCD1234", "0123456789ABCDEF");
        assert!(xml.contains("<name>DIRECT-FK-ABCD1234</name>"));
        assert!(xml.contains("<keyMaterial>0123456789ABCDEF</keyMaterial>"));
        assert!(xml.contains("WPA2PSK"));
    }

    #[test]
    fn finds_the_active_wifi_connection() {
        let output =
            "Wired connection 1:802-3-ethernet\nHome\\: Kara:802-11-wireless\nlo:loopback\n";
        assert_eq!(
            active_wifi_connection(output),
            Some("Home: Kara".to_string())
        );
    }

    #[test]
    fn no_active_wifi_connection() {
        assert_eq!(
            active_wifi_connection("Wired connection 1:802-3-ethernet\n"),
            None
        );
    }

    #[test]
    fn finds_the_wifi_card() {
        let output = "eth0:ethernet\nwlp3s0:wifi\nlo:loopback\n";
        assert_eq!(wifi_device(output), Some("wlp3s0".to_string()));
        assert_eq!(wifi_device("eth0:ethernet\n"), None);
    }

    #[test]
    fn finds_the_profile_in_english_and_french_output() {
        let english = "    Name                   : Wi-Fi\n    State                  : connected\n    Profile                : Campus\n";
        let french = "    Nom                    : Wi-Fi\n    Profil                 : Maison\n";
        assert_eq!(connected_profile(english), Some("Campus".to_string()));
        assert_eq!(connected_profile(french), Some("Maison".to_string()));
    }

    #[test]
    fn a_disconnected_adapter_has_no_profile() {
        assert_eq!(
            connected_profile("    Name : Wi-Fi\n    State : disconnected\n"),
            None
        );
    }
}
