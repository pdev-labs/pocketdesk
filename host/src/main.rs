//! PocketDesk — stream this machine's screen to your phone and drive it remotely.

mod audio;
mod capture;
mod config;
mod discovery;
mod input;
mod keys;
mod layout;
mod pairing;
mod protocol;
mod second_screen;
mod server;
mod throttle;
mod usb;
mod view;

use std::{
    io::IsTerminal,
    net::SocketAddr,
    sync::{
        atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicUsize},
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
    /// Phones currently receiving audio (used to mute/unmute exactly once).
    pub audio_clients: Arc<AtomicUsize>,
    /// True while PocketDesk itself muted the default sink.
    pub audio_muted: Arc<AtomicBool>,
    pub throttle: Arc<throttle::Throttle>,
    /// Live capture knobs (fps, quality, scale, monitor). The phone can change
    /// any of them at any time.
    pub settings: Arc<RwLock<capture::CaptureSettings>>,
    /// Monitor to fall back to when a second screen is released.
    pub base_output: Option<String>,
    /// Backend used for every capture thread, including per-phone displays.
    pub plan: capture::CapturePlan,
    /// Hands out `pocketdesk-phone-<n>` names, one per extended phone.
    pub next_display: Arc<AtomicU32>,
    /// The headless output, when the phone asked for a second screen.
    pub second_screen: Arc<Mutex<Option<second_screen::VirtualDisplay>>>,
    /// Most recent captured frame, for late joiners and resumed sessions.
    pub last_frame: capture::SharedFrame,
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

    let settings = Arc::new(RwLock::new(capture::CaptureSettings::from_config(&cfg)));
    let plan = capture::CapturePlan {
        backend: capture::resolve(cfg.capture),
    };
    let last_frame: capture::SharedFrame = Arc::new(Mutex::new(None));
    let capture_info = capture::spawn(
        plan.clone(),
        settings.clone(),
        frames.clone(),
        fps_x10.clone(),
        last_frame.clone(),
        Arc::new(AtomicBool::new(false)),
    )?;

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

    let geometry = layout::detect(cfg.output.as_deref())
        .unwrap_or_else(|err| {
            tracing::warn!("monitor layout unavailable ({err:#}); assuming one screen");
            Geometry::fallback(1366.0, 768.0)
        });
    tracing::debug!("layout: {geometry:?}");

    let state = Arc::new(AppState {
        version: env!("CARGO_PKG_VERSION"),
        token: Arc::new(token.clone()),
        frames,
        geometry: Arc::new(RwLock::new(geometry)),
        input,
        input_error,
        read_only: cfg.read_only,
        capture: Arc::new(capture_info),
        fps_x10,
        clients: Arc::new(AtomicU64::new(0)),
        audio_clients: Arc::new(AtomicUsize::new(0)),
        audio_muted: Arc::new(AtomicBool::new(false)),
        throttle: Arc::new(throttle::Throttle::new()),
        settings: settings.clone(),
        base_output: cfg.output.clone(),
        plan,
        next_display: Arc::new(AtomicU32::new(1)),
        second_screen: Arc::new(Mutex::new(None)),
        last_frame,
    });

    // `--extend 1600x900`: start with a virtual monitor instead of mirroring.
    if let Some((width, height)) = cfg.extend {
        match second_screen::VirtualDisplay::create(width, height, false) {
            Ok(display) => {
                let mut settings = settings.write().unwrap_or_else(|e| e.into_inner());
                settings.output = Some(second_screen::OUTPUT_NAME.to_string());
                settings.scale = 1.0;
                drop(settings);
                *state
                    .second_screen
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = Some(display);
                refresh_geometry(&state);
            }
            Err(err) => tracing::warn!("--extend disabled: {err:#}"),
        }
    }

    // Re-read the monitor layout now and then, so hot-plugging a screen, adding
    // the virtual monitor or rotating the phone does not desync the pointer.
    {
        let state = state.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(4)).await;
                refresh_geometry(&state);
            }
        });
    }

    let usb_ready = if cfg.no_usb {
        0
    } else {
        let ready = usb::setup(cfg.port);
        usb::monitor(cfg.port);
        ready
    };

    banner(&cfg, &state, &token, usb_ready);

    let app = server::router(state.clone());
    let listener = tokio::net::TcpListener::bind((cfg.bind.as_str(), cfg.port))
        .await
        .map_err(|err| anyhow::anyhow!("cannot listen on {}: {err}", cfg.addr()))?;

    // Advertise on the LAN only after the HTTP port is actually reachable.
    discovery::spawn(cfg.port, state.version);

    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(async {
        shutdown_signal().await;
        println!("\n  stopping PocketDesk");
    })
    .await?;

    // Removing the virtual output matters: leaving it behind would keep a
    // phantom monitor in the desktop layout.
    if let Some(mut display) = state
        .second_screen
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .take()
    {
        display.hide();
    }

    Ok(())
}

