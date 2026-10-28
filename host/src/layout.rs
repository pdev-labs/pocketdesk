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

        // Guard against a degenerate layout: a non-finite ratio would put every
        // touch in the same place (the kernel then drops the duplicate value).
        let fx = if self.layout.w.is_finite() && self.layout.w > 0.0 {
            ((desktop_x - self.layout.x) / self.layout.w).clamp(0.0, 1.0)
        } else {
            nx
        };
        let fy = if self.layout.h.is_finite() && self.layout.h > 0.0 {
            ((desktop_y - self.layout.y) / self.layout.h).clamp(0.0, 1.0)
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

    let layout = bounding_box(&monitors);

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

/// One rectangle covering every monitor, including negative coordinates (a
/// screen placed to the left of the primary one).
fn bounding_box(monitors: &[Monitor]) -> Rect {
    let mut min_x = f64::INFINITY;
    let mut min_y = f64::INFINITY;
    let mut max_x = f64::NEG_INFINITY;
    let mut max_y = f64::NEG_INFINITY;

    for monitor in monitors {
        min_x = min_x.min(monitor.x);
        min_y = min_y.min(monitor.y);
        max_x = max_x.max(monitor.right());
        max_y = max_y.max(monitor.bottom());
    }

    if !min_x.is_finite() || !max_x.is_finite() || !min_y.is_finite() || !max_y.is_finite() {
        return Rect::default();
    }

    Rect {
        x: min_x,
        y: min_y,
        w: (max_x - min_x).max(1.0),
        h: (max_y - min_y).max(1.0),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dual_monitor() -> Geometry {
        Geometry {
            layout: Rect {
                x: 0.0,
                y: 0.0,
                w: 3200.0,
                h: 1080.0,
            },
            captured: Rect {
                x: 1920.0,
                y: 0.0,
                w: 1280.0,
                h: 1080.0,
            },
            source: "test".into(),
        }
    }

    #[test]
    fn maps_into_second_monitor() {
        let geometry = dual_monitor();
        // Top-left of the streamed screen is 1920px into a 3200px desktop.
        let (x, y) = geometry.map(0.0, 0.0);
        assert_eq!((x, y), (19660, 0));

        let (x, y) = geometry.map(1.0, 1.0);
        assert_eq!((x, y), (32767, 32767));
    }

    #[test]
    fn clamps_out_of_range_input() {
        let geometry = dual_monitor();
        assert_eq!(geometry.map(-3.0, 9.0), geometry.map(0.0, 1.0));
    }

    fn monitors_from_json(json: &str) -> Vec<Monitor> {
        serde_json::from_str(json).expect("monitor json")
    }

    #[test]
    fn bounding_box_uses_real_edges() {
        let monitors = monitors_from_json(
            r#"[
                {"name":"eDP-1","x":0,"y":0,"width":1366,"height":768,"focused":true},
                {"name":"pocketdesk-phone","x":1366,"y":0,"width":1600,"height":716,"focused":false}
            ]"#,
        );
        let layout = bounding_box(&monitors);
        assert_eq!(layout.w, 2966.0);
        assert_eq!(layout.h, 768.0);
        assert_eq!(layout.x, 0.0);
    }

    #[test]
    fn bounding_box_handles_screens_left_of_origin() {
        let monitors = monitors_from_json(
            r#"[
                {"name":"DP-1","x":0,"y":0,"width":1920,"height":1080},
                {"name":"HDMI-A-1","x":-1280,"y":0,"width":1280,"height":1024}
            ]"#,
        );
        let layout = bounding_box(&monitors);
        assert_eq!(layout.x, -1280.0);
        assert_eq!(layout.w, 3200.0);
        assert_eq!(layout.h, 1080.0);
    }

    #[test]
    fn maps_a_point_on_the_virtual_monitor() {
        // The exact geometry the host built while a phone was extended.
        let geometry = Geometry {
            layout: Rect {
                x: 0.0,
                y: 0.0,
                w: 2966.0,
                h: 768.0,
            },
            captured: Rect {
                x: 1366.0,
                y: 0.0,
                w: 1600.0,
                h: 716.0,
            },
            source: "test".into(),
        };
        // Centre of the phone screen is 1366+800 = 2166 px into a 2966 px desktop.
        let (x, y) = geometry.map(0.5, 0.5);
        assert_eq!(x, ((2166.0_f64 / 2966.0) * 32767.0).round() as i32);
        assert_eq!(y, ((358.0_f64 / 768.0) * 32767.0).round() as i32);
        assert!(x > 20_000, "must not collapse to the origin: {x}");
    }

    #[test]
    fn degenerate_layout_does_not_collapse_to_the_origin() {
        let mut geometry = Geometry::fallback(1366.0, 768.0);
        geometry.layout.w = f64::INFINITY;
        geometry.layout.h = f64::INFINITY;
        assert_eq!(geometry.map(0.5, 0.5), (16384, 16384));
    }

    #[test]
    fn single_monitor_center() {
        let geometry = Geometry::fallback(1366.0, 768.0);
        assert_eq!(geometry.map(0.5, 0.5), (16384, 16384));
    }
}
