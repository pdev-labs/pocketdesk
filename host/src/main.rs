//! PocketDesk — stream this machine's screen to your phone and drive it remotely.

mod capture;
mod config;
mod input;
mod keys;
mod layout;
mod pairing;
mod protocol;
mod server;
mod throttle;

use std::{
    io::IsTerminal,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU32, AtomicU64},
        Arc, Mutex, RwLock,
    },
    time::Duration,
};

use anyhow::{bail, Result};
use clap::Parser;
use tracing_subscriber::EnvFilter;

use crate::{
    capture::CaptureInfo,
    config::Config,
    layout::Geometry,
};

#[derive(Clone)]
pub struct AppState {
    pub version: &'static str,
    pub token: Arc<String>,
    pub frames: tokio::sync::broadcast::Sender<Arc<capture::Frame>>,
    pub geometry: Arc<RwLock<Geometry>>,
    pub input: Option<Arc<Mutex<input::Input>>>,
    pub input_error: Option<String>,
    pub read_only: bool,
    pub capture: Arc<CaptureInfo>,
    pub fps_x10: Arc<AtomicU32>,
    pub clients: Arc<AtomicU64>,
    pub throttle: Arc<throttle::Throttle>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cfg = Config::parse();

    let filter = if cfg.verbose {
        "pocketdesk=debug,info"
    } else {
        "pocketdesk=info,warn"
    };
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::new(filter))
        .with_target(false)
        .init();

    if cfg.print_token {
        println!("{}", config::load_or_create_token()?);
        return Ok(());
    }

    let token = match cfg.token.clone() {
        Some(explicit) if !explicit.trim().is_empty() => explicit.trim().to_string(),
        _ => config::load_or_create_token()?,
    };
    if config::normalize_token(&token).len() < config::TOKEN_LEN {
        bail!(
            "the pairing code must be at least {} characters",
            config::TOKEN_LEN
        );
    }

    // Latest-frame-wins: capacity 2 is enough to survive a scheduler hiccup
    // without ever queuing seconds of stale video.
    let fps_x10 = Arc::new(AtomicU32::new(cfg.fps.clamp(1, 60) * 10));
    let (frames, _) = tokio::sync::broadcast::channel(2);
    let capture_info = capture::spawn(&cfg, frames.clone(), fps_x10.clone())?;

    let geometry = match layout::detect(cfg.output.as_deref()) {
        Ok(detected) => detected,
        Err(err) => {
            tracing::warn!("monitor layout unavailable ({err:#}); assuming one screen");
            Geometry::fallback(1366.0, 768.0)
        }
    };
    tracing::debug!("layout: {geometry:?}");
    let geometry = Arc::new(RwLock::new(geometry));

    let (input, input_error) = if cfg.read_only {
        (None, None)
    } else {
        match input::Input::new() {
            Ok(device) => (Some(Arc::new(Mutex::new(device))), None),
            Err(err) => {
                tracing::warn!("{err:#}");
                (None, Some(format!("{err:#}")))
            }
        }
    };

    let state = Arc::new(AppState {
        version: env!("CARGO_PKG_VERSION"),
        token: Arc::new(token.clone()),
        frames,
        geometry: geometry.clone(),
        input,
        input_error,
        read_only: cfg.read_only,
        capture: Arc::new(capture_info),
        fps_x10,
        clients: Arc::new(AtomicU64::new(0)),
        throttle: Arc::new(throttle::Throttle::new()),
    });

    // Re-read the monitor layout now and then, so hot-plugging a screen or
    // rotating the tablet does not desync the pointer mapping.
    {
        let state = state.clone();
        let output = cfg.output.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(20)).await;
                if let Ok(fresh) = layout::detect(output.as_deref()) {
                    let mut guard = state
                        .geometry
                        .write()
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    if guard.captured != fresh.captured || guard.layout != fresh.layout {
                        tracing::info!("monitor layout changed: {}", fresh.source);
                        *guard = fresh;
                    }
                }
            }
        });
    }

    banner(&cfg, &state, &token);

    let app = server::router(state.clone());
    let listener = tokio::net::TcpListener::bind((cfg.bind.as_str(), cfg.port))
        .await
        .map_err(|err| anyhow::anyhow!("cannot listen on {}: {err}", cfg.addr()))?;

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async {
        let _ = tokio::signal::ctrl_c().await;
        println!("\n  stopping PocketDesk");
    })
    .await?;

    Ok(())
}

fn banner(cfg: &Config, state: &AppState, token: &str) {
    let address = if cfg.bind == "127.0.0.1" || cfg.bind == "localhost" {
        "127.0.0.1".to_string()
    } else {
        config::lan_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|| "127.0.0.1".to_string())
    };

    let display = state
        .geometry
        .read()
        .map(|g| g.source.clone())
        .unwrap_or_else(|_| "unknown".to_string());

    let input = match (&state.input, &state.input_error) {
        (Some(_), _) => "enabled (uinput virtual pointer + keyboard)".to_string(),
        (None, Some(err)) => format!(
            "disabled — {}",
            err.lines().next().unwrap_or("unavailable")
        ),
        (None, None) => "disabled (read-only mode)".to_string(),
    };

    println!();
    println!("  PocketDesk {}", state.version);
    println!("  ─────────────────────────────────────────────");
    println!("  stream   {} · {} fps · quality {}", state.capture.backend, state.capture.fps, state.capture.quality);
    println!("  display  {display}");
    println!("  server   http://{address}:{}", cfg.port);
    println!("  code     {}", config::pretty_token(token));
    println!("  input    {input}");
    println!();

    if !cfg.no_qr {
        let payload = pairing::uri(
            &address,
            cfg.port,
            &config::normalize_token(token),
            &pairing::hostname(),
        );
        if std::io::stdout().is_terminal() {
            println!("  Scan this with PocketDesk → \"Scan QR\"");
            match pairing::render_terminal(&payload) {
                Ok(qr) => print!("{qr}"),
                Err(err) => tracing::warn!("could not render the pairing QR: {err:#}"),
            }
            println!();
        } else {
            // Piped output would be garbled with ANSI blocks; the URI is enough.
            tracing::info!("stdout is not a terminal; pairing URI: {payload}");
        }
    }

    println!("  Phone app → scan the QR, or enter {address}:{} and the code above.", cfg.port);
    println!("  The app remembers this machine unless you untick \"Remember this device\".");
    println!("  Stay on your LAN, or reach it from anywhere over Tailscale/WireGuard.");
    println!();
}
