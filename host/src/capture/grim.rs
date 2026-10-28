//! Wayland capture through `grim`.
//!
//! wlroots compositors (Hyprland, Sway, river, ...) do not expose a screen
//! grabber ffmpeg can use, so we ask grim for one JPEG per frame. On a modest
//! 1366x768 panel a frame takes ~80 ms end to end, which comfortably drives
//! 10-15 fps at ~1-2 Mbit/s.

use std::{
    process::{Command, Stdio},
    sync::Arc,
};

use anyhow::{bail, Context, Result};

use crate::capture::{jpeg_frame, Capture, CaptureSettings, Frame};

pub struct Grim {
    output: Option<String>,
    scale: Option<f64>,
    quality: u8,
    cursor: bool,
    last: Arc<std::sync::Mutex<(u32, u32)>>,
}

impl Grim {
    pub fn new(settings: &CaptureSettings) -> Result<Self> {
        let available = Command::new("grim")
            .arg("-h")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok();

        if !available {
            bail!(
                "`grim` not found. Install it (`sudo pacman -S grim`, `apt install grim`, \
                 or your distro's package) or run with --capture ffmpeg / --capture test."
            );
        }

        Ok(Self {
            output: settings.output.clone(),
            scale: if (settings.scale - 1.0).abs() > 0.001 {
                Some(settings.scale)
            } else {
                None
            },
            quality: settings.quality,
            cursor: settings.cursor,
            last: Arc::new(std::sync::Mutex::new((0, 0))),
        })
    }
}

impl Capture for Grim {
    fn next_frame(&mut self) -> Result<Frame> {
        let mut cmd = Command::new("grim");
        cmd.arg("-t").arg("jpeg").arg("-q").arg(self.quality.to_string());

        if let Some(output) = &self.output {
            cmd.arg("-o").arg(output);
        }
        if let Some(scale) = self.scale {
            cmd.arg("-s").arg(format!("{scale}"));
        }
        if self.cursor {
            cmd.arg("-c");
        }
        cmd.arg("-");
        cmd.stderr(Stdio::piped());

        let out = cmd.output().context("running grim")?;
        if !out.status.success() {
            let err = String::from_utf8_lossy(&out.stderr);
            bail!("grim failed: {}", err.trim());
        }
        if out.stdout.is_empty() {
            bail!("grim returned an empty frame");
        }

        let frame = jpeg_frame(out.stdout)?;
        *self.last.lock().unwrap_or_else(|e| e.into_inner()) = (frame.w, frame.h);
        Ok(frame)
    }

    fn hint_size(&self) -> (u32, u32) {
        *self.last.lock().unwrap_or_else(|e| e.into_inner())
    }
}
