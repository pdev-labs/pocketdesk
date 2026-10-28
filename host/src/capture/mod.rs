//! Screen capture backends.
//!
//! Every backend produces complete JPEG frames, which keeps the phone side
//! simple (one decoder works everywhere) and avoids the licensing/bandwidth
//! complexity of an H.264 pipeline.
//!
//! A single capture thread feeds a broadcast channel, so five connected phones
//! still cost exactly one screenshot per frame. The thread watches
//! [`CaptureSettings`]; changing fps/quality/scale/output from the phone tears
//! down the backend and rebuilds it with the new arguments.

pub mod ffmpeg;
pub mod grim;
pub mod test_pattern;

use std::{
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc, RwLock,
    },
    time::{Duration, Instant},
};

use anyhow::{bail, Result};
use bytes::Bytes;
use tokio::sync::broadcast;

use crate::config::{Backend, Config};

/// Live, user-adjustable capture settings.
///
/// This is the single source of truth for "what the phone sees". The phone can
/// change any field at any time; the capture thread notices the change on its
/// next frame and rebuilds the backend.
#[derive(Clone, Debug, PartialEq)]
pub struct CaptureSettings {
    pub fps: u32,
    pub quality: u8,
    pub scale: f64,
    /// Monitor to grab. `None` means "the focused monitor".
    pub output: Option<String>,
    pub cursor: bool,
}

impl CaptureSettings {
    pub fn from_config(cfg: &Config) -> Self {
        Self {
            fps: cfg.fps.clamp(1, 60),
            quality: cfg.quality.clamp(10, 95),
            scale: cfg.scale.clamp(0.25, 1.0),
            output: cfg.output.clone(),
            cursor: !cfg.no_cursor,
        }
    }

    /// Merge a partial update from a client, clamping everything to sane bounds.
    pub fn merge(self, fps: Option<u32>, quality: Option<u8>, scale: Option<f64>) -> Self {
        Self {
            fps: fps.map(|value| value.clamp(1, 60)).unwrap_or(self.fps),
            quality: quality
                .map(|value| value.clamp(10, 95))
                .unwrap_or(self.quality),
            scale: scale
                .map(|value| value.clamp(0.25, 1.0))
                .unwrap_or(self.scale),
            ..self
        }
    }

    /// The preset name the phone shows next to the sliders.
    pub fn preset(&self) -> &'static str {
        match (self.fps, self.quality) {
            (..=11, ..=50) => "low",
            (20.., 78..) => "sharp",
            (25.., ..) => "smooth",
            _ => "balanced",
        }
    }
}

/// What backend to use. Everything else lives in [`CaptureSettings`].
#[derive(Clone, Debug)]
pub struct CapturePlan {
    pub backend: Backend,
}

pub struct Frame {
    pub jpeg: Bytes,
    pub w: u32,
    pub h: u32,
}

pub struct CaptureInfo {
    pub backend: &'static str,
    pub width: u32,
    pub height: u32,
}

/// The most recent frame, shared with the server so a phone that connects (or
/// resumes) while the screen is static still gets a picture immediately.
pub type SharedFrame = Arc<std::sync::Mutex<Option<Arc<Frame>>>>;

pub trait Capture: Send {
    fn next_frame(&mut self) -> Result<Frame>;
    /// Frame size before the first frame arrives, if the backend knows it.
    #[allow(dead_code)]
    fn hint_size(&self) -> (u32, u32) {
        (0, 0)
    }
}

/// Pick a concrete backend for this session.
pub fn resolve(requested: Backend) -> Backend {
    if requested != Backend::Auto {
        return requested;
    }

    if cfg!(target_os = "linux")
        && std::env::var_os("WAYLAND_DISPLAY").is_some()
        && has_command("grim")
    {
        return Backend::Grim;
    }
    if has_command("ffmpeg") {
        return Backend::Ffmpeg;
    }
    Backend::Test
}

fn has_command(cmd: &str) -> bool {
    std::process::Command::new(cmd)
        .arg("-h")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .is_ok()
}

