//! Linux input injection through `/dev/uinput`.
//!
//! We create one virtual device that is both an absolute pointer and a full
//! keyboard. Absolute axes are scaled to 0..32767 and mapped across the whole
//! desktop layout, which is how libinput/wlroots place absolute pointing
//! devices. Works on Wayland (Hyprland, Sway, KDE, GNOME) and on X11.
//!
//! The raw ioctl dance is deliberate: it is ~100 lines and avoids pulling in a
//! C library or a crate whose uinput API changes between releases.

use std::{
    collections::HashSet,
    fs::{File, OpenOptions},
    os::unix::io::{AsRawFd, RawFd},
    time::Duration,
};

use anyhow::{bail, Context, Result};

use crate::keys;

const EV_SYN: u16 = 0;
const EV_KEY: u16 = 1;
const EV_REL: u16 = 2;
const EV_ABS: u16 = 3;

const SYN_REPORT: u16 = 0;

const ABS_X: u16 = 0;
const ABS_Y: u16 = 1;

const REL_HWHEEL: u16 = 6;
const REL_WHEEL: u16 = 8;

const BTN_LEFT: u16 = 0x110;
const BTN_RIGHT: u16 = 0x111;
const BTN_MIDDLE: u16 = 0x112;

const ABS_MAX: i32 = 32767;
const BUS_VIRTUAL: u16 = 0x06;

// --- ioctl encoding (asm-generic/ioctl.h) -----------------------------------
const IOC_WRITE: u32 = 1 << 30;

const fn iow(kind: u32, nr: u32, size: u32) -> libc::c_ulong {
    (IOC_WRITE | (size << 16) | (kind << 8) | nr) as libc::c_ulong
}

const fn io(kind: u32, nr: u32) -> libc::c_ulong {
    ((kind << 8) | nr) as libc::c_ulong
}

const UINPUT_BASE: u32 = b'U' as u32;

const UI_SET_EVBIT: libc::c_ulong = iow(UINPUT_BASE, 100, 4);
const UI_SET_KEYBIT: libc::c_ulong = iow(UINPUT_BASE, 101, 4);
const UI_SET_RELBIT: libc::c_ulong = iow(UINPUT_BASE, 102, 4);
const UI_SET_ABSBIT: libc::c_ulong = iow(UINPUT_BASE, 103, 4);
const UI_DEV_CREATE: libc::c_ulong = io(UINPUT_BASE, 1);
const UI_DEV_DESTROY: libc::c_ulong = io(UINPUT_BASE, 2);
const UI_DEV_SETUP: libc::c_ulong = iow(UINPUT_BASE, 3, std::mem::size_of::<UinputSetup>() as u32);
const UI_ABS_SETUP: libc::c_ulong =
    iow(UINPUT_BASE, 4, std::mem::size_of::<UinputAbsSetup>() as u32);

#[repr(C)]
#[derive(Clone, Copy)]
struct InputEvent {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct InputId {
    bustype: u16,
    vendor: u16,
    product: u16,
    version: u16,
}

#[repr(C)]
struct UinputSetup {
    id: InputId,
    name: [libc::c_char; 80],
    ff_effects_max: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Default)]
struct InputAbsInfo {
    value: i32,
    minimum: i32,
    maximum: i32,
    fuzz: i32,
    flat: i32,
    resolution: i32,
}

#[repr(C)]
struct UinputAbsSetup {
    code: u16,
    absinfo: InputAbsInfo,
}

fn event(type_: u16, code: u16, value: i32) -> InputEvent {
    InputEvent {
        time: libc::timeval {
            tv_sec: 0,
            tv_usec: 0,
        },
        type_,
        code,
        value,
    }
}

fn button_code(name: &str) -> Option<u16> {
    match name.trim().to_ascii_lowercase().as_str() {
        "left" | "l" => Some(BTN_LEFT),
        "right" | "r" => Some(BTN_RIGHT),
        "middle" | "m" | "wheel" => Some(BTN_MIDDLE),
        _ => None,
    }
}

pub struct Device {
    // Holding the File keeps the fd open; dropping it destroys the device.
    file: File,
    fd: RawFd,
    advertised: HashSet<u16>,
}

