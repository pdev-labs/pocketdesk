//! Monitor layout discovery.
//!
//! Absolute pointer devices (uinput) address the *whole desktop*, while a frame
//! only covers *one* monitor. We therefore keep two rectangles: the bounding box
//! of every monitor, and the rectangle of the monitor being streamed. An
//! incoming normalized coordinate (0..1 inside the frame) is mapped into the
//! desktop, then scaled to the 0..32767 ABS_X/ABS_Y range.

use anyhow::{bail, Context, Result};
use serde::Deserialize;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Rect {
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

impl Rect {
    pub fn right(&self) -> f64 {
        self.x + self.w
    }

    pub fn bottom(&self) -> f64 {
        self.y + self.h
    }
}

#[derive(Clone, Debug)]
pub struct Geometry {
    /// Bounding box of every connected monitor, in logical pixels.
    pub layout: Rect,
    /// Rectangle of the monitor we are streaming.
    pub captured: Rect,
    /// Where the numbers came from, for the logs.
    pub source: String,
}

impl Geometry {
    /// Assume a single monitor with the frame's own dimensions.
    pub fn fallback(w: f64, h: f64) -> Self {
        let rect = Rect {
            x: 0.0,
            y: 0.0,
            w: w.max(1.0),
            h: h.max(1.0),
        };
        Self {
            layout: rect,
            captured: rect,
            source: "fallback (single monitor)".to_string(),
        }
    }

    /// Map a normalized frame coordinate to the absolute 0..32767 axis range.
    pub fn map(&self, nx: f64, ny: f64) -> (i32, i32) {
        let nx = nx.clamp(0.0, 1.0);
        let ny = ny.clamp(0.0, 1.0);

        let desktop_x = self.captured.x + nx * self.captured.w;
        let desktop_y = self.captured.y + ny * self.captured.h;

        let fx = if self.layout.w > 0.0 {
            (desktop_x - self.layout.x) / self.layout.w
        } else {
            nx
        };
        let fy = if self.layout.h > 0.0 {
            (desktop_y - self.layout.y) / self.layout.h
        } else {
            ny
        };

        (
            (fx * 32767.0).round().clamp(0.0, 32767.0) as i32,
            (fy * 32767.0).round().clamp(0.0, 32767.0) as i32,
        )
    }
}

#[derive(Deserialize)]
struct Monitor {
    name: String,
    x: f64,
    y: f64,
    width: f64,
    height: f64,
    #[serde(default)]
    focused: bool,
}

/// Ask Hyprland for the monitor layout.
pub fn detect(output: Option<&str>) -> Result<Geometry> {
    let out = std::process::Command::new("hyprctl")
        .args(["-j", "monitors"])
        .output()
        .context("running hyprctl (is Hyprland running?)")?;

    if !out.status.success() {
        bail!("hyprctl exited with {}", out.status);
    }

    let monitors: Vec<Monitor> =
        serde_json::from_slice(&out.stdout).context("parsing hyprctl monitors JSON")?;

    if monitors.is_empty() {
        bail!("hyprctl reported no monitors");
    }

    let layout = monitors.iter().fold(
        Rect {
            x: f64::INFINITY,
            y: f64::INFINITY,
            w: 0.0,
            h: 0.0,
        },
        |acc, m| {
            let min_x = acc.x.min(m.x);
            let min_y = acc.y.min(m.y);
            let max_x = acc.right().max(m.right());
            let max_y = acc.bottom().max(m.bottom());
            Rect {
                x: min_x,
                y: min_y,
                w: max_x - min_x,
                h: max_y - min_y,
            }
        },
    );

    let chosen = output
        .and_then(|o| monitors.iter().find(|m| m.name.eq_ignore_ascii_case(o)))
        .or_else(|| monitors.iter().find(|m| m.focused))
        .or_else(|| monitors.first())
        .expect("monitors is not empty");

    Ok(Geometry {
        layout,
        captured: Rect {
            x: chosen.x,
            y: chosen.y,
            w: chosen.width,
            h: chosen.height,
        },
        source: format!(
            "hyprctl:{} ({:.0}x{:.0} at {:.0},{:.0})",
            chosen.name, chosen.width, chosen.height, chosen.x, chosen.y
        ),
    })
}

impl Monitor {
    fn right(&self) -> f64 {
        self.x + self.width
    }

    fn bottom(&self) -> f64 {
        self.y + self.height
    }
}
