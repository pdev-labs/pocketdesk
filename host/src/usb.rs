//! USB-cable transport.
//!
//! `adb reverse tcp:PORT tcp:PORT` makes the phone's `127.0.0.1:PORT` reach the
//! host's port over the USB cable. The app then connects to localhost and gets
//! the whole USB link instead of sharing Wi-Fi with the video stream.
//!
//! Everything here is best effort: no adb, no USB debugging, or no cable simply
//! means Wi-Fi keeps working as before. The host port still requires the pairing
//! token, so the forwarded port is not an unauthenticated hole.

use std::{process::{Command, Stdio}, time::Duration};

pub fn adb_available() -> bool {
    let Some(path) = std::env::var_os("PATH") else {
        return false;
    };
    std::env::split_paths(&path)
        .map(|dir| dir.join("adb"))
        .any(|candidate| candidate.is_file())
}

fn devices() -> Vec<String> {
    let Ok(output) = Command::new("adb").arg("devices").output() else {
        return Vec::new();
    };
    String::from_utf8_lossy(&output.stdout)
        .lines()
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next()?;
            (state == "device").then(|| serial.to_string())
        })
        .collect()
}

fn reverse(serial: &str, port: u16) -> bool {
    let forward = format!("tcp:{port}");
    Command::new("adb")
        .args(["-s", serial, "reverse", &forward, &forward])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}

/// Configure the reverse tunnel for every attached device. Returns how many
/// phones are ready for USB mode.
pub fn setup(port: u16) -> usize {
    if !adb_available() {
        return 0;
    }
    let mut ready = 0;
    for serial in devices() {
        if reverse(&serial, port) {
            tracing::debug!("USB: adb reverse tcp:{port} ready for {serial}");
            ready += 1;
        } else {
            tracing::debug!("USB: could not set adb reverse for {serial}");
        }
    }
    ready
}

/// Re-apply the tunnel when a phone is plugged in after the host started.
/// `adb reverse` is lost on unplug, so this also restores it on reconnect.
pub fn monitor(port: u16) {
    if !adb_available() {
        tracing::debug!("USB: adb not installed; cable mode will not be set up automatically");
        return;
    }

    tokio::spawn(async move {
        let mut last = usize::MAX;
        loop {
            let ready = tokio::task::spawn_blocking(move || setup(port))
                .await
                .unwrap_or(0);
            if ready != last {
                if ready > 0 {
                    tracing::info!(
                        "USB: adb reverse ready on {ready} device(s) — the app can use 127.0.0.1:{port}"
                    );
                } else if last != usize::MAX {
                    tracing::info!("USB: no adb device attached");
                }
                last = ready;
            }
            tokio::time::sleep(Duration::from_secs(10)).await;
        }
    });
}
