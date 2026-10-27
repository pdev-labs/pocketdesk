//! ffmpeg capture: X11 (x11grab), Windows (gdigrab), macOS (avfoundation).
//!
//! ffmpeg encodes MJPEG and writes the frames to stdout; we re-assemble the
//! JPEGs by marker and keep only the freshest one, so a slow phone never builds
//! a latency backlog.

use std::{
    io::Read,
    process::{Child, Command, Stdio},
    sync::Arc,
    thread::JoinHandle,
    time::Duration,
};

use anyhow::{bail, Context, Result};

use crate::{
    capture::{drain_jpegs, Capture, Frame, LatestFrame},
    config::Config,
};

pub struct Ffmpeg {
    child: Child,
    latest: Arc<LatestFrame>,
    reader: Option<JoinHandle<()>>,
}

impl Ffmpeg {
    pub fn new(cfg: &Config) -> Result<Self> {
        let mut command = Command::new("ffmpeg");
        command
            .args(ffmpeg_args(cfg))
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit());

        let mut child = command.spawn().map_err(|e| {
            anyhow::anyhow!(
                "could not start ffmpeg ({e}). Install it (`sudo pacman -S ffmpeg`, \
                 `apt install ffmpeg`, `brew install ffmpeg`) or use --capture test."
            )
        })?;

        let mut stdout = child
            .stdout
            .take()
            .context("ffmpeg did not expose stdout")?;

        let latest = LatestFrame::new();
        let sink = latest.clone();

        let reader = std::thread::Builder::new()
            .name("ffmpeg-reader".to_string())
            .spawn(move || {
                let mut buffer: Vec<u8> = Vec::with_capacity(1 << 20);
                let mut chunk = [0u8; 64 * 1024];
                loop {
                    match stdout.read(&mut chunk) {
                        Ok(0) => break,
                        Ok(n) => {
                            buffer.extend_from_slice(&chunk[..n]);
                            drain_jpegs(&mut buffer, &sink);
                        }
                        Err(err) => {
                            tracing::warn!("ffmpeg read error: {err}");
                            break;
                        }
                    }
                }
            })
            .context("spawning ffmpeg reader thread")?;

        Ok(Self {
            child,
            latest,
            reader: Some(reader),
        })
    }
}

impl Capture for Ffmpeg {
    fn next_frame(&mut self) -> Result<Frame> {
        if let Some(status) = self.child.try_wait().context("polling ffmpeg")? {
            bail!("ffmpeg exited with {status}");
        }
        self.latest.take(Duration::from_millis(2500))
    }
}

impl Drop for Ffmpeg {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        if let Some(reader) = self.reader.take() {
            let _ = reader.join();
        }
    }
}

fn ffmpeg_args(cfg: &Config) -> Vec<String> {
    let fps = cfg.fps.clamp(1, 60).to_string();
    let scale = cfg.scale.clamp(0.1, 4.0);
    let q = quality_to_q(cfg.quality);

    let mut args: Vec<String> = vec!["-hide_banner".into(), "-loglevel".into(), "error".into()];
    args.extend(platform_input(&fps));
    args.extend([
        // Even dimensions keep every encoder/browser happy.
        "-vf".into(),
        format!("scale=trunc(iw*{scale}/2)*2:trunc(ih*{scale}/2)*2"),
        "-f".into(),
        "mjpeg".into(),
        "-q:v".into(),
        q,
        "-".into(),
    ]);
    args
}

#[cfg(target_os = "linux")]
fn platform_input(fps: &str) -> Vec<String> {
    let display = std::env::var("DISPLAY").unwrap_or_else(|_| ":0".to_string());
    vec![
        "-f".into(),
        "x11grab".into(),
        "-framerate".into(),
        fps.into(),
        "-i".into(),
        display,
    ]
}

#[cfg(windows)]
fn platform_input(fps: &str) -> Vec<String> {
    vec![
        "-f".into(),
        "gdigrab".into(),
        "-framerate".into(),
        fps.into(),
        "-draw_mouse".into(),
        "1".into(),
        "-i".into(),
        "desktop".into(),
    ]
}

#[cfg(target_os = "macos")]
fn platform_input(fps: &str) -> Vec<String> {
    vec![
        "-f".into(),
        "avfoundation".into(),
        "-framerate".into(),
        fps.into(),
        "-capture_cursor".into(),
        "1".into(),
        "-i".into(),
        "1:none".into(),
    ]
}

#[cfg(not(any(target_os = "linux", target_os = "macos", windows)))]
fn platform_input(_fps: &str) -> Vec<String> {
    Vec::new()
}

fn quality_to_q(quality: u8) -> String {
    // ffmpeg's mjpeg scale runs from 2 (best) to 31 (worst).
    let q = ((100u32.saturating_sub(quality as u32)) * 31 / 100).clamp(2, 31);
    q.to_string()
}