pub fn spawn(
    plan: CapturePlan,
    settings: Arc<RwLock<CaptureSettings>>,
    tx: broadcast::Sender<Arc<Frame>>,
    fps_x10: Arc<AtomicU32>,
    latest: SharedFrame,
) -> Result<CaptureInfo> {
    let backend_name = match plan.backend {
        Backend::Grim => "grim (wayland)",
        Backend::Ffmpeg => "ffmpeg",
        Backend::Test => "test pattern",
        Backend::Auto => unreachable!("resolve() returns a concrete backend"),
    };

    let info = CaptureInfo {
        backend: backend_name,
        width: 0,
        height: 0,
    };

    std::thread::Builder::new()
        .name("pocketdesk-capture".to_string())
        .spawn(move || capture_loop(plan, settings, tx, fps_x10, latest))
        .map_err(|e| anyhow::anyhow!("spawning capture thread: {e}"))?;

    Ok(info)
}

fn make_capture(plan: &CapturePlan, settings: &CaptureSettings) -> Result<Box<dyn Capture>> {
    match plan.backend {
        Backend::Grim => Ok(Box::new(grim::Grim::new(settings)?)),
        Backend::Ffmpeg => Ok(Box::new(ffmpeg::Ffmpeg::new(settings)?)),
        Backend::Test => Ok(Box::new(test_pattern::TestPattern::new(settings))),
        Backend::Auto => unreachable!("plan.backend is concrete"),
    }
}

fn capture_loop(
    plan: CapturePlan,
    settings: Arc<RwLock<CaptureSettings>>,
    tx: broadcast::Sender<Arc<Frame>>,
    fps_x10: Arc<AtomicU32>,
    latest: SharedFrame,
) {
    let mut backend: Option<Box<dyn Capture>> = None;
    let mut applied: Option<CaptureSettings> = None;
    // The previous frame's bytes. JPEG is deterministic, so an unchanged
    // desktop produces byte-identical output: no need to send it again.
    let mut previous: Option<Bytes> = None;

    let mut window = Instant::now();
    let mut frames = 0u32;

    loop {
        // Nobody is watching: do not burn CPU on screenshots.
        if tx.receiver_count() == 0 {
            fps_x10.store(0, Ordering::Relaxed);
            window = Instant::now();
            frames = 0;
            // Drop the backend too: an ffmpeg child would keep encoding.
            backend = None;
            applied = None;
            previous = None;
            std::thread::sleep(Duration::from_millis(150));
            continue;
        }

        let current = settings
            .read()
            .unwrap_or_else(|e| e.into_inner())
            .clone();

        if applied.as_ref() != Some(&current) {
            // Drop the old backend first: for ffmpeg this kills the child process.
            backend = None;
            match make_capture(&plan, &current) {
                Ok(fresh) => {
                    if let Some(output) = &current.output {
                        tracing::info!(
                            "capture: {} · {output} · {} fps · quality {} · scale {:.2}×",
                            match plan.backend {
                                Backend::Grim => "grim",
                                Backend::Ffmpeg => "ffmpeg",
                                Backend::Test => "test pattern",
                                Backend::Auto => "auto",
                            },
                            current.fps,
                            current.quality,
                            current.scale
                        );
                    } else {
                        tracing::info!(
                            "capture: {} fps · quality {} · scale {:.2}×",
                            current.fps,
                            current.quality,
                            current.scale
                        );
                    }
                    backend = Some(fresh);
                    applied = Some(current.clone());
                }
                Err(err) => {
                    tracing::warn!("cannot start capture: {err:#}");
                    std::thread::sleep(Duration::from_millis(500));
                    continue;
                }
            }
        }

        let Some(capture) = backend.as_mut() else {
            continue;
        };

        let started = Instant::now();
        match capture.next_frame() {
            Ok(frame) => {
                frames += 1;
                if previous.as_ref() != Some(&frame.jpeg) {
                    previous = Some(frame.jpeg.clone());
                    let shared = Arc::new(frame);
                    *latest.lock().unwrap_or_else(|e| e.into_inner()) = Some(shared.clone());
                    let _ = tx.send(shared);
                }
            }
            Err(err) => {
                tracing::warn!("capture failed: {err:#}");
                std::thread::sleep(Duration::from_millis(750));
            }
        }

        let elapsed = started.elapsed();
        let budget = Duration::from_secs_f64(1.0 / current.fps as f64);
        if elapsed < budget {
            std::thread::sleep(budget - elapsed);
        }

        if window.elapsed() >= Duration::from_secs(1) {
            let measured = frames as f32 / window.elapsed().as_secs_f32();
            fps_x10.store((measured * 10.0).round() as u32, Ordering::Relaxed);
            window = Instant::now();
            frames = 0;
        }
    }
}

