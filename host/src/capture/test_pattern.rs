//! Synthetic moving test pattern — no display, no dependencies.
//!
//! Useful to check the phone app, the network path, or a build in CI before the
//! capture backend is configured.

use anyhow::Result;
use jpeg_encoder::{ColorType, Encoder};

use crate::capture::{jpeg_frame, Capture, CaptureSettings, Frame};

pub struct TestPattern {
    w: usize,
    h: usize,
    base: Vec<u8>,
    tick: u64,
    quality: u8,
}

impl TestPattern {
    pub fn new(settings: &CaptureSettings) -> Self {
        let w = 1280usize;
        let h = 720usize;
        let mut base = vec![0u8; w * h * 3];

        for y in 0..h {
            for x in 0..w {
                let checker = ((x / 80) + (y / 80)) % 2 == 0;
                let depth = (y as f32 / h as f32 * 26.0) as u8;
                let (r, g, b) = if checker {
                    (16 + depth, 18 + depth, 26 + depth)
                } else {
                    (11 + depth, 13 + depth, 19 + depth)
                };
                let i = (y * w + x) * 3;
                base[i] = r;
                base[i + 1] = g;
                base[i + 2] = b;
            }
        }

        Self {
            w,
            h,
            base,
            tick: 0,
            quality: settings.quality,
        }
    }

    fn paint_rect(
        rgb: &mut [u8],
        stride: usize,
        x0: usize,
        y0: usize,
        rw: usize,
        rh: usize,
        color: [u8; 3],
    ) {
        for y in y0..(y0 + rh) {
            for x in x0..(x0 + rw) {
                let i = (y * stride + x) * 3;
                rgb[i..i + 3].copy_from_slice(&color);
            }
        }
    }
}

impl Capture for TestPattern {
    fn next_frame(&mut self) -> Result<Frame> {
        self.tick += 1;
        let mut rgb = self.base.clone();

        // A block that sweeps left to right, so movement is obvious.
        let block = 160usize;
        let travel = self.w.saturating_sub(block + 40);
        let x = 20 + ((self.tick as usize * 12) % travel.max(1));
        TestPattern::paint_rect(&mut rgb, self.w, x, 220, block, block, [108, 140, 255]);

        // A second one moving the other way.
        let y = 40 + ((self.tick as usize * 7) % (self.h.saturating_sub(120)));
        TestPattern::paint_rect(&mut rgb, self.w, 40, y, 24, 60, [255, 138, 92]);

        // Progress bar at the bottom.
        let bar_w = (self.tick as usize * 9) % self.w;
        TestPattern::paint_rect(&mut rgb, self.w, 0, self.h - 12, bar_w, 12, [92, 214, 160]);

        let mut out = Vec::with_capacity(self.w * self.h / 6);
        let encoder = Encoder::new(&mut out, self.quality);
        encoder
            .encode(&rgb, self.w as u16, self.h as u16, ColorType::Rgb)
            .map_err(|e| anyhow::anyhow!("jpeg encode failed: {e:?}"))?;

        jpeg_frame(out)
    }

    fn hint_size(&self) -> (u32, u32) {
        (self.w as u32, self.h as u32)
    }
}
