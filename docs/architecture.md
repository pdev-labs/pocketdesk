# Architecture

PocketDesk is deliberately small: one Rust daemon on the computer, one Kotlin app on the phone, and a single WebSocket between them.

## Process model

```
                 ┌──────────────────────────── pocketdesk host ────────────────────────────┐
                 │                                                                          │
  grim / ffmpeg ─┼─► capture thread ──► broadcast channel (capacity 2) ──► session task 1 ──┼─► phone A
   (one frame    │        │                        │                        session task 2 ──┼─► phone B
    at a time)   │        └─► measured fps                                        │         │
                 │                                                               ▼         │
                 │   axum HTTP + WS ──► JSON input ──► input injector (uinput)     │         │
                 └──────────────────────────────────────────────────────────────────────────┘
```

* **Capture thread** — a dedicated OS thread runs the blocking capture backend in a loop. It sleeps when nobody is subscribed (`broadcast::Receiver` count is zero), so an idle host costs nothing.
* **Broadcast channel** — capacity 2, `Arc<Frame>`. Slow clients are dropped rather than queued; a phone that cannot keep up sees fewer frames, never a growing delay.
* **Session task per client** — axum/WebSocket. It forwards frames, parses input messages, and sends a stats line every 5 seconds.
* **Input injector** — one `uinput` device for the whole process, guarded by a mutex whose critical section contains only `write(2)` calls (no `await`).

Everything is `tokio`, except the capture and ffmpeg-reader threads, which are blocking by nature.

## Why JPEG instead of H.264

H.264 would cut bandwidth 3–5×, but it brings a codec pipeline (SPS/PPS, timestamps, decoder surface management) and hides latency in a bitrate buffer. JPEG gives:

* one decoder on the phone (`BitmapFactory`), no hardware-codec quirks;
* frame-independent delivery — every frame is a complete picture, so a dropped packet costs 1/15th of a second, not a keyframe interval;
* trivial debuggability (`curl`-like tools can inspect the stream).

At 1366×768 / q75 a frame is ~100 KB, so 15 fps ≈ 12 Mbit/s on the LAN. `--scale 0.75` and `--quality 60` halve that when needed. H.264 is planned for v0.2 (see [roadmap](roadmap.md)).

## Capture backends

| Backend | Platforms | Mechanism | Default when |
| --- | --- | --- | --- |
| `grim` | Wayland (wlroots: Hyprland, Sway, river) | one `grim -t jpeg -` per frame | `WAYLAND_DISPLAY` set and `grim` installed |
| `ffmpeg` | X11, Windows, macOS | `x11grab` / `gdigrab` / `avfoundation` → `-f mjpeg -`, frames re-assembled from stdout | `ffmpeg` installed |
| `test` | any | synthetic moving pattern, encoded in-process | nothing else available |

`--capture auto` picks in that order. The chosen backend and its measured rate are printed at startup and shown in the phone toolbar.

Measured on the reference machine (Hyprland 0.56, 1366×768, Ryzen laptop): `grim` takes ~55–80 ms per frame including process spawn and JPEG encode. That is the practical ceiling of the Wayland backend; the pacer simply stops at the frame budget.

A native `wlr-screencopy` client (no process per frame) is planned and would roughly double the ceiling.

## Input model

The host creates one virtual device:

* absolute pointer — `ABS_X`/`ABS_Y`, range 0..32767;
* buttons — `BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`;
* wheel — `REL_WHEEL`, `REL_HWHEEL`;
* a full US keyboard layout, shifted characters included.

Phone coordinates are **normalized inside the frame** (0..1), so the mapping is resolution-independent and survives `--scale`, DPI changes and window resizes.

The frame covers one monitor; an absolute pointer addresses the whole desktop. The host therefore keeps two rectangles, discovered from `hyprctl -j monitors`:

```
layout   = bounding box of every monitor   (e.g. 0,0 → 3200×1080)
captured = the monitor being streamed      (e.g. 1366×768 at 0,0)

abs_x = (captured.x + nx·captured.w − layout.x) / layout.w · 32767
```

The layout is re-read every 20 seconds, so hot-plugging a display or rotating the tablet does not desync the pointer.

**Why uinput and not XTest/X11 automation:** uinput is below the display server, so the same code drives Wayland and X11, and the compositor treats events as first-class input (focus, hotkeys, drag-and-drop). It needs the udev rule in `scripts/setup-linux-input.sh`; without it the host degrades to view-only instead of failing.

## Latency budget (LAN, reference machine)

| Stage | Cost |
| --- | --- |
| `grim` capture + JPEG encode | 55–80 ms |
| WebSocket transport | 2–10 ms |
| Phone JPEG decode (1366×768) | 15–25 ms |
| Touch event → uinput | 1–3 ms |
| **Perceived (frame → eyes)** | **~90–120 ms** |

Input feels faster than video because pointer events take the direct path (~5 ms); the frame confirms the action a moment later.

## Failure behaviour

* Capture error → logged, retried after 750 ms; the phone keeps the last frame.
* Input unavailable (no `/dev/uinput`) → stream continues, `hello` reports `input: false` with the reason, app shows **view only**.
* Slow client / congested link → broadcast lag drops frames, never blocks capture.
* Phone sleeps / network drops → OkHttp reconnects; the app retries four times with exponential backoff (1, 2, 4, 8 s) before returning to the pairing screen.
* Host dies → socket closes, the phone shows "Connection lost" and retries.

## What is intentionally missing in v0.1

Clipboard, audio, file transfer, multi-monitor switching, TLS, Windows/macOS input. All are additive; none require changing the frame or input model.
