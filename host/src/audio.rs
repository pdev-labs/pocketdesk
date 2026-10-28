//! Desktop audio to the phone.
//!
//! `/audio` upgrades to a second WebSocket that carries raw PCM (s16le,
//! 48 kHz, stereo) in ~20 ms chunks. PulseAudio/PipeWire's monitor source is
//! captured with `parec`, so no encoder is needed on the host and Android can
//! feed the bytes straight into `AudioTrack`.
//!
//! The phone decides whether the machine stays audible: `?mute=1` mutes the
//! default sink while a phone is listening. The mute is undone when the audio
//! socket closes, including the ordinary network-drop case.

use std::{process::Stdio, sync::atomic::Ordering, sync::Arc};

use anyhow::{Context, Result};
use axum::extract::ws::{Message, WebSocket};
use tokio::{
    io::AsyncReadExt,
    process::Command,
};

use crate::AppState;

pub const RATE: u32 = 48_000;
pub const CHANNELS: u16 = 2;
/// 20 ms of 48 kHz stereo s16.
const FRAME_BYTES: usize = (RATE as usize / 50) * CHANNELS as usize * 2;

pub fn available() -> bool {
    which("parec").is_some()
}

fn which(bin: &str) -> Option<std::path::PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path)
        .map(|dir| dir.join(bin))
        .find(|candidate| candidate.is_file())
}

pub async fn handle(mut socket: WebSocket, state: Arc<AppState>, mute_host: bool) {
    if !available() {
        let _ = socket
            .send(Message::Text(
                serde_json::json!({
                    "t": "error",
                    "msg": "parec not found — install pulseaudio-utils or pipewire-pulse"
                })
                .to_string()
                .into(),
            ))
            .await;
        return;
    }

    let first = state.audio_clients.fetch_add(1, Ordering::SeqCst) == 0;
    let muted = mute_host && first;
    if muted {
        set_mute(true).await;
        state.audio_muted.store(true, Ordering::SeqCst);
    }

    let config = serde_json::json!({
        "t": "audio",
        "codec": "pcm_s16le",
        "rate": RATE,
        "channels": CHANNELS,
        "frame": FRAME_BYTES,
    });
    if socket
        .send(Message::Text(config.to_string().into()))
        .await
        .is_err()
    {
        finish(&state).await;
        return;
    }

    let mut child = match spawn_capture() {
        Ok(child) => child,
        Err(err) => {
            tracing::warn!("audio capture failed: {err:#}");
            let _ = socket
                .send(Message::Text(
                    serde_json::json!({"t": "error", "msg": format!("{err:#}")})
                        .to_string()
                        .into(),
                ))
                .await;
            finish(&state).await;
            return;
        }
    };

    let Some(mut stdout) = child.stdout.take() else {
        let _ = child.kill().await;
        finish(&state).await;
        return;
    };

    tracing::info!("audio: streaming the default monitor to the phone");
    let mut buffer = vec![0u8; FRAME_BYTES];

    loop {
        tokio::select! {
            read = stdout.read_exact(&mut buffer) => {
                match read {
                    Ok(_) => {
                        if socket
                            .send(Message::Binary(buffer.clone().into()))
                            .await
                            .is_err()
                        {
                            break;
                        }
                    }
                    Err(_) => break,
                }
            }
            message = socket.recv() => {
                match message {
                    None | Some(Err(_)) | Some(Ok(Message::Close(_))) => break,
                    _ => {}
                }
            }
        }
    }

    let _ = child.kill().await;
    tracing::info!("audio: stopped");
    finish(&state).await;
}

async fn finish(state: &AppState) {
    let remaining = state
        .audio_clients
        .fetch_sub(1, Ordering::SeqCst)
        .saturating_sub(1);
    // Whether *this* client did the muting does not matter: the sink stays
    // muted until the last listener leaves, then it is restored once.
    if remaining == 0 && state.audio_muted.swap(false, Ordering::SeqCst) {
        set_mute(false).await;
    }
}

fn spawn_capture() -> Result<tokio::process::Child> {
    Command::new("parec")
        .args([
            "--device=@DEFAULT_MONITOR@",
            "--format=s16le",
            "--rate=48000",
            "--channels=2",
            "--raw",
            "--latency-msec=30",
        ])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .context("starting parec")
}

/// Mute the default sink so sound only comes out of the phone. Best effort:
/// `wpctl` on PipeWire, `pactl` otherwise.
async fn set_mute(mute: bool) {
    let value = if mute { "1" } else { "0" };

    let wpctl = Command::new("wpctl")
        .args(["set-mute", "@DEFAULT_AUDIO_SINK@", value])
        .status()
        .await;
    if matches!(wpctl, Ok(status) if status.success()) {
        return;
    }

    let pactl = Command::new("pactl")
        .args(["set-sink-mute", "@DEFAULT_SINK@", value])
        .status()
        .await;
    match pactl {
        Ok(status) if status.success() => {}
        Ok(status) => tracing::warn!("pactl set-sink-mute exited with {status}"),
        Err(err) => tracing::warn!("could not mute the host sink: {err}"),
    }
}