impl Device {
    pub fn new() -> Result<Self> {
        let file = OpenOptions::new()
            .write(true)
            .open("/dev/uinput")
            .map_err(|e| {
                if e.kind() == std::io::ErrorKind::PermissionDenied {
                    anyhow::anyhow!(
                        "cannot open /dev/uinput: permission denied.\n       \
                         Run `sudo scripts/setup-linux-input.sh` once, then log out and back in.\n       \
                         Screen mirroring works without this; only remote input is disabled."
                    )
                } else if e.kind() == std::io::ErrorKind::NotFound {
                    anyhow::anyhow!(
                        "/dev/uinput does not exist. Load the module with `sudo modprobe uinput`."
                    )
                } else {
                    anyhow::Error::new(e).context("opening /dev/uinput")
                }
            })?;

        let fd = file.as_raw_fd();

        // Which event types the device reports.
        unsafe {
            ioctl_int(fd, UI_SET_EVBIT, EV_KEY as i32)?;
            ioctl_int(fd, UI_SET_EVBIT, EV_ABS as i32)?;
            ioctl_int(fd, UI_SET_EVBIT, EV_REL as i32)?;

            for code in keys::all_codes() {
                ioctl_int(fd, UI_SET_KEYBIT, code as i32)?;
            }
            for code in [BTN_LEFT, BTN_RIGHT, BTN_MIDDLE] {
                ioctl_int(fd, UI_SET_KEYBIT, code as i32)?;
            }

            ioctl_int(fd, UI_SET_ABSBIT, ABS_X as i32)?;
            ioctl_int(fd, UI_SET_ABSBIT, ABS_Y as i32)?;
            ioctl_int(fd, UI_SET_RELBIT, REL_WHEEL as i32)?;
            ioctl_int(fd, UI_SET_RELBIT, REL_HWHEEL as i32)?;

            for code in [ABS_X, ABS_Y] {
                let setup = UinputAbsSetup {
                    code,
                    absinfo: InputAbsInfo {
                        value: 0,
                        minimum: 0,
                        maximum: ABS_MAX,
                        fuzz: 0,
                        flat: 0,
                        resolution: 0,
                    },
                };
                if libc::ioctl(fd, UI_ABS_SETUP, &setup) < 0 {
                    return Err(std::io::Error::last_os_error())
                        .context("UI_ABS_SETUP (kernel too old for uinput abs setup?)");
                }
            }

            let mut setup: UinputSetup = std::mem::zeroed();
            setup.id = InputId {
                bustype: BUS_VIRTUAL,
                vendor: 0x1209,
                product: 0x0001,
                version: 1,
            };
            let name = b"PocketDesk Virtual Input";
            for (slot, byte) in setup.name.iter_mut().zip(name.iter()) {
                *slot = *byte as libc::c_char;
            }
            if libc::ioctl(fd, UI_DEV_SETUP, &setup) < 0 {
                return Err(std::io::Error::last_os_error()).context("UI_DEV_SETUP");
            }
            if libc::ioctl(fd, UI_DEV_CREATE) < 0 {
                return Err(std::io::Error::last_os_error()).context("UI_DEV_CREATE");
            }
        }

        // Give udev/libinput a moment to pick the device up before the first event.
        std::thread::sleep(Duration::from_millis(150));

        Ok(Self {
            file,
            fd,
            advertised: keys::all_codes().into_iter().collect(),
        })
    }

    fn emit(&mut self, events: &[InputEvent]) -> Result<()> {
        let bytes = std::mem::size_of_val(events);
        let written = unsafe {
            libc::write(
                self.fd,
                events.as_ptr() as *const libc::c_void,
                bytes,
            )
        };
        if written < 0 {
            return Err(std::io::Error::last_os_error()).context("writing input event to uinput");
        }
        Ok(())
    }

    fn sync(&mut self) -> Result<()> {
        self.emit(&[event(EV_SYN, SYN_REPORT, 0)])
    }

    pub fn move_abs(&mut self, x: i32, y: i32) -> Result<()> {
        let x = x.clamp(0, ABS_MAX);
        let y = y.clamp(0, ABS_MAX);
        self.emit(&[
            event(EV_ABS, ABS_X, x),
            event(EV_ABS, ABS_Y, y),
            event(EV_SYN, SYN_REPORT, 0),
        ])
    }