/// Ctrl+C, or `SIGTERM` from systemd/`pkill`. Both must run the cleanup below,
/// otherwise a virtual monitor would be left behind in the desktop layout.
async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut terminate = match signal(SignalKind::terminate()) {
            Ok(handle) => handle,
            Err(err) => {
                tracing::warn!("cannot listen for SIGTERM: {err}");
                let _ = tokio::signal::ctrl_c().await;
                return;
            }
        };
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        }
    }

    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

/// Re-read the monitor layout into `state.geometry`.
pub fn refresh_geometry(state: &AppState) {
    let output = state
        .settings
        .read()
        .map(|s| s.output.clone())
        .unwrap_or(None);

    match layout::detect(output.as_deref()) {
        Ok(fresh) => {
            let mut guard = state
                .geometry
                .write()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if guard.captured != fresh.captured || guard.layout != fresh.layout {
                tracing::info!("monitor layout: {}", fresh.source);
            }
            *guard = fresh;
        }
        Err(err) => tracing::debug!("monitor layout unavailable: {err:#}"),
    }
}

fn banner(cfg: &Config, state: &AppState, token: &str, usb_ready: usize) {
    let address = if cfg.bind == "127.0.0.1" || cfg.bind == "localhost" {
        "127.0.0.1".to_string()
    } else {
        config::lan_ip()
            .map(|ip| ip.to_string())
            .unwrap_or_else(|| "127.0.0.1".to_string())
    };

    let settings = state
        .settings
        .read()
        .map(|s| s.clone())
        .unwrap_or_else(|e| e.into_inner().clone());

    let display = state
        .geometry
        .read()
        .map(|g| g.source.clone())
        .unwrap_or_else(|_| "unknown".to_string());

    let mode = if state
        .second_screen
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .is_some()
    {
        "second screen (virtual monitor)"
    } else {
        "mirror"
    };

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
    println!(
        "  stream   {} · {} fps · quality {}",
        state.capture.backend, settings.fps, settings.quality
    );
    println!("  display  {display}");
    println!("  mode     {mode}");
    println!("  server   http://{address}:{}", cfg.port);
    if usb_ready > 0 {
        println!(
            "  usb      127.0.0.1:{} over adb reverse ({} device{})",
            cfg.port,
            usb_ready,
            if usb_ready == 1 { "" } else { "s" }
        );
    }
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
            println!("  QR looks solid? That terminal is eating colours — just type the code.");
            println!();
        } else {
            // Piped output would be garbled with ANSI blocks; the URI is enough.
            tracing::info!("stdout is not a terminal; pairing URI: {payload}");
        }
    }

    println!("  Phone app → scan the QR, or enter {address}:{} and the code above.", cfg.port);
    println!("  In the app: ⋯ for quality, keys and \"Use phone as second screen\".");
    println!("  Wi-Fi: stay on your LAN, or use Tailscale/WireGuard. Cable: tick \"USB cable\" in the app.");
    println!();
}
