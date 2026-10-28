//! Wire protocol (client -> host). Everything is JSON text frames; screen
//! frames travel as binary WebSocket messages.

use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ClientMsg {
    /// Move the pointer. Coordinates are normalized inside the streamed frame.
    Move { x: f32, y: f32 },
    /// Press and release a button once, at the current pointer position.
    Click {
        #[serde(default)]
        button: Option<String>,
        #[serde(default)]
        count: Option<u8>,
    },
    /// Press or release a button without moving (used for drags).
    Button { button: String, down: bool },
    /// Scroll wheel. `dy` is in wheel notches; positive means "finger moved down".
    Scroll {
        #[serde(default)]
        dx: f32,
        dy: f32,
    },
    /// A single key. `mods` are held for the duration of the key.
    Key {
        name: String,
        #[serde(default)]
        mods: Vec<String>,
        #[serde(default)]
        action: Option<KeyAction>,
    },
    /// Type a string using the US layout (shift is handled automatically).
    Text { s: String },
    /// Reconfigure the stream live. Any subset may be sent; the host replies
    /// with the settings it actually applied (they are clamped).
    SetQuality {
        #[serde(default)]
        fps: Option<u32>,
        #[serde(default)]
        quality: Option<u8>,
        #[serde(default)]
        scale: Option<f64>,
    },
    /// `mode: "mirror"` streams the physical monitor; `mode: "extend"` creates
    /// (or resizes) a virtual monitor of `width`x`height` and streams that, so
    /// the phone is a second display rather than a copy.
    SetDisplay {
        mode: String,
        #[serde(default)]
        width: Option<u32>,
        #[serde(default)]
        height: Option<u32>,
    },
    /// Keep-alive probe; answered with `{"t":"pong"}`.
    Ping,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeyAction {
    Tap,
    Down,
    Up,
}
