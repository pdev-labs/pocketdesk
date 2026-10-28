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

    /// Do not print the pairing QR code in the terminal.
    #[arg(long)]
    pub no_qr: bool,

    /// Create a virtual monitor of this size (e.g. `1600x900`) and stream it, so
    /// the phone acts as a second screen instead of a copy of the desktop.
    /// Hyprland only. The phone can turn this on later from its ⋯ menu.
    #[arg(long, value_parser = parse_size, value_name = "WxH")]
    pub extend: Option<(u32, u32)>,

    /// Show verbose logs.
    #[arg(long)]
    pub verbose: bool,
}

impl Config {
    pub fn addr(&self) -> String {
        format!("{}:{}", self.bind, self.port)
    }
}

/// `1600x900` -> `(1600, 900)`, with a range check so a typo cannot ask the
/// compositor for a 1x1 or 99999x99999 monitor.
fn parse_size(value: &str) -> Result<(u32, u32), String> {
    let (w, h) = value
        .split_once(['x', 'X'])
        .ok_or_else(|| format!("expected WIDTHxHEIGHT, e.g. 1600x900 (got {value})"))?;
    let w: u32 = w
        .trim()
        .parse()
        .map_err(|_| format!("bad width: {w}"))?;
    let h: u32 = h
        .trim()
        .parse()
        .map_err(|_| format!("bad height: {h}"))?;
    if !(320..=3840).contains(&w) || !(240..=2160).contains(&h) {
        return Err("size out of range (320x240 … 3840x2160)".to_string());
    }
    Ok((w, h))
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
///
/// Tokens saved by 0.1 were 64 hex characters: unusable to type and dense in a
/// QR. They are rotated to a short code once, with a warning, so upgraded
/// installs get the same experience as fresh ones.
pub fn load_or_create_token() -> Result<String> {
    match read_token()? {
        Some(existing) if is_short_code(&existing) => Ok(existing),
        Some(_legacy) => {
            let fresh = random_token();
            save_token(&fresh)?;
            tracing::warn!(
                "the saved pairing code was from an older version; rotated it to {} — re-scan the QR in the app",
                pretty_token(&fresh)
            );
            Ok(fresh)
        }
        None => {
            let token = random_token();
            save_token(&token)?;
            Ok(token)
        }
    }
}

/// True when a stored value is already a 0.2-style short code.
pub fn is_short_code(token: &str) -> bool {
    let normalized = normalize_token(token);
    normalized.len() == TOKEN_LEN && normalized.bytes().all(|byte| ALPHABET.contains(&byte))
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

/// Pairing codes are short enough to read out loud: 8 characters picked from a
/// 31-symbol alphabet (~40 bits), shown as `K7M4-QX2B`. Wrong guesses are
/// throttled per address, and most people pair by scanning the QR anyway.
pub const TOKEN_LEN: usize = 8;

/// No 0/O, 1/I/L: all the characters that get mistyped.
const ALPHABET: &[u8] = b"23456789ABCDEFGHJKMNPQRSTUVWXYZ";

pub fn random_token() -> String {
    use rand::Rng;
    let mut rng = rand::thread_rng();
    (0..TOKEN_LEN)
        .map(|_| ALPHABET[rng.gen_range(0..ALPHABET.len())] as char)
        .collect()
}

/// Accept `k7m4-qx2b`, `K7M4 QX2B` or `K7M4QX2B` and compare only the useful
/// characters. Tokens saved by older versions (64 hex chars) still work.
pub fn normalize_token(input: &str) -> String {
    input
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect()
}

/// Grouped for humans: `K7M4-QX2B`.
pub fn pretty_token(token: &str) -> String {
    let token = normalize_token(token);
    let mut out = String::with_capacity(token.len() + token.len() / 4);
    for (index, ch) in token.chars().enumerate() {
        if index > 0 && index % 4 == 0 {
            out.push('-');
        }
        out.push(ch);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_are_short_and_unambiguous() {
        for _ in 0..64 {
            let token = random_token();
            assert_eq!(token.len(), TOKEN_LEN);
            assert!(token.bytes().all(|b| ALPHABET.contains(&b)));
            assert!(!token.chars().any(|c| "0O1IL".contains(c)));
        }
    }

    #[test]
    fn detects_short_codes_and_legacy_tokens() {
        assert!(is_short_code("K7M4QX2B"));
        assert!(is_short_code("k7m4-qx2b"));
        assert!(!is_short_code("0B36057DCEF0ABF02DE2A1034A2C80C0ED18680D4BE7D2CF2DF7489374461F64"));
        assert!(!is_short_code("K7M4QX2"));
        assert!(!is_short_code("K7M4QX2B9"));
        assert!(!is_short_code(""));
    }

    #[test]
    fn normalization_accepts_typed_variants() {
        assert_eq!(normalize_token("k7m4-qx2b"), "K7M4QX2B");
        assert_eq!(normalize_token(" K7M4 QX2B "), "K7M4QX2B");
        assert_eq!(pretty_token("K7M4QX2B"), "K7M4-QX2B");
        assert_eq!(pretty_token("k7m4-qx2b"), "K7M4-QX2B");
        assert_eq!(pretty_token(""), "");
    }

    #[test]
    fn parses_extend_sizes() {
        assert_eq!(parse_size("1600x900"), Ok((1600, 900)));
        assert_eq!(parse_size("1920X1080"), Ok((1920, 1080)));
        assert!(parse_size("1600").is_err());
        assert!(parse_size("1600x0").is_err());
        assert!(parse_size("99999x900").is_err());
        assert!(parse_size("axb").is_err());
    }
}