    pub fn button(&mut self, name: &str, down: bool) -> Result<()> {
        let code = button_code(name)
            .ok_or_else(|| anyhow::anyhow!("unknown mouse button `{name}`"))?;
        self.emit(&[event(EV_KEY, code, i32::from(down))])?;
        self.sync()
    }

    pub fn click(&mut self, name: &str) -> Result<()> {
        let code = button_code(name)
            .ok_or_else(|| anyhow::anyhow!("unknown mouse button `{name}`"))?;
        self.emit(&[
            event(EV_KEY, code, 1),
            event(EV_SYN, SYN_REPORT, 0),
            event(EV_KEY, code, 0),
            event(EV_SYN, SYN_REPORT, 0),
        ])
    }

    pub fn scroll(&mut self, dx: f32, dy: f32) -> Result<()> {
        // Phone convention: dy > 0 means the finger moved down, i.e. wheel down.
        let mut vertical = (-dy).round() as i32;
        if vertical == 0 && dy.abs() > 0.15 {
            vertical = if dy > 0.0 { -1 } else { 1 };
        }
        let horizontal = dx.round() as i32;

        let mut events = Vec::with_capacity(4);
        if vertical != 0 {
            events.push(event(EV_REL, REL_WHEEL, vertical));
        }
        if horizontal != 0 {
            events.push(event(EV_REL, REL_HWHEEL, horizontal));
        }
        if events.is_empty() {
            return Ok(());
        }
        events.push(event(EV_SYN, SYN_REPORT, 0));
        self.emit(&events)
    }

    pub fn key(&mut self, code: u16, down: bool) -> Result<()> {
        if !self.advertised.contains(&code) {
            bail!("key code {code} was not advertised to the kernel");
        }
        self.emit(&[
            event(EV_KEY, code, i32::from(down)),
            event(EV_SYN, SYN_REPORT, 0),
        ])
    }

    pub fn keys_down(&mut self, codes: &[u16]) -> Result<()> {
        for code in codes {
            self.key(*code, true)?;
        }
        Ok(())
    }

    pub fn keys_up(&mut self, codes: &[u16]) -> Result<()> {
        for code in codes.iter().rev() {
            self.key(*code, false)?;
        }
        Ok(())
    }

    pub fn tap_with_mods(&mut self, mods: &[u16], code: u16) -> Result<()> {
        for modifier in mods {
            self.emit(&[event(EV_KEY, *modifier, 1)])?;
        }
        if !mods.is_empty() {
            self.sync()?;
        }

        self.emit(&[
            event(EV_KEY, code, 1),
            event(EV_SYN, SYN_REPORT, 0),
            event(EV_KEY, code, 0),
            event(EV_SYN, SYN_REPORT, 0),
        ])?;

        for modifier in mods.iter().rev() {
            self.emit(&[event(EV_KEY, *modifier, 0)])?;
        }
        if !mods.is_empty() {
            self.sync()?;
        }
        Ok(())
    }

    pub fn type_text(&mut self, text: &str) -> Result<()> {
        for ch in text.chars() {
            let Some((code, needs_shift)) = keys::for_char(ch) else {
                tracing::debug!("skipping unsupported character {ch:?}");
                continue;
            };

            let mut events = Vec::with_capacity(8);
            if needs_shift {
                events.push(event(EV_KEY, keys::KEY_LEFTSHIFT, 1));
            }
            events.push(event(EV_KEY, code, 1));
            events.push(event(EV_SYN, SYN_REPORT, 0));
            events.push(event(EV_KEY, code, 0));
            if needs_shift {
                events.push(event(EV_KEY, keys::KEY_LEFTSHIFT, 0));
            }
            events.push(event(EV_SYN, SYN_REPORT, 0));
            self.emit(&events)?;

            // Compositors coalesce bursts; a tiny gap keeps every character.
            std::thread::sleep(Duration::from_millis(3));
        }
        Ok(())
    }
}

impl Drop for Device {
    fn drop(&mut self) {
        unsafe {
            libc::ioctl(self.fd, UI_DEV_DESTROY);
        }
        let _ = &self.file;
    }
}

unsafe fn ioctl_int(fd: RawFd, request: libc::c_ulong, value: i32) -> Result<()> {
    if libc::ioctl(fd, request, value) < 0 {
        return Err(std::io::Error::last_os_error())
            .with_context(|| format!("uinput ioctl {request:#x}"));
    }
    Ok(())
}