/// Width/height from a JPEG's SOF marker without decoding pixels.
pub fn jpeg_dims(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 4 || bytes[0] != 0xFF || bytes[1] != 0xD8 {
        return None;
    }
    let mut i = 2;
    while i + 9 < bytes.len() {
        if bytes[i] != 0xFF {
            i += 1;
            continue;
        }
        let marker = bytes[i + 1];
        // Standalone markers carry no length field.
        if marker == 0xD8 || marker == 0x01 || (0xD0..=0xD7).contains(&marker) {
            i += 2;
            continue;
        }
        let len = u16::from_be_bytes([bytes[i + 2], bytes[i + 3]]) as usize;
        let is_sof = (0xC0..=0xCF).contains(&marker) && !matches!(marker, 0xC4 | 0xC8 | 0xCC);
        if is_sof {
            let h = u16::from_be_bytes([bytes[i + 5], bytes[i + 6]]) as u32;
            let w = u16::from_be_bytes([bytes[i + 7], bytes[i + 8]]) as u32;
            return Some((w, h));
        }
        if len < 2 {
            return None;
        }
        i += 2 + len;
    }
    None
}

/// Build a frame, refusing anything that is not a decodable JPEG.
pub fn jpeg_frame(bytes: Vec<u8>) -> Result<Frame> {
    let (w, h) = jpeg_dims(&bytes).ok_or_else(|| anyhow::anyhow!("not a JPEG frame"))?;
    if w == 0 || h == 0 {
        bail!("JPEG has zero dimensions");
    }
    Ok(Frame {
        jpeg: Bytes::from(bytes),
        w,
        h,
    })
}

/// Shared "latest frame" slot for backends that read a stream in a thread.
pub struct LatestFrame {
    slot: std::sync::Mutex<Option<Frame>>,
    signal: std::sync::Condvar,
}

impl LatestFrame {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            slot: std::sync::Mutex::new(None),
            signal: std::sync::Condvar::new(),
        })
    }

    pub fn push(&self, frame: Frame) {
        let mut slot = self.slot.lock().unwrap_or_else(|e| e.into_inner());
        *slot = Some(frame); // dropping the previous frame is intentional
        self.signal.notify_one();
    }

    pub fn take(&self, timeout: Duration) -> Result<Frame> {
        let deadline = Instant::now() + timeout;
        let mut slot = self.slot.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some(frame) = slot.take() {
                return Ok(frame);
            }
            let now = Instant::now();
            if now >= deadline {
                bail!("timed out waiting for a frame");
            }
            let (guard, _) = self
                .signal
                .wait_timeout(slot, deadline - now)
                .unwrap_or_else(|e| e.into_inner());
            slot = guard;
        }
    }
}

/// Helper used by ffmpeg's reader thread.
pub fn drain_jpegs(buffer: &mut Vec<u8>, latest: &LatestFrame) {
    loop {
        let Some(start) = find_marker(buffer, 0, 0xD8) else {
            // Keep the last byte: a marker may be split across reads.
            if buffer.len() > 2 {
                let keep = buffer.len() - 1;
                buffer.drain(..keep);
            }
            return;
        };
        if start > 0 {
            buffer.drain(..start);
        }

        let Some(end) = find_marker(buffer, 2, 0xD9) else {
            if buffer.len() > 32 * 1024 * 1024 {
                tracing::warn!("discarding oversized JPEG buffer");
                buffer.clear();
            }
            return;
        };

        let take = end + 2;
        let bytes: Vec<u8> = buffer.drain(..take).collect();
        match jpeg_frame(bytes) {
            Ok(frame) => latest.push(frame),
            Err(err) => tracing::debug!("skipping bad frame: {err:#}"),
        }
    }
}

