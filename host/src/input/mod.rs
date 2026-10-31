//! Input injection: turn protocol messages into real pointer/keyboard events.

use anyhow::Result;

use crate::{
    keys,
    layout::Geometry,
    protocol::{ClientMsg, KeyAction},
};

#[cfg(target_os = "linux")]
mod uinput;

#[cfg(target_os = "linux")]
use uinput::Device;

#[cfg(windows)]
mod windows;

#[cfg(windows)]
use windows::Device;

#[cfg(not(any(target_os = "linux", windows)))]
mod stub;

#[cfg(not(any(target_os = "linux", windows)))]
use stub::Device;

pub struct Input {
    /// Absolute pointer (direct touch) and full keyboard.
    dev: Device,
    /// Relative pointer for touchpad mode; absent if uinput only allowed one.
    pad: Option<Device>,
}

impl Input {
    pub fn new() -> Result<Self> {
        let dev = Device::new()?;
        let pad = match Device::new_relative() {
            Ok(pad) => Some(pad),
            Err(err) => {
                tracing::warn!("touchpad device unavailable: {err:#}");
                None
            }
        };
        Ok(Self { dev, pad })
    }

    /// Which device a pointer message belongs to.
    fn target(&mut self, pad: bool) -> &mut Device {
        if pad {
            self.pad.as_mut().unwrap_or(&mut self.dev)
        } else {
            &mut self.dev
        }
    }

    pub fn apply(&mut self, msg: &ClientMsg, geom: &Geometry) -> Result<()> {
        match msg {
            ClientMsg::Move { x, y } => {
                let (ax, ay) = geom.map(*x as f64, *y as f64);
                self.dev.move_abs(ax, ay)
            }
            ClientMsg::MoveRel { dx, dy } => match self.pad.as_mut() {
                Some(pad) => pad.move_rel(*dx, *dy),
                // Without a relative device the best fallback is to stay put;
                // buttons and scrolling still work through the absolute one.
                None => Ok(()),
            },
            ClientMsg::Click {
                button,
                count,
                pad,
            } => {
                let button = button.as_deref().unwrap_or("left");
                let device = self.target(*pad);
                for _ in 0..count.unwrap_or(1).clamp(1, 3) {
                    device.click(button)?;
                }
                Ok(())
            }
            ClientMsg::Button {
                button,
                down,
                pad,
            } => self.target(*pad).button(button, *down),
            ClientMsg::Scroll { dx, dy, pad } => self.target(*pad).scroll(*dx, *dy),
            ClientMsg::Key {
                name,
                mods,
                action,
            } => {
                // `shift`, `ctrl`, … are valid key names too (the protocol
                // documents `{"t":"key","name":"shift","action":"down"}`), and
                // so is any single printable character ("s", "?", "7").
                let (code, implicit_shift) = if let Some(code) = keys::by_name(name) {
                    (code, false)
                } else if let Some(code) = keys::modifier(name) {
                    (code, false)
                } else if let Some((code, shift)) = keys::by_char(name) {
                    (code, shift)
                } else {
                    anyhow::bail!("unknown key name `{name}`");
                };
                let mut mods: Vec<u16> =
                    mods.iter().filter_map(|m| keys::modifier(m)).collect();
                if implicit_shift && !mods.contains(&keys::KEY_LEFTSHIFT) {
                    mods.insert(0, keys::KEY_LEFTSHIFT);
                }
                match action.unwrap_or(KeyAction::Tap) {
                    KeyAction::Tap => self.dev.tap_with_mods(&mods, code),
                    KeyAction::Down => {
                        self.dev.keys_down(&mods)?;
                        self.dev.key(code, true)
                    }
                    KeyAction::Up => {
                        self.dev.key(code, false)?;
                        self.dev.keys_up(&mods)
                    }
                }
            }
            ClientMsg::Text { s, mods } => self.dev.type_text_with(s, mods),
            ClientMsg::Ping => Ok(()),
            // Handled by the server before input injection: they change the
            // stream, not the desktop.
            ClientMsg::SetQuality { .. }
            | ClientMsg::SetDisplay { .. }
            | ClientMsg::Pause
            | ClientMsg::Resume => Ok(()),
        }
    }
}
