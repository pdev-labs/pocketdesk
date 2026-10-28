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

#[cfg(not(target_os = "linux"))]
mod stub;

#[cfg(not(target_os = "linux"))]
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
                // documents `{"t":"key","name":"shift","action":"down"}`).
                let code = keys::by_name(name)
                    .or_else(|| keys::modifier(name))
                    .ok_or_else(|| anyhow::anyhow!("unknown key name `{name}`"))?;
                let mods: Vec<u16> = mods.iter().filter_map(|m| keys::modifier(m)).collect();
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
            ClientMsg::Text { s } => self.dev.type_text(s),
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
