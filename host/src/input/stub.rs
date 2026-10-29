//! Placeholder input backend for Windows and macOS.
//!
//! The protocol and the phone app are already platform-neutral; v0.1 only ships
//! the Linux uinput backend. These stubs let the host build (and stream) on
//! Windows/macOS while injection lands in a later release.

use anyhow::Result;

pub struct Device;

impl Device {
    pub fn new() -> Result<Self> {
        anyhow::bail!(
            "input injection is not implemented on this platform yet \
             (v0.1 supports Linux; Windows/macOS are next) — running in view-only mode"
        )
    }

    pub fn new_relative() -> Result<Self> {
        anyhow::bail!(
            "touchpad input is not implemented on this platform yet \
             (v0.1 supports Linux; Windows/macOS are next) — running in view-only mode"
        )
    }

    pub fn move_abs(&mut self, _x: i32, _y: i32) -> Result<()> {
        Ok(())
    }

    pub fn move_rel(&mut self, _dx: f32, _dy: f32) -> Result<()> {
        Ok(())
    }

    pub fn button(&mut self, _name: &str, _down: bool) -> Result<()> {
        Ok(())
    }

    pub fn click(&mut self, _name: &str) -> Result<()> {
        Ok(())
    }

    pub fn scroll(&mut self, _dx: f32, _dy: f32) -> Result<()> {
        Ok(())
    }

    pub fn key(&mut self, _code: u16, _down: bool) -> Result<()> {
        Ok(())
    }

    pub fn keys_down(&mut self, _codes: &[u16]) -> Result<()> {
        Ok(())
    }

    pub fn keys_up(&mut self, _codes: &[u16]) -> Result<()> {
        Ok(())
    }

    pub fn tap_with_mods(&mut self, _mods: &[u16], _code: u16) -> Result<()> {
        Ok(())
    }

    pub fn type_text_with(&mut self, _text: &str, _mods: &[String]) -> Result<()> {
        Ok(())
    }
}
