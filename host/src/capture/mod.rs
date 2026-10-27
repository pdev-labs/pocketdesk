//! Screen capture backends.
//!
//! Every backend produces complete JPEG frames, which keeps the phone side
//! simple (one decoder works everywhere) and avoids the licensing/bandwidth
//! complexity of an H.264 pipeline in v0.1.
//!
//! A single capture thread feeds a broadcast channel, so five connected phones
//! still cost exactly one screenshot per frame.

pub mod ffmpeg;
pub mod grim;
pub mod test_pattern;

use std::{
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};

use anyhow::{bail, Result};
use bytes::Bytes;
use tokio::sync::broadcast;

use crate::config::{Backend, Config};

pub struct Frame {
    pub jpeg: Bytes,
    pub w: u32,
    pub h: u32,
}

pub struct CaptureInfo {
    pub backend: &'static str,
    pub fps: u32,
    pub quality: u8,
    pub width: u32,
    pub height: u32,
}

pub trait Capture: Send {
    fn next_frame(&mut self) -> Result<Frame>;
    /// Frame size before the first frame arrives, if the backend knows it.
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

pub fn spawn(cfg: &Config, tx: broadcast::Sender<Arc<Frame>>, fps_x10: Arc<AtomicU32>) -> Result<CaptureInfo> {
    let backend = resolve(cfg.capture);

    let (mut cap, name): (Box<dyn Capture>, &'static str) = match backend {
        Backend::Grim => (Box::new(grim::Grim::new(cfg)?), "grim (wayland)"),
        Backend::Ffmpeg => (Box::new(ffmpeg::Ffmpeg::new(cfg)?), "ffmpeg"),
        Backend::Test => (Box::new(test_pattern::TestPattern::new(cfg)), "test pattern"),
        Backend::Auto => unreachable!("resolve() returns a concrete backend"),
    };

    let (hint_w, hint_h) = cap.hint_size();
    let fps = cfg.fps.clamp(1, 60);

    let info = CaptureInfo {
        backend: name,
        fps,
        quality: cfg.quality,
        width: hint_w,
        height: hint_h,
    };

    std::thread::Builder::new()
        .name("pocketdesk-capture".to_string())
        .spawn(move || capture_loop(&mut *cap, tx, Duration::from_secs_f64(1.0 / fps as f64), fps_x10))
        .map_err(|e| anyhow::anyhow!("spawning capture thread: {e}"))?;

    Ok(info)
}

fn capture_loop(
    cap: &mut dyn Capture,
    tx: broadcast::Sender<Arc<Frame>>,
    budget: Duration,
    fps_x10: Arc<AtomicU32>,
) {
    let mut window = Instant::now();
    let mut frames = 0u32;

    loop {
        // Nobody is watching: do not burn CPU on screenshots.
        if tx.receiver_count() == 0 {
            fps_x10.store(0, Ordering::Relaxed);
            window = Instant::now();
            frames = 0;
            std::thread::sleep(Duration::from_millis(150));
            continue;
        }

        let started = Instant::now();
        match cap.next_frame() {
            Ok(frame) => {
                frames += 1;
                let _ = tx.send(Arc::new(frame));
            }
            Err(err) => {
                tracing::warn!("capture failed: {err:#}");
                std::thread::sleep(Duration::from_millis(750));
            }
        }

        let elapsed = started.elapsed();
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
