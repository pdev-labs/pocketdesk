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

use crate::{protocol::ClientMsg, AppState};

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/", get(index))
        .route("/healthz", get(|| async { "ok" }))
        .route("/ws", get(upgrade))
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
  <div class="row"><span>Capture rate</span><span>{fps:.1} fps</span></div>
  <div class="row"><span>Input</span><span>{input}</span></div>
  <div class="row"><span>Endpoint</span><span><code>/ws</code></span></div>
  <p class="sub" style="margin:24px 0 0">Open the PocketDesk app on your phone and
  paste this machine's address plus the pairing token printed in the host terminal.</p>
</div></body></html>"#,
        display = html_escape(&display),
        version = state.version,
        clients = clients,
        fps = fps,
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

    match token {
        Some(candidate) if token_matches(&state.token, &candidate) => {
            tracing::info!(%peer, "client connected");
            ws.on_upgrade(move |socket| session(socket, state))
        }
        Some(_) => {
            tracing::warn!(%peer, "rejected connection: invalid token");
            (StatusCode::UNAUTHORIZED, "invalid token").into_response()
        }
        None => {
            tracing::warn!(%peer, "rejected connection: no token");
            (StatusCode::UNAUTHORIZED, "missing token").into_response()
        }
    }
}

fn token_matches(expected: &str, candidate: &str) -> bool {
    let a = expected.as_bytes();
    let b = candidate.as_bytes();
    if a.len() != b.len() {
        return false;
    }
    a.ct_eq(b).into()
}

async fn session(mut socket: WebSocket, state: Arc<AppState>) {
    state.clients.fetch_add(1, Ordering::Relaxed);
    let mut frames = state.frames.subscribe();

    let hello = serde_json::json!({
        "t": "hello",
        "app": "pocketdesk",
        "version": state.version,
        "codec": "jpeg",
        "fps": state.capture.fps,
        "width": state.capture.width,
        "height": state.capture.height,
        "input": state.input.is_some(),
        "input_error": state.input_error,
        "read_only": state.read_only,
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

    loop {
        tokio::select! {
            received = frames.recv() => {
                match received {
                    Ok(frame) => {
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

            incoming = socket.recv() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        match handle_text(&state, &text).await {
                            Ok(Some(reply)) => {
                                if socket.send(Message::Text(reply.into())).await.is_err() {
                                    break;
                                }
                            }
                            Ok(None) => {}
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

            _ = stats_timer.tick() => {
                let seconds = window_start.elapsed().as_secs_f64().max(0.001);
                let msg = serde_json::json!({
                    "t": "stats",
                    "fps": ((frames_sent as f64 / seconds) * 10.0).round() / 10.0,
                    "kbps": ((bytes_sent as f64 * 8.0 / 1000.0) / seconds).round() as u64,
                    "capture_fps": state.fps_x10.load(Ordering::Relaxed) as f64 / 10.0,
                    "clients": state.clients.load(Ordering::Relaxed),
                });
                if socket.send(Message::Text(msg.to_string().into())).await.is_err() {
                    break;
                }
                frames_sent = 0;
                bytes_sent = 0;
                window_start = Instant::now();
            }

            _ = ping_timer.tick() => {
                if socket.send(Message::Ping(Vec::new().into())).await.is_err() {
                    break;
                }
            }
        }
    }

    state.clients.fetch_sub(1, Ordering::Relaxed);
    tracing::info!("client disconnected");
}

/// Returns `Ok(Some(reply))` to send a reply, `Err(message)` when the client
/// should be told what went wrong.
async fn handle_text(state: &Arc<AppState>, text: &str) -> Result<Option<String>, String> {
    let msg: ClientMsg = serde_json::from_str(text).map_err(|e| format!("bad message: {e}"))?;

    if matches!(msg, ClientMsg::Ping) {
        return Ok(Some("{\"t\":\"pong\"}".to_string()));
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

    // Keep the lock scoped: never hold it across an await point.
    let geometry = state
        .geometry
        .read()
        .map(|g| g.clone())
        .unwrap_or_else(|e| e.into_inner().clone());

    let mut device = input.lock().unwrap_or_else(|e| e.into_inner());
    device
        .apply(&msg, &geometry)
        .map_err(|e| format!("{e:#}"))?;
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::token_matches;

    #[test]
    fn tokens_must_match_exactly() {
        assert!(token_matches("8f0c41a", "8f0c41a"));
        assert!(!token_matches("8f0c41a", "8f0c41b"));
        assert!(!token_matches("8f0c41a", "8f0c41"));
        assert!(!token_matches("8f0c41a", ""));
        assert!(!token_matches("", "x"));
    }
}
