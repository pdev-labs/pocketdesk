//! A virtual monitor, so the phone can be a *second* screen rather than a copy.
//!
//! On Hyprland, `hyprctl output create headless <name>` adds a real output with
//! its own workspaces. We size it to the phone's aspect ratio, park it to the
//! right of the physical desktop, and capture it with grim. Pointer input then
//! maps into that monitor's rectangle like any other screen (see `layout.rs`).
//!
//! Hyprland 0.5x parses config with Lua (`hyprctl eval`), older builds use
//! `hyprctl keyword`; both paths are tried.

use std::{
    process::Command,
    thread::sleep,
    time::{Duration, Instant},
};

use anyhow::{bail, Context, Result};
use serde::Deserialize;

/// The output name is ours, so a crashed run leaves something we can reuse.
pub const OUTPUT_NAME: &str = "pocketdesk-phone";

pub struct VirtualDisplay {
    /// Output name, e.g. `pocketdesk-phone-2` for a second phone.
    name: String,
    pub width: u32,
    pub height: u32,
    /// True when a phone asked for it and it should go away when that phone
    /// disconnects. `--extend` at startup sets this to false.
    pub on_demand: bool,
    present: bool,
}

impl VirtualDisplay {
    /// The shared virtual monitor used by `--extend`.
    pub fn create(requested_w: u32, requested_h: u32, on_demand: bool) -> Result<Self> {
        Self::create_named(OUTPUT_NAME, requested_w, requested_h, on_demand)
    }

    /// Create (or resize) a headless output and wait until the compositor
    /// reports it at the requested size.
    ///
    /// Every phone that asks for a second screen gets its own name, so two
    /// extended phones are two independent monitors rather than one shared one.
    pub fn create_named(
        name: &str,
        requested_w: u32,
        requested_h: u32,
        on_demand: bool,
    ) -> Result<Self> {
        let width = even(requested_w.clamp(480, 3840));
        let height = even(requested_h.clamp(320, 2160));

        match run(&["output", "create", "headless", name]) {
            Ok(_) => {}
            Err(err) => {
                // A leftover output from a crashed run is fine: reuse it.
                if monitors().is_err() {
                    bail!(
                        "second-screen mode needs Hyprland (`hyprctl` not usable: {err:#}). \
                         Mirroring still works everywhere."
                    );
                }
            }
        }

        apply_mode(name, width, height)?;

        let deadline = Instant::now() + Duration::from_secs(4);
        loop {
            let found = monitors()?
                .into_iter()
                .find(|m| m.name.eq_ignore_ascii_case(name));

            if let Some(monitor) = found {
                if monitor.width >= width && monitor.height >= height {
                    break;
                }
            }
            if Instant::now() >= deadline {
                bail!(
                    "the virtual monitor did not come up in time — check \
                     `hyprctl monitors` for {name}"
                );
            }
            sleep(Duration::from_millis(120));
        }

        tracing::info!("virtual monitor {name} ready: {width}x{height} (second screen)");
        Ok(Self {
            name: name.to_string(),
            width,
            height,
            on_demand,
            present: true,
        })
    }

    /// The output name, for `grim -o` and `layout::detect`.
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Remove the output. Safe to call twice.
    pub fn hide(&mut self) {
        if !self.present {
            return;
        }
        self.present = false;
        match run(&["output", "remove", &self.name]) {
            Ok(_) => tracing::info!("virtual monitor {} removed", self.name),
            Err(err) => tracing::warn!("could not remove {}: {err:#}", self.name),
        }
    }
}

impl Drop for VirtualDisplay {
    fn drop(&mut self) {
        self.hide();
    }
}

fn apply_mode(name: &str, width: u32, height: u32) -> Result<()> {
    let mode = format!("{width}x{height}");

    // Hyprland 0.5x+ (Lua config).
    let lua =
        format!(r#"hl.monitor({{output="{name}", mode="{mode}", position="auto-right", scale=1}})"#);
    if run(&["eval", &lua]).is_ok() {
        return Ok(());
    }

    // Older Hyprland (legacy config parser).
    match run(&["keyword", "monitor", &format!("{name},{mode},auto-right,1")]) {
        Ok(_) => Ok(()),
        Err(err) => bail!("could not size {name}: {err:#}"),
    }
}

fn run(args: &[&str]) -> Result<String> {
    let out = Command::new("hyprctl")
        .args(args)
        .output()
        .context("running hyprctl")?;

    let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();

    if !out.status.success() {
        bail!(
            "hyprctl {} failed: {}",
            args.first().copied().unwrap_or(""),
            stderr.lines().next().unwrap_or("unknown error")
        );
    }
    if stderr.starts_with("error") {
        bail!("hyprctl {}: {stderr}", args.first().copied().unwrap_or(""));
    }
    Ok(stdout)
}

#[derive(Deserialize)]
struct Monitor {
    name: String,
    width: u32,
    height: u32,
}

fn monitors() -> Result<Vec<Monitor>> {
    let out = Command::new("hyprctl")
        .args(["-j", "monitors"])
        .output()
        .context("running hyprctl -j monitors")?;
    if !out.status.success() {
        bail!("hyprctl -j monitors exited with {}", out.status);
    }
    serde_json::from_slice(&out.stdout).context("parsing hyprctl monitors JSON")
}

fn even(value: u32) -> u32 {
    value - (value % 2)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_stay_even_and_sane() {
        assert_eq!(even(1281), 1280);
        assert_eq!(even(720), 720);
        assert_eq!(even(1), 0);
    }
}
