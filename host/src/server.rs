//! HTTP + WebSocket server.
//!
//! One route carries everything: `/ws` upgrades to a WebSocket that pushes JPEG
//! frames down and takes JSON input events up. Authentication is a bearer token
//! checked in constant time; there is no anonymous read of the screen.

use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{atomic::Ordering, Arc},
    time::{Duration, Instant},
};

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Query, State,
    },
    http::{header, HeaderMap, StatusCode},
    response::{Html, IntoResponse, Response},
    routing::get,
    Router,
};
use subtle::ConstantTimeEq;
use tokio::sync::broadcast::error::RecvError;

use crate::{
    config::normalize_token,
    protocol::ClientMsg,
    view::View,
    AppState,
};

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/healthz", get(|| async { "ok" }))
        .route("/ws", get(upgrade))
        .route("/audio", get(audio_upgrade))
        .with_state(state)
}

async fn index(State(state): State<Arc<AppState>>) -> Html<String> {
    let clients = state.clients.load(Ordering::Relaxed);
    let fps = state.fps_x10.load(Ordering::Relaxed) as f32 / 10.0;
    let display = state
        .geometry
        .read()
        .map(|g| g.source.clone())
        .unwrap_or_else(|_| "unknown".to_string());
    let settings = state
        .settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_else(|e| e.into_inner().clone());
    let mode = if state
        .second_screen
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .is_some()
    {
        "second screen (base)"
    } else {
        "mirror · per-phone second screens"
    };

    Html(format!(
        r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketDesk</title>
<style>
:root {{ color-scheme: dark; }}
body {{ margin:0; min-height:100vh; display:grid; place-items:center;
  background:#0b0c0f; color:#e7e9ee;
  font:15px/1.6 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }}
.card {{ width:min(560px,90vw); padding:32px; border-radius:20px;
  background:#14161b; border:1px solid #262a33; box-shadow:0 24px 60px #0008; }}
h1 {{ margin:0 0 4px; font-size:22px; letter-spacing:-0.02em; }}
p.sub {{ margin:0 0 24px; color:#8b93a7; }}
.row {{ display:flex; justify-content:space-between; gap:16px; padding:10px 0;
  border-top:1px solid #1e222a; }}
.row span:first-child {{ color:#8b93a7; }}
.dot {{ display:inline-block; width:8px; height:8px; border-radius:50%;
  background:#5cd6a0; margin-right:8px; box-shadow:0 0 12px #5cd6a0; }}
code {{ background:#0b0c0f; padding:2px 6px; border-radius:6px; color:#8ea1ff; }}
</style></head><body>
<div class="card">
  <h1><span class="dot"></span>PocketDesk is running</h1>
  <p class="sub">Streaming {display} — v{version}</p>
  <div class="row"><span>Clients</span><span>{clients}</span></div>
  <div class="row"><span>Mode</span><span>{mode}</span></div>
  <div class="row"><span>Capture rate</span><span>{fps:.1} fps</span></div>
  <div class="row"><span>Quality</span><span>{quality} · {target_fps} fps target · {scale:.2}×</span></div>
  <div class="row"><span>Input</span><span>{input}</span></div>
  <div class="row"><span>Audio</span><span>{audio}</span></div>
  <div class="row"><span>Endpoints</span><span><code>/ws</code> · <code>/audio</code></span></div>
  <p class="sub" style="margin:24px 0 0">Open PocketDesk on your phone and scan the QR code
  printed in the host terminal — or type the short code shown there. The app can remember
  this machine so you only pair once.</p>
</div></body></html>"#,
        display = html_escape(&display),
        version = state.version,
        clients = clients,
        mode = mode,
        fps = fps,
        quality = settings.quality,
        target_fps = settings.fps,
        scale = settings.scale,
        audio = if state.audio_clients.load(Ordering::Relaxed) > 0 {
            "phone"
        } else {
            "this machine"
        },
        input = if state.input.is_some() {
            "mouse + keyboard"
        } else if state.read_only {
            "disabled (read-only)"
        } else {
            "view only"
        },
    ))
}

fn html_escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

async fn upgrade(
    State(state): State<Arc<AppState>>,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    ws: WebSocketUpgrade,
) -> Response {
    let token = query.get("token").cloned().or_else(|| {
        headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .map(|value| value.to_string())
    });

    match authorize(&state, token, peer) {
        Err(response) => response,
        Ok(()) => {
            let transport = if peer.ip().is_loopback() { "USB" } else { "LAN" };
            tracing::info!(%peer, "client connected over {transport}");
            ws.on_upgrade(move |socket| session(socket, state))
        }
    }
}

async fn audio_upgrade(
    State(state): State<Arc<AppState>>,
    Query(query): Query<HashMap<String, String>>,
    headers: HeaderMap,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    ws: WebSocketUpgrade,
) -> Response {
    let token = query.get("token").cloned().or_else(|| {
        headers
            .get(header::AUTHORIZATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.strip_prefix("Bearer "))
            .map(|value| value.to_string())
    });
    let mute = query
        .get("mute")
        .map(|value| value == "1" || value.eq_ignore_ascii_case("true"))
        .unwrap_or(false);

    match authorize(&state, token, peer) {
        Err(response) => response,
        Ok(()) => {
            tracing::info!(%peer, "audio client connected");
            ws.on_upgrade(move |socket| crate::audio::handle(socket, state, mute))
        }
    }
}

/// Shared token check and brute-force throttle for both WebSocket routes.
fn authorize(
    state: &AppState,
    token: Option<String>,
    peer: SocketAddr,
) -> Result<(), Response> {
    let ip = peer.ip();
    if let Err(remaining) = state.throttle.check(ip) {
        let seconds = remaining.as_secs().max(1);
        tracing::warn!(%peer, "rejected connection: locked out for another {seconds}s");
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            format!("too many wrong codes — retry in {seconds}s"),
        )
            .into_response());
    }

    match token {
        Some(candidate) if token_matches(&state.token, &candidate) => {
            state.throttle.success(ip);
            Ok(())
        }
        Some(_) => {
            state.throttle.fail(ip);
            tracing::warn!(%peer, "rejected connection: wrong code");
            Err((StatusCode::UNAUTHORIZED, "wrong pairing code").into_response())
        }
        None => {
            state.throttle.fail(ip);
            tracing::warn!(%peer, "rejected connection: no code");
            Err((StatusCode::UNAUTHORIZED, "missing pairing code").into_response())
        }
    }
}

fn token_matches(expected: &str, candidate: &str) -> bool {
    // Accept `k7m4-qx2b`, `K7M4 QX2B` and the canonical form alike.
    let expected = normalize_token(expected);
    let candidate = normalize_token(candidate);
    if expected.is_empty() || expected.len() != candidate.len() {
        return false;
    }
    expected.as_bytes().ct_eq(candidate.as_bytes()).into()
}

/// What a client message asked the session loop to do.
enum Outcome {
    Reply(Option<String>),
    Pause,
    Resume,
}

async fn session(mut socket: WebSocket, state: Arc<AppState>) {
    state.clients.fetch_add(1, Ordering::Relaxed);
    // Each connection owns its mode, capture and geometry: one phone asking for
    // a second screen never moves another phone's stream.
    let mut view = View::mirror(&state);
    let mut frames = state.frames.subscribe();

    let settings = view
        .settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_else(|e| e.into_inner().clone());
    let (width, height) = view.size();

    let hello = serde_json::json!({
        "t": "hello",
        "app": "pocketdesk",
        "version": state.version,
        "codec": "jpeg",
        "fps": settings.fps,
        "quality": settings.quality,
        "scale": settings.scale,
        "mode": view.mode(),
        "width": width,
        "height": height,
        "presets": ["low", "balanced", "sharp", "smooth"],
        "input": state.input.is_some(),
        "input_error": state.input_error,
        "read_only": state.read_only,
        "audio": crate::audio::available(),
    });

    if socket
        .send(Message::Text(hello.to_string().into()))
        .await
        .is_err()
    {
        state.clients.fetch_sub(1, Ordering::Relaxed);
        return;
    }

    let mut stats_timer = tokio::time::interval(Duration::from_secs(5));
    stats_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    stats_timer.tick().await;

    let mut ping_timer = tokio::time::interval(Duration::from_secs(20));
    ping_timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    ping_timer.tick().await;

    let mut frames_sent: u64 = 0;
    let mut bytes_sent: u64 = 0;
    let mut last_size = (0u32, 0u32);
    let mut window_start = Instant::now();
    // Frames are only sent to a client that is looking at them; duplicate
    // frames are already suppressed by the capture thread.
    let mut streaming = true;

    // A phone that connects while the screen is idle would otherwise wait for
    // the next change. Send whatever we have right now.
    if let Some(frame) = view.latest() {
        let msg = serde_json::json!({"t":"size","w":frame.w,"h":frame.h});
        if socket.send(Message::Text(msg.to_string().into())).await.is_err() {
            state.clients.fetch_sub(1, Ordering::Relaxed);
            return;
        }
        if socket.send(Message::Binary(frame.jpeg.clone())).await.is_err() {
            state.clients.fetch_sub(1, Ordering::Relaxed);
            return;
        }
        last_size = (frame.w, frame.h);
    }

    loop {
        // Text messages are handled *after* the select, because switching
        // display replaces the receiver the select borrows.
        let mut incoming = None;

        tokio::select! {
            received = frames.recv() => {
                match received {
                    Ok(frame) => {
                        if !streaming {
                            continue;
                        }
                        if (frame.w, frame.h) != last_size {
                            last_size = (frame.w, frame.h);
                            let msg = serde_json::json!({"t":"size","w":frame.w,"h":frame.h});
                            if socket.send(Message::Text(msg.to_string().into())).await.is_err() {
                                break;
                            }
                        }
                        if socket.send(Message::Binary(frame.jpeg.clone())).await.is_err() {
                            break;
                        }
                        frames_sent += 1;
                        bytes_sent += frame.jpeg.len() as u64;
                    }
                    Err(RecvError::Lagged(skipped)) => {
                        tracing::debug!("dropped {skipped} frame(s) for a slow client");
                    }
                    Err(RecvError::Closed) => break,
                }
            }

            message = socket.recv() => {
                incoming = Some(message);
            }

            _ = stats_timer.tick() => {
                let seconds = window_start.elapsed().as_secs_f64().max(0.001);
                let msg = serde_json::json!({
                    "t": "stats",
                    "fps": ((frames_sent as f64 / seconds) * 10.0).round() / 10.0,
                    "kbps": ((bytes_sent as f64 * 8.0 / 1000.0) / seconds).round() as u64,
                    "capture_fps": view.fps_x10.load(Ordering::Relaxed) as f64 / 10.0,
                    "clients": state.clients.load(Ordering::Relaxed),
                });
                if socket.send(Message::Text(msg.to_string().into())).await.is_err() {
                    break;
                }
                frames_sent = 0;
                bytes_sent = 0;
                window_start = Instant::now();
                // An extended phone has its own monitor; keep its geometry
                // fresh. Mirroring shares the host-wide refresher.
                if view.is_extended() {
                    view.refresh_layout();
                }
            }

            _ = ping_timer.tick() => {
                if socket.send(Message::Ping(Vec::new().into())).await.is_err() {
                    break;
                }
            }
        }

        let Some(message) = incoming else { continue };
        match message {
            Some(Ok(Message::Text(text))) => {
                match handle_text(&state, &mut view, &mut frames, &text).await {
                    Ok(Outcome::Reply(Some(reply))) => {
                        if socket.send(Message::Text(reply.into())).await.is_err() {
                            break;
                        }
                    }
                    Ok(Outcome::Reply(None)) => {}
                    Ok(Outcome::Pause) => {
                        tracing::debug!("client paused its stream");
                        streaming = false;
                    }
                    Ok(Outcome::Resume) => {
                        streaming = true;
                        if let Some(frame) = view.latest() {
                            let size = serde_json::json!({
                                "t": "size", "w": frame.w, "h": frame.h
                            });
                            if socket
                                .send(Message::Text(size.to_string().into()))
                                .await
                                .is_err()
                            {
                                break;
                            }
                            if socket
                                .send(Message::Binary(frame.jpeg.clone()))
                                .await
                                .is_err()
                            {
                                break;
                            }
                            last_size = (frame.w, frame.h);
                        }
                    }
                    Err(reply) => {
                        let msg = serde_json::json!({"t":"error","msg":reply});
                        if socket.send(Message::Text(msg.to_string().into())).await.is_err() {
                            break;
                        }
                    }
                }
            }
            Some(Ok(Message::Close(_))) | None => break,
            Some(Ok(_)) => {}
            Some(Err(err)) => {
                tracing::debug!("websocket error: {err}");
                break;
            }
        }
    }

    state.clients.fetch_sub(1, Ordering::Relaxed);
    // Dropping `view` removes this phone's virtual monitor and stops its
    // capture thread; other phones are untouched.
    tracing::info!("client disconnected");
}

/// Returns an [`Outcome`] for the session loop, or `Err(message)` when the
/// client should be told what went wrong.
async fn handle_text(
    state: &Arc<AppState>,
    view: &mut View,
    frames: &mut tokio::sync::broadcast::Receiver<Arc<crate::capture::Frame>>,
    text: &str,
) -> Result<Outcome, String> {
    let msg: ClientMsg = serde_json::from_str(text).map_err(|e| format!("bad message: {e}"))?;

    match msg {
        ClientMsg::Ping => {
            return Ok(Outcome::Reply(Some("{\"t\":\"pong\"}".to_string())))
        }
        ClientMsg::Pause => return Ok(Outcome::Pause),
        ClientMsg::Resume => return Ok(Outcome::Resume),

        // Stream settings are not input: they work in read-only mode too, and
        // they belong to this phone's view.
        ClientMsg::SetQuality {
            fps,
            quality,
            scale,
        } => {
            {
                let mut guard = view
                    .settings
                    .write()
                    .unwrap_or_else(|e| e.into_inner());
                let current = guard.clone();
                *guard = current.merge(fps, quality, scale);
            }
            let applied = view
                .settings
                .read()
                .map(|s| s.clone())
                .unwrap_or_else(|e| e.into_inner().clone());
            tracing::info!(
                "stream ({}): {} fps · quality {} · scale {:.2}×",
                view.mode(),
                applied.fps,
                applied.quality,
                applied.scale
            );
            return Ok(Outcome::Reply(Some(view.capture_json())));
        }

        ClientMsg::SetDisplay {
            mode,
            width,
            height,
        } => {
            // Creating a compositor output blocks for a moment; block_in_place
            // keeps the other connections streaming meanwhile.
            let state = state.clone();
            let reply = tokio::task::block_in_place(|| {
                if mode == "extend" {
                    let width = width.unwrap_or(1600);
                    let height = height.unwrap_or(900);
                    let receiver = view
                        .extend(&state, width, height)
                        .map_err(|e| format!("{e:#}"))?;
                    *frames = receiver;
                } else {
                    *frames = view.mirror_again(&state);
                }
                Ok::<String, String>(view.capture_json())
            })?;
            return Ok(Outcome::Reply(Some(reply)));
        }

        _ => {}
    }

    if state.read_only {
        return Err("host is running in read-only mode".to_string());
    }

    let Some(input) = &state.input else {
        return Err(state
            .input_error
            .clone()
            .unwrap_or_else(|| "input is disabled on the host".to_string()));
    };

    // Input is mapped through *this* phone's monitor geometry, so a phone on a
    // virtual display drives that display.
    let geometry = view
        .geometry
        .read()
        .map(|g| g.clone())
        .unwrap_or_else(|e| e.into_inner().clone());

    let mut device = input.lock().unwrap_or_else(|e| e.into_inner());
    device
        .apply(&msg, &geometry)
        .map_err(|e| format!("{e:#}"))?;
    Ok(Outcome::Reply(None))
}

#[cfg(test)]
mod tests {
    use super::token_matches;

    #[test]
    fn tokens_must_match_exactly() {
        assert!(token_matches("K7M4QX2B", "K7M4QX2B"));
        assert!(token_matches("K7M4QX2B", "k7m4-qx2b"));
        assert!(token_matches("K7M4QX2B", " K7M4 QX2B "));
        assert!(!token_matches("K7M4QX2B", "K7M4QX2C"));
        assert!(!token_matches("K7M4QX2B", "K7M4QX2"));
        assert!(!token_matches("K7M4QX2B", ""));
        assert!(!token_matches("", "x"));
    }
}
