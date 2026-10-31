//! Windows input injection through `SendInput`.
//!
//! The protocol speaks in the same terms as the Linux backend: normalized
//! absolute coordinates (0..32767), relative pixels for touchpad mode, and
//! Linux key codes. Here they are translated to Win32 virtual keys and to the
//! absolute virtual-desktop coordinate space, so a multi-monitor desktop maps
//! correctly.
//!
//! `SendInput` is subject to UIPI: it cannot type into a window owned by a
//! process running as administrator unless PocketDesk is elevated too.

use anyhow::{bail, Result};

use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYEVENTF_KEYUP,
    KEYEVENTF_UNICODE, MOUSEEVENTF_ABSOLUTE, MOUSEEVENTF_HWHEEL, MOUSEEVENTF_LEFTDOWN,
    MOUSEEVENTF_LEFTUP, MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP, MOUSEEVENTF_MOVE,
    MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, MOUSEEVENTF_VIRTUALDESK, MOUSEEVENTF_WHEEL,
    MOUSEINPUT,
};

// Virtual-key codes from WinUser.h, kept here so the mapping stays explicit.
const VK_BACK: u16 = 0x08;
const VK_TAB: u16 = 0x09;
const VK_RETURN: u16 = 0x0D;
const VK_SHIFT: u16 = 0x10;
const VK_CONTROL: u16 = 0x11;
const VK_MENU: u16 = 0x12;
const VK_CAPITAL: u16 = 0x14;
const VK_ESCAPE: u16 = 0x1B;
const VK_SPACE: u16 = 0x20;
const VK_PRIOR: u16 = 0x21;
const VK_NEXT: u16 = 0x22;
const VK_END: u16 = 0x23;
const VK_HOME: u16 = 0x24;
const VK_LEFT: u16 = 0x25;
const VK_UP: u16 = 0x26;
const VK_RIGHT: u16 = 0x27;
const VK_DOWN: u16 = 0x28;
const VK_SNAPSHOT: u16 = 0x2C;
const VK_INSERT: u16 = 0x2D;
const VK_DELETE: u16 = 0x2E;
const VK_LWIN: u16 = 0x5B;
const VK_F1: u16 = 0x70;
const VK_OEM_1: u16 = 0xBA; // ;:
const VK_OEM_PLUS: u16 = 0xBB; // =+
const VK_OEM_COMMA: u16 = 0xBC;
const VK_OEM_MINUS: u16 = 0xBD;
const VK_OEM_PERIOD: u16 = 0xBE;
const VK_OEM_2: u16 = 0xBF; // /?
const VK_OEM_3: u16 = 0xC0; // `~
const VK_OEM_4: u16 = 0xDB; // [{
const VK_OEM_5: u16 = 0xDC; // \|
const VK_OEM_6: u16 = 0xDD; // ]}
const VK_OEM_7: u16 = 0xDE; // '"

