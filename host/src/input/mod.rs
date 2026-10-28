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
    dev: Device,
}

impl Input {
    pub fn new() -> Result<Self> {
        Ok(Self {
            dev: Device::new()?,
        })
    }

    pub fn apply(&mut self, msg: &ClientMsg, geom: &Geometry) -> Result<()> {
        match msg {
            ClientMsg::Move { x, y } => {
                let (ax, ay) = geom.map(*x as f64, *y as f64);
                self.dev.move_abs(ax, ay)
            }
            ClientMsg::Click { button, count } => {
                let button = button.as_deref().unwrap_or("left");
                for _ in 0..count.unwrap_or(1).clamp(1, 3) {
                    self.dev.click(button)?;
                }
                Ok(())
            }
            ClientMsg::Button { button, down } => self.dev.button(button, *down),
            ClientMsg::Scroll { dx, dy } => self.dev.scroll(*dx, *dy),
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
