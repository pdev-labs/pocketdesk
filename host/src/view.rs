//! What one connection is looking at.
//!
//! Before this module there was a single global mode: the first phone that
//! asked for a second screen moved *every* connected phone onto the virtual
//! monitor, and switching back moved them all back. The mode belongs to the
//! phone, though, so each connection now owns a [`View`]:
//!
//! * **mirror** — the shared base capture (the physical monitor, or whatever
//!   `--output` selected). Any number of phones can mirror it and it still costs
//!   one screenshot per frame.
//! * **extend** — a private headless output, sized to that phone, with its own
//!   capture thread, settings and monitor geometry. Two extended phones are two
//!   separate monitors.
//!
//! The view also supplies the geometry the pointer is mapped through, so input
//! from one phone lands on the monitor that phone is watching.

use std::sync::{
    atomic::{AtomicBool, AtomicU32, Ordering},
    Arc, Mutex, RwLock,
};

use anyhow::{Context, Result};
use tokio::sync::broadcast;

use crate::{
    capture::{self, CaptureSettings, Frame, SharedFrame},
    layout::{self, Geometry},
    second_screen::VirtualDisplay,
    AppState,
};

pub struct View {
    pub settings: Arc<RwLock<CaptureSettings>>,
    pub geometry: Arc<RwLock<Geometry>>,
    pub last_frame: SharedFrame,
    pub fps_x10: Arc<AtomicU32>,
    /// Present only in extend mode; dropping it removes the output and stops
    /// the private capture thread.
    private: Option<Private>,
}

struct Private {
    /// Dropping it removes the headless output.
    _display: VirtualDisplay,
    stop: Arc<AtomicBool>,
}

impl Drop for Private {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

impl View {
    /// A view of the host's shared capture. This is what every connection starts
    /// with, and what `mode: "mirror"` returns to.
    pub fn mirror(state: &AppState) -> Self {
        Self {
            settings: state.settings.clone(),
            geometry: state.geometry.clone(),
            last_frame: state.last_frame.clone(),
            fps_x10: state.fps_x10.clone(),
            private: None,
        }
    }

    pub fn is_extended(&self) -> bool {
        self.private.is_some()
    }

    pub fn mode(&self) -> &'static str {
        if self.is_extended() {
            "extend"
        } else {
            "mirror"
        }
    }

    /// Size of the frame this view is receiving.
    pub fn size(&self) -> (u32, u32) {
        self.geometry
            .read()
            .map(|g| (g.captured.w.round() as u32, g.captured.h.round() as u32))
            .unwrap_or((0, 0))
    }

    /// The most recent frame, for late joiners and resumes.
    pub fn latest(&self) -> Option<Arc<Frame>> {
        self.last_frame
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// Switch this connection — and only this connection — to its own virtual
    /// monitor. Blocking: it talks to the compositor and waits for the output.
    ///
    /// The new display is built before the old one is dropped, so a failed
    /// request leaves the current stream untouched.
    pub fn extend(
        &mut self,
        state: &AppState,
        width: u32,
        height: u32,
    ) -> Result<broadcast::Receiver<Arc<Frame>>> {
        let index = state.next_display.fetch_add(1, Ordering::Relaxed);
        let name = format!("pocketdesk-phone-{index}");
        let display = VirtualDisplay::create_named(&name, width, height, true)
            .with_context(|| format!("creating {name}"))?;
        let output = display.name().to_string();

        let base = state
            .settings
            .read()
            .map(|s| s.clone())
            .unwrap_or_else(|e| e.into_inner().clone());
        let settings = Arc::new(RwLock::new(CaptureSettings {
            output: Some(output.clone()),
            // The virtual monitor is already phone-sized: do not downscale.
            scale: 1.0,
            ..base
        }));

        let (tx, receiver) = broadcast::channel(2);
        let last_frame: SharedFrame = Arc::new(Mutex::new(None));
        let fps_x10 = Arc::new(AtomicU32::new(
            settings
                .read()
                .map(|s| s.fps)
                .unwrap_or_else(|e| e.into_inner().fps)
                * 10,
        ));
        let stop = Arc::new(AtomicBool::new(false));

        capture::spawn(
            state.plan.clone(),
            settings.clone(),
            tx,
            fps_x10.clone(),
            last_frame.clone(),
            stop.clone(),
        )
        .context("starting the second-screen capture")?;

        let geometry = layout::detect(Some(&output)).unwrap_or_else(|err| {
            tracing::warn!("layout for {output} unavailable ({err:#}); using its size");
            Geometry::fallback(width as f64, height as f64)
        });
        tracing::info!(
            "phone switched to its own display: {output} · {}x{}",
            width,
            height
        );

        self.settings = settings;
        self.geometry = Arc::new(RwLock::new(geometry));
        self.last_frame = last_frame;
        self.fps_x10 = fps_x10;
        // Assigning drops the previous private view (output + thread), if any.
        self.private = Some(Private { _display: display, stop });
        Ok(receiver)
    }

    /// Back to the shared base capture for this connection only.
    pub fn mirror_again(
        &mut self,
        state: &AppState,
    ) -> broadcast::Receiver<Arc<Frame>> {
        let was_extended = self.is_extended();
        self.private = None;
        self.settings = state.settings.clone();
        self.geometry = state.geometry.clone();
        self.last_frame = state.last_frame.clone();
        self.fps_x10 = state.fps_x10.clone();
        if was_extended {
            tracing::info!("phone switched back to mirroring the base output");
        }
        state.frames.subscribe()
    }

    /// Re-read the layout this view maps input through. Cheap enough for the
    /// five-second stats tick, and it keeps a rotated phone or a hot-plugged
    /// monitor from desynchronising the pointer.
    pub fn refresh_layout(&self) {
        let output = self
            .settings
            .read()
            .map(|s| s.output.clone())
            .unwrap_or(None);
        match layout::detect(output.as_deref()) {
            Ok(fresh) => {
                let mut guard = self
                    .geometry
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                *guard = fresh;
            }
            Err(err) => tracing::debug!("monitor layout unavailable: {err:#}"),
        }
    }

    /// `{"t":"capture", …}` — the state the phone shows after a change.
    pub fn capture_json(&self) -> String {
        let settings = self
            .settings
            .read()
            .map(|s| s.clone())
            .unwrap_or_else(|e| e.into_inner().clone());
        let (width, height) = self.size();

        serde_json::json!({
            "t": "capture",
            "fps": settings.fps,
            "quality": settings.quality,
            "scale": settings.scale,
            "mode": self.mode(),
            "width": width,
            "height": height,
            "output": settings.output,
            "preset": settings.preset(),
        })
        .to_string()
    }
}