/// Translate a Linux evdev key code (see `keys.rs`) to a Windows virtual key.
fn vk(code: u16) -> Option<u16> {
    use crate::keys;

    let vk = match code {
        keys::KEY_ESC => VK_ESCAPE,
        keys::KEY_TAB => VK_TAB,
        keys::KEY_ENTER => VK_RETURN,
        keys::KEY_BACKSPACE => VK_BACK,
        keys::KEY_SPACE => VK_SPACE,
        keys::KEY_CAPSLOCK => VK_CAPITAL,
        keys::KEY_MINUS => VK_OEM_MINUS,
        keys::KEY_EQUAL => VK_OEM_PLUS,
        keys::KEY_LEFTBRACE => VK_OEM_4,
        keys::KEY_RIGHTBRACE => VK_OEM_6,
        keys::KEY_SEMICOLON => VK_OEM_1,
        keys::KEY_APOSTROPHE => VK_OEM_7,
        keys::KEY_GRAVE => VK_OEM_3,
        keys::KEY_BACKSLASH => VK_OEM_5,
        keys::KEY_COMMA => VK_OEM_COMMA,
        keys::KEY_DOT => VK_OEM_PERIOD,
        keys::KEY_SLASH => VK_OEM_2,
        keys::KEY_LEFTCTRL | keys::KEY_RIGHTCTRL => VK_CONTROL,
        keys::KEY_LEFTSHIFT | keys::KEY_RIGHTSHIFT => VK_SHIFT,
        keys::KEY_LEFTALT | keys::KEY_RIGHTALT => VK_MENU,
        keys::KEY_LEFTMETA | keys::KEY_RIGHTMETA => VK_LWIN,
        keys::KEY_HOME => VK_HOME,
        keys::KEY_END => VK_END,
        keys::KEY_PAGEUP => VK_PRIOR,
        keys::KEY_PAGEDOWN => VK_NEXT,
        keys::KEY_INSERT => VK_INSERT,
        keys::KEY_DELETE => VK_DELETE,
        keys::KEY_LEFT => VK_LEFT,
        keys::KEY_RIGHT => VK_RIGHT,
        keys::KEY_UP => VK_UP,
        keys::KEY_DOWN => VK_DOWN,
        keys::KEY_SYSRQ => VK_SNAPSHOT,
        // Letters and digits: Linux lays them out by position, which VK_A..VK_Z
        // and VK_0..VK_9 already follow.
        16..=25 => b'Q' as u16 + (code - 16),  // Q..P
        30..=38 => b'A' as u16 + (code - 30),  // A..L
        44..=50 => b'Z' as u16 + (code - 44),  // Z..M
        2..=10 => b'1' as u16 + (code - 2),    // 1..9
        11 => b'0' as u16,
        keys::KEY_F1..=keys::KEY_F10 => VK_F1 + (code - keys::KEY_F1),
        keys::KEY_F11 => VK_F1 + 10,
        keys::KEY_F12 => VK_F1 + 11,
        _ => return None,
    };
    Some(vk)
}