fn find_marker(bytes: &[u8], from: usize, marker: u8) -> Option<usize> {
    if bytes.len() < 2 {
        return None;
    }
    let mut i = from;
    while i + 1 < bytes.len() {
        if bytes[i] == 0xFF && bytes[i + 1] == marker {
            return Some(i);
        }
        i += 1;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use jpeg_encoder::{ColorType, Encoder};

    fn jpeg(w: u16, h: u16) -> Vec<u8> {
        let rgb = vec![0x80u8; w as usize * h as usize * 3];
        let mut out = Vec::new();
        Encoder::new(&mut out, 80)
            .encode(&rgb, w, h, ColorType::Rgb)
            .expect("encode");
        out
    }

    #[test]
    fn reads_dimensions_without_decoding() {
        let bytes = jpeg(40, 24);
        assert_eq!(jpeg_dims(&bytes), Some((40, 24)));
        assert!(jpeg_frame(bytes).is_ok());
    }

    #[test]
    fn rejects_garbage() {
        assert!(jpeg_dims(b"not a jpeg at all").is_none());
        assert!(jpeg_frame(vec![0, 1, 2, 3]).is_err());
    }

    #[test]
    fn splits_concatenated_mjpeg_stream() {
        let latest = LatestFrame::new();
        let mut buffer = jpeg(40, 24);
        buffer.extend(jpeg(48, 32));

        drain_jpegs(&mut buffer, &latest);

        // Only the freshest frame is kept.
        let frame = latest.take(Duration::from_millis(10)).expect("frame");
        assert_eq!((frame.w, frame.h), (48, 32));
        assert!(buffer.is_empty());
    }

    #[test]
    fn waits_for_the_rest_of_a_partial_frame() {
        let latest = LatestFrame::new();
        let full = jpeg(40, 24);
        let split = full.len() / 2;

        let mut buffer = full[..split].to_vec();
        drain_jpegs(&mut buffer, &latest);
        assert!(latest.take(Duration::from_millis(5)).is_err());

        buffer.extend_from_slice(&full[split..]);
        drain_jpegs(&mut buffer, &latest);
        assert_eq!(latest.take(Duration::from_millis(10)).unwrap().w, 40);
    }

    fn settings(fps: u32, quality: u8, scale: f64) -> CaptureSettings {
        CaptureSettings {
            fps,
            quality,
            scale,
            output: None,
            cursor: true,
        }
    }

    #[test]
    fn settings_merge_and_clamp() {
        let base = settings(15, 75, 1.0);
        assert_eq!(
            base.clone().merge(Some(0), Some(200), Some(9.0)),
            settings(1, 95, 1.0)
        );
        assert_eq!(base.clone().merge(Some(24), None, None), settings(24, 75, 1.0));
        assert_eq!(
            base.clone().merge(None, Some(55), Some(0.6)),
            settings(15, 55, 0.6)
        );
        // Output and cursor survive a quality change.
        let with_output = CaptureSettings {
            output: Some("pocketdesk-phone".into()),
            cursor: false,
            ..base.clone()
        };
        let merged = with_output.merge(None, Some(50), None);
        assert_eq!(merged.output.as_deref(), Some("pocketdesk-phone"));
        assert!(!merged.cursor);
    }

    #[test]
    fn presets_round_trip() {
        assert_eq!(settings(10, 45, 0.75).preset(), "low");
        assert_eq!(settings(15, 65, 1.0).preset(), "balanced");
        assert_eq!(settings(20, 82, 1.0).preset(), "sharp");
        assert_eq!(settings(30, 60, 1.0).preset(), "smooth");
    }
}
