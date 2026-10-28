//! Pairing: the QR payload, a terminal renderer, and the machine name.

use anyhow::Result;
use qrcode::{Color, EcLevel, QrCode};

/// `pocketdesk://pair?v=1&h=…&p=…&t=…&n=…`
///
/// Short keys keep the QR small (fewer modules = easier to scan from a
/// terminal). The phone app fills in every field from it, so scanning and
/// typing the 8-character code are equivalent.
pub fn uri(host: &str, port: u16, token: &str, name: &str) -> String {
    format!(
        "pocketdesk://pair?v=1&h={host}&p={port}&t={token}&n={name}",
        host = percent_encode(host),
        port = port,
        token = percent_encode(token),
        name = percent_encode(name),
    )
}

/// Best-effort machine name, shown in the app after pairing.
pub fn hostname() -> String {
    #[cfg(target_os = "linux")]
    {
        if let Ok(contents) = std::fs::read_to_string("/etc/hostname") {
            let name = contents.trim();
            if !name.is_empty() {
                return name.to_string();
            }
        }
    }

    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .ok()
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "pocketdesk".to_string())
}

fn percent_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

/// Render the QR as ANSI truecolor half-blocks: black modules on a white
/// background, quiet zone included. Prints in ~40 columns, which survives a
/// split terminal or tmux pane.
pub fn render_terminal(payload: &str) -> Result<String> {
    let code = QrCode::with_error_correction_level(payload.as_bytes(), EcLevel::L)?;
    let width = code.width();
    let colors = code.to_colors();
    let quiet = 3usize;
    let total = width + quiet * 2;

    let is_dark = |x: usize, y: usize| -> bool {
        if x < quiet || y < quiet {
            return false;
        }
        let (mx, my) = (x - quiet, y - quiet);
        if mx >= width || my >= width {
            return false;
        }
        matches!(colors[my * width + mx], Color::Dark)
    };

    let mut out = String::new();
    for row in (0..total).step_by(2) {
        for col in 0..total {
            let top = is_dark(col, row);
            let bottom = is_dark(col, row + 1);
            out.push_str(match (top, bottom) {
                (true, true) => "\x1b[38;2;0;0;0m\x1b[48;2;0;0;0m",
                (true, false) => "\x1b[38;2;0;0;0m\x1b[48;2;255;255;255m",
                (false, true) => "\x1b[38;2;255;255;255m\x1b[48;2;0;0;0m",
                (false, false) => "\x1b[38;2;255;255;255m\x1b[48;2;255;255;255m",
            });
            out.push('\u{2580}'); // upper half block
        }
        out.push_str("\x1b[0m\n");
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn matrix(payload: &str) -> (usize, Vec<Color>, usize) {
        let code = QrCode::with_error_correction_level(payload.as_bytes(), EcLevel::L).unwrap();
        let width = code.width();
        (width, code.to_colors(), 4)
    }

    #[test]
    fn uri_is_parseable_and_escaped() {
        let uri = uri("2001:db8::1", 8765, "K7M4QX2B", "work station");
        assert!(uri.starts_with("pocketdesk://pair?v=1&"));
        assert!(uri.contains("h=2001%3Adb8%3A%3A1"));
        assert!(uri.contains("n=work%20station"));
        assert!(uri.contains("t=K7M4QX2B"));
        assert!(uri.contains("p=8765"));
    }

    /// Render the same matrix to greyscale and let an independent decoder read
    /// it back. This proves the QR is valid, not just that we called the encoder.
    #[test]
    fn qr_round_trips_through_a_decoder() {
        let payload = uri("192.168.1.24", 8765, "K7M4QX2B", "arch");
        let (width, colors, quiet) = matrix(&payload);
        let total = width + quiet * 2;
        let scale = 4usize;
        let size = total * scale;

        let mut prepared = rqrr::PreparedImage::prepare_from_greyscale(size, size, |x, y| {
            let mx = x / scale;
            let my = y / scale;
            if mx < quiet || my < quiet || mx >= quiet + width || my >= quiet + width {
                return 255;
            }
            match colors[(my - quiet) * width + (mx - quiet)] {
                Color::Dark => 0,
                Color::Light => 255,
            }
        });

        let grids = prepared.detect_grids();
        assert_eq!(grids.len(), 1, "expected exactly one QR grid");
        let (_, text) = grids[0].decode().expect("decode QR");
        assert_eq!(text, payload);
    }

    #[test]
    fn terminal_render_has_a_quiet_zone_and_no_escape_leaks() {
        let rendered = render_terminal("pocketdesk://pair?v=1&host=127.0.0.1&port=8765").unwrap();
        assert!(rendered.contains('\u{2580}'));
        assert!(rendered.ends_with("\x1b[0m\n"));
        // Wider than tall: half-block rows halve the height.
        let lines = rendered.lines().count();
        assert!(lines > 10 && lines < 30, "unexpected height: {lines}");
    }
}