fn mouse(dx: i32, dy: i32, data: u32, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dx,
                dy,
                mouseData: data,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

fn keyboard(vk: u16, scan: u16, flags: u32) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: scan,
                dwFlags: flags,
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

fn send(events: &[INPUT]) -> Result<()> {
    if events.is_empty() {
        return Ok(());
    }
    let sent = unsafe { SendInput(events.len() as u32, events.as_ptr(), std::mem::size_of::<INPUT>() as i32) };
    if sent as usize != events.len() {
        bail!(
            "SendInput inserted {sent} of {} events — is another process blocking input?",
            events.len()
        );
    }
    Ok(())
}

pub struct Device {
    /// Sub-pixel remainder, so slow touchpad movement is not rounded away.
    rel_x: f64,
    rel_y: f64,
}

impl Device {
    pub fn new() -> Result<Self> {
        Ok(Self {
            rel_x: 0.0,
            rel_y: 0.0,
        })
    }

    pub fn new_relative() -> Result<Self> {
        Self::new()
    }

    /// Absolute pointer. `x`/`y` are 0..32767 inside the captured frame, which
    /// is the whole virtual desktop on Windows.
    pub fn move_abs(&mut self, x: i32, y: i32) -> Result<()> {
        let x = x.clamp(0, 32767) as i64 * 65535 / 32767;
        let y = y.clamp(0, 32767) as i64 * 65535 / 32767;
        self.rel_x = 0.0;
        self.rel_y = 0.0;
        send(&[mouse(
            x as i32,
            y as i32,
            0,
            MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
        )])
    }

    pub fn move_rel(&mut self, dx: f32, dy: f32) -> Result<()> {
        if !dx.is_finite() || !dy.is_finite() {
            return Ok(());
        }
        self.rel_x = (self.rel_x + dx.clamp(-2000.0, 2000.0) as f64).clamp(-5000.0, 5000.0);
        self.rel_y = (self.rel_y + dy.clamp(-2000.0, 2000.0) as f64).clamp(-5000.0, 5000.0);

        let step_x = self.rel_x.round() as i32;
        let step_y = self.rel_y.round() as i32;
        if step_x == 0 && step_y == 0 {
            return Ok(());
        }
        self.rel_x -= step_x as f64;
        self.rel_y -= step_y as f64;
        send(&[mouse(step_x, step_y, 0, MOUSEEVENTF_MOVE)])
    }

    fn flags(name: &str, down: bool) -> Option<u32> {
        let flags = match name.trim().to_ascii_lowercase().as_str() {
            "left" | "l" => {
                if down {
                    MOUSEEVENTF_LEFTDOWN
                } else {
                    MOUSEEVENTF_LEFTUP
                }
            }
            "right" | "r" => {
                if down {
                    MOUSEEVENTF_RIGHTDOWN
                } else {
                    MOUSEEVENTF_RIGHTUP
                }
            }
            "middle" | "m" | "wheel" => {
                if down {
                    MOUSEEVENTF_MIDDLEDOWN
                } else {
                    MOUSEEVENTF_MIDDLEUP
                }
            }
            _ => return None,
        };
        Some(flags)
    }

    pub fn button(&mut self, name: &str, down: bool) -> Result<()> {
        let flags =
            Self::flags(name, down).ok_or_else(|| anyhow::anyhow!("unknown mouse button `{name}`"))?;
        send(&[mouse(0, 0, 0, flags)])
    }

    pub fn click(&mut self, name: &str) -> Result<()> {
        let down =
            Self::flags(name, true).ok_or_else(|| anyhow::anyhow!("unknown mouse button `{name}`"))?;
        let up =
            Self::flags(name, false).ok_or_else(|| anyhow::anyhow!("unknown mouse button `{name}`"))?;
        send(&[mouse(0, 0, 0, down), mouse(0, 0, 0, up)])
    }

    pub fn scroll(&mut self, dx: f32, dy: f32) -> Result<()> {
        // Phone convention: dy > 0 means the finger moved down, i.e. wheel down.
        let vertical = (-dy).round() as i32;
        let horizontal = dx.round() as i32;

        let mut events = Vec::with_capacity(2);
        if vertical != 0 {
            events.push(mouse(0, 0, (vertical * 120) as u32, MOUSEEVENTF_WHEEL));
        }
        if horizontal != 0 {
            events.push(mouse(0, 0, (horizontal * 120) as u32, MOUSEEVENTF_HWHEEL));
        }
        send(&events)
    }

    pub fn key(&mut self, code: u16, down: bool) -> Result<()> {
        let vk = vk(code).ok_or_else(|| anyhow::anyhow!("key code {code} has no Windows mapping"))?;
        let flags = if down { 0 } else { KEYEVENTF_KEYUP };
        send(&[keyboard(vk, 0, flags)])
    }

    pub fn keys_down(&mut self, codes: &[u16]) -> Result<()> {
        for code in codes {
            self.key(*code, true)?;
        }
        Ok(())
    }

    pub fn keys_up(&mut self, codes: &[u16]) -> Result<()> {
        for code in codes {
            self.key(*code, false)?;
        }
        Ok(())
    }

    pub fn tap_with_mods(&mut self, mods: &[u16], code: u16) -> Result<()> {
        self.keys_down(mods)?;
        let pressed = self.key(code, true).and_then(|()| self.key(code, false));
        let released = self.keys_up(mods);
        pressed.and(released)
    }

    pub fn type_text_with(&mut self, text: &str, mods: &[String]) -> Result<()> {
        let held: Vec<u16> = mods
            .iter()
            .filter_map(|m| crate::keys::modifier(m))
            .collect();
        self.keys_down(&held)?;

        let mut result = Ok(());
        for ch in text.chars() {
            if ch == '\n' || ch == '\r' {
                result = self.key(crate::keys::KEY_ENTER, true)
                    .and_then(|()| self.key(crate::keys::KEY_ENTER, false));
            } else if ch == '\t' {
                result = self.key(crate::keys::KEY_TAB, true)
                    .and_then(|()| self.key(crate::keys::KEY_TAB, false));
            } else {
                // KEYEVENTF_UNICODE types the character itself, independent of
                // the keyboard layout. Characters above the BMP become a
                // surrogate pair, which is exactly what the API expects.
                let mut units = [0u16; 2];
                for unit in ch.encode_utf16(&mut units) {
                    let scan = *unit;
                    result = send(&[
                        keyboard(0, scan, KEYEVENTF_UNICODE),
                        keyboard(0, scan, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP),
                    ]);
                    if result.is_err() {
                        break;
                    }
                }
            }
            if result.is_err() {
                break;
            }
        }

        let released = self.keys_up(&held);
        result.and(released)
    }
}
