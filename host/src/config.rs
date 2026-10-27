//! Command line configuration + token persistence.

use std::{
    fs,
    io::Write,
    path::PathBuf,
};

use anyhow::{Context, Result};
use clap::{Parser, ValueEnum};

/// How the screen is captured.
#[derive(Clone, Copy, Debug, PartialEq, Eq, ValueEnum)]
pub enum Backend {
    /// Pick the best backend for this session (grim on Wayland, ffmpeg otherwise).
    Auto,
    /// Wayland (wlroots: Hyprland, Sway) via grim. Also works on X11 through XWayland-less setups.
    Grim,
    /// ffmpeg screen grabber: X11 (x11grab), Windows (gdigrab), macOS (avfoundation).
    Ffmpeg,
    /// Synthetic moving test pattern. No display needed; handy for trying the app.
    Test,
}

#[derive(Clone, Debug, Parser)]
#[command(
    name = "pocketdesk",
    version,
    about = "Stream this machine's screen to your phone, and drive it from there.",
    long_about = None
)]
pub struct Config {
    /// Address to listen on. Use 0.0.0.0 for LAN, 127.0.0.1 behind an SSH tunnel.
    #[arg(long, default_value = "0.0.0.0", env = "POCKETDESK_BIND")]
    pub bind: String,

    /// TCP port for the HTTP/WebSocket server.
    #[arg(long, default_value_t = 8765, env = "POCKETDESK_PORT")]
    pub port: u16,

    /// Pairing token. Defaults to the one stored in ~/.config/pocketdesk/token.
    #[arg(long, env = "POCKETDESK_TOKEN")]
    pub token: Option<String>,

    /// Capture backend.
    #[arg(long, value_enum, default_value_t = Backend::Auto)]
    pub capture: Backend,

    /// Target frames per second. Capture is paced, never queued.
    #[arg(long, default_value_t = 15)]
    pub fps: u32,

    /// JPEG quality, 1-100. Lower = smaller frames, more compression artifacts.
    #[arg(long, default_value_t = 75)]
    pub quality: u8,

    /// Downscale the captured image (grim only). 0.5 halves the resolution and bandwidth.
    #[arg(long, default_value_t = 1.0)]
    pub scale: f64,

    /// Monitor to stream, e.g. eDP-1 or HDMI-A-1. Defaults to the focused monitor.
    #[arg(long)]
    pub output: Option<String>,

    /// Do not draw the mouse cursor into the stream.
    #[arg(long)]
    pub no_cursor: bool,

    /// Stream only: never inject keyboard or mouse input.
    #[arg(long)]
    pub read_only: bool,

    /// Print the pairing token and exit.
    #[arg(long)]
    pub print_token: bool,

    /// Show verbose logs.
    #[arg(long)]
    pub verbose: bool,
}

impl Config {
    pub fn addr(&self) -> String {
        format!("{}:{}", self.bind, self.port)
    }
}

/// `$XDG_CONFIG_HOME/pocketdesk` or `~/.config/pocketdesk`.
pub fn config_dir() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
        .unwrap_or_else(|| PathBuf::from(".config"));
    base.join("pocketdesk")
}

fn token_path() -> PathBuf {
    config_dir().join("token")
}

/// Read the saved token, creating one on first run.
pub fn load_or_create_token() -> Result<String> {
    if let Some(token) = read_token()? {
        return Ok(token);
    }
    let token = random_token();
    save_token(&token)?;
    Ok(token)
}

pub fn read_token() -> Result<Option<String>> {
    match fs::read_to_string(token_path()) {
        Ok(s) => {
            let s = s.trim().to_string();
            Ok(if s.is_empty() { None } else { Some(s) })
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e).with_context(|| format!("reading {}", token_path().display())),
    }
}

fn save_token(token: &str) -> Result<()> {
    let dir = config_dir();
    fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
    let path = token_path();
    let mut file = fs::File::create(&path).with_context(|| format!("creating {}", path.display()))?;
    // Owner-only before the secret is written.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    file.write_all(token.as_bytes())?;
    file.write_all(b"\n")?;
    Ok(())
}

/// 32 random bytes, hex encoded.
pub fn random_token() -> String {
    use rand::RngCore;
    let mut bytes = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    let mut out = String::with_capacity(64);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

/// Best-effort local IPv4 so the banner can print a URL you can type on the phone.
pub fn lan_ip() -> Option<std::net::IpAddr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    // No packets are sent; this only asks the kernel which source address it would use.
    socket.connect("192.0.2.1:9").ok()?;
    let ip = socket.local_addr().ok()?.ip();
    if ip.is_loopback() {
        None
    } else {
        Some(ip)
    }
}
