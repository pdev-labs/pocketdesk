//! Wire protocol (client -> host). Everything is JSON text frames; screen
//! frames travel as binary WebSocket messages.

use serde::Deserialize;

#[derive(Debug, Deserialize)]
#[serde(tag = "t", rename_all = "snake_case")]
pub enum ClientMsg {
    /// Move the pointer. Coordinates are normalized inside the streamed frame.
    Move { x: f32, y: f32 },
    /// Move the pointer a relative number of desktop pixels (touchpad mode).
    MoveRel { dx: f32, dy: f32 },
    /// Press and release a button once, at the current pointer position.
    Click {
        #[serde(default)]
        button: Option<String>,
        #[serde(default)]
        count: Option<u8>,
        /// Use the relative (touchpad) device instead of the absolute one.
        #[serde(default)]
        pad: bool,
    },
    /// Press or release a button without moving (used for drags).
    Button {
        button: String,
        down: bool,
        #[serde(default)]
        pad: bool,
    },
    /// Scroll wheel. `dy` is in wheel notches; positive means "finger moved down".
    Scroll {
        #[serde(default)]
        dx: f32,
        dy: f32,
        #[serde(default)]
        pad: bool,
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
    /// Stop sending frames to this client (app in the background). The socket,
    /// stats and pings stay up, so resuming is instant.
    Pause,
    /// Resume frames after [`Pause`]; the latest frame is sent immediately.
    Resume,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relative_moves_parse() {
        let msg: ClientMsg = serde_json::from_str(r#"{"t":"move_rel","dx":12.5,"dy":-3}"#)
            .expect("move_rel");
        match msg {
            ClientMsg::MoveRel { dx, dy } => {
                assert!((dx - 12.5).abs() < f32::EPSILON);
                assert!((dy + 3.0).abs() < f32::EPSILON);
            }
            other => panic!("expected MoveRel, got {other:?}"),
        }
    }

    #[test]
    fn touchpad_buttons_carry_the_pad_flag() {
        let msg: ClientMsg = serde_json::from_str(
            r#"{"t":"button","button":"left","down":true,"pad":true}"#,
        )
        .expect("pad button");
        match msg {
            ClientMsg::Button { button, down, pad } => {
                assert_eq!(button, "left");
                assert!(down);
                assert!(pad);
            }
            other => panic!("expected Button, got {other:?}"),
        }
    }

    #[test]
    fn older_clients_without_pad_default_to_the_absolute_pointer() {
        let msg: ClientMsg =
            serde_json::from_str(r#"{"t":"click","button":"right"}"#).expect("legacy click");
        match msg {
            ClientMsg::Click { pad, .. } => assert!(!pad),
            other => panic!("expected Click, got {other:?}"),
        }
    }
}
