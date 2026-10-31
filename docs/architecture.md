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

## Transports: LAN, USB and discovery

The WebSocket is transport-agnostic — only the address the phone dials changes.

* **LAN** — the app connects to the host's address directly (or through
  Tailscale/WireGuard when away from home).
* **USB** — the host runs `adb reverse tcp:8765 tcp:8765` for every attached
  device and re-checks every 10 seconds, so a replug needs no host restart. The
  phone then dials `127.0.0.1:8765`; from the server's point of view the peer is
  loopback, which is how it labels the session `USB` in the log. `--no-usb`
  disables the automatic setup. Because the pipe is short and lossless, the app
  raises its preset to 30 fps/q90 and asks for a 2400 px virtual monitor instead
  of 1600.
* **Discovery** — the host broadcasts a JSON beacon on UDP 8766 every three
  seconds (`app`, `name`, `port`, `version`) and answers `{"t":"discover"}`
  probes immediately. The app lists machines while the connect screen is
  visible and uses the packet's source address, not a self-reported one. The
  pairing token is never broadcast; discovery only saves typing an IP.

## Why JPEG instead of H.264

H.264 would cut bandwidth 3–5×, but it brings a codec pipeline (SPS/PPS, timestamps, decoder surface management) and hides latency in a bitrate buffer. JPEG gives:

* one decoder on the phone (`BitmapFactory`), no hardware-codec quirks;
* frame-independent delivery — every frame is a complete picture, so a dropped packet costs 1/15th of a second, not a keyframe interval;
* trivial debuggability (`curl`-like tools can inspect the stream).

At 1366×768 / q75 a frame is ~100 KB, so 15 fps ≈ 12 Mbit/s on the LAN. The phone's
quality presets (`--scale 0.75`, `--quality 45`) cut that by roughly half without
restarting the host. H.264 is planned for a later release (see [roadmap](roadmap.md)).

## Capture backends

| Backend | Platforms | Mechanism | Default when |
| --- | --- | --- | --- |
| `grim` | Wayland (wlroots: Hyprland, Sway, river) | one `grim -t jpeg -` per frame | `WAYLAND_DISPLAY` set and `grim` installed |
| `ffmpeg` | X11, Windows, macOS | `x11grab` / `gdigrab` / `avfoundation` → `-f mjpeg -`, frames re-assembled from stdout | `ffmpeg` installed |
| `test` | any | synthetic moving pattern, encoded in-process | nothing else available |

`--capture auto` picks in that order. The chosen backend and its measured rate are printed at startup and shown in the phone toolbar.

Measured on the reference machine (Hyprland 0.56, 1366×768, Ryzen laptop): `grim` takes ~55–80 ms per frame including process spawn and JPEG encode. That is the practical ceiling of the Wayland backend; the pacer simply stops at the frame budget.

A native `wlr-screencopy` client (no process per frame) is planned and would roughly double the ceiling.

### Live settings

The capture thread does not own its configuration. `CaptureSettings { fps, quality,
scale, output, cursor }` lives behind an `RwLock` in `AppState`; the thread compares
its applied copy against the shared one before every frame and, when they differ,
drops the backend and builds a new one:

```
phone ── {"t":"set_quality","quality":40,"fps":5} ──► AppState.settings
                                                          │
capture thread ── sees a difference ──► drop grim/ffmpeg ──► rebuild
                                                          │
phone ◄── {"t":"capture", …applied values…} ◄── reply ─────┘
```

The reply matters: the host clamps values (fps 1–60, quality 10–95, scale 0.25–1.0),
and the phone shows what the host actually chose rather than what it asked for.
Dropping the backend also kills the ffmpeg child, so nothing keeps encoding in the
background.

## Frame economy

Three mechanisms keep an idle or background session nearly free:

* **Duplicate suppression.** `grim`/ffmpeg output is deterministic, so the capture thread
  compares each frame's bytes with the previous one and does not broadcast an identical
  frame. `AppState.last_frame` holds the newest frame, which is sent to a new client right
  after `hello` and on `resume`, so a static desktop never looks blank.
* **Per-client pause.** The app sends `{"t":"pause"}` when it loses focus; the session
  task keeps draining the broadcast channel but writes nothing. `stats` and pings continue,
  so the connection stays warm. `{"t":"resume"}` re-sends the last frame immediately.
* **Decode ring.** The phone alternates three mutable bitmaps (`inBitmap`) instead of
  allocating a new ~4.6 MB bitmap per frame; three slots mean a bitmap is never reused
  while the view could still be drawing it.

The app also watches its own health: two consecutive 5-second windows where it renders
less than 60% of the frames the host sent imply a slow link, and it drops one quality
preset for the rest of the session.

## Second screen

"Extend" is the same pipeline pointed at a different monitor.

```
hyprctl output create headless pocketdesk-phone-N
hyprctl eval 'hl.monitor({output="pocketdesk-phone-N", mode="1600x720",
                          position="auto-right", scale=1})'
        │
        ├── per-view settings.output = "pocketdesk-phone-N"  → grim -o …
        ├── private capture thread + broadcast channel
        └── layout::detect()                                 → pointer maps there
```

* The mode is **per connection**, not per host. Every WebSocket session owns a
  `view::View`: a mirror view shares the base capture (one screenshot per frame for
  any number of phones), while an extended view owns a headless output, a capture
  thread, its own fps/quality settings and its own `Geometry`. One phone switching
  never moves another phone.
* The output is a real monitor: it gets workspaces, windows can be moved onto it with
  `Super+Shift+←/→`, and fullscreen works.
* `layout.rs` needs no special case — each virtual monitor is just another entry in
  `hyprctl -j monitors`, so the existing two-rectangle mapping (desktop bounding box
  vs that phone's captured monitor) does the right thing. Input from a phone is mapped
  through that phone's view geometry.
* Hyprland 0.5x parses config with Lua, so sizing goes through `hyprctl eval`;
  older builds fall back to `hyprctl keyword monitor …`.
* An on-demand display lives exactly as long as its connection: when that phone
  switches back to mirror, closes the app, or loses the socket, its `View` is dropped,
  which stops the capture thread and removes the output. Other phones are unaffected.
* A display created by `--extend` is the base output instead: it survives phone
  disconnects and is what mirror-mode phones see.
* Output names are handed out from a counter (`pocketdesk-phone-1`, `-2`, …), so two
  extended phones are two independent monitors rather than one shared one.

## Input model

The host creates one virtual device:

* absolute pointer — `ABS_X`/`ABS_Y`, range 0..32767;
* buttons — `BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`;
* wheel — `REL_WHEEL`, `REL_HWHEEL`;
* a full US keyboard layout, shifted characters included.

Phone coordinates are **normalized inside the frame** (0..1), so the mapping is resolution-independent and survives `--scale`, DPI changes and window resizes.

Keyboard input has two paths that share the same key table:

* `{"t":"key","name":"esc","mods":["ctrl"]}` — a single named key (or any one
  printable character, so `name:"c"` works) tapped while the modifiers are held.
* `{"t":"text","s":"s","mods":["super"]}` — the soft keyboard's committed text.
  Each character is tapped with the modifiers held down for the string, which is
  what makes `Super+S` and `Ctrl+C` work from the on-screen keyboard. Uppercase
  and shifted symbols add Shift per character unless Shift is already held, so
  `?` with Super becomes `Super+Shift+/`.

The frame covers one monitor; an absolute pointer addresses the whole desktop. The host therefore keeps two rectangles, discovered from `hyprctl -j monitors`:

```
layout   = bounding box of every monitor   (e.g. 0,0 → 3200×1080)
captured = the monitor being streamed      (e.g. 1366×768 at 0,0)

abs_x = (captured.x + nx·captured.w − layout.x) / layout.w · 32767
```

The layout is re-read every 20 seconds, so hot-plugging a display or rotating the tablet does not desync the pointer.

**Why uinput and not XTest/X11 automation:** uinput is below the display server, so the same code drives Wayland and X11, and the compositor treats events as first-class input (focus, hotkeys, drag-and-drop). It needs the udev rule in `scripts/setup-linux-input.sh`; without it the host degrades to view-only instead of failing.

## Audio

Audio is deliberately separate from the video socket. `/audio` authenticates with
the same token, then sends a JSON config followed by raw PCM (s16le, 48 kHz,
stereo) in 20 ms messages. The host captures the PulseAudio/PipeWire monitor of the
default sink with `parec`; the phone writes the bytes straight into `AudioTrack`.
No encoder means no codec negotiation and no resampling on weak phones, at the cost
of ~1.4 Mbit/s while audio is enabled.

`?mute=1` mutes the default sink for the lifetime of the socket, so the phone is the
only output. The mute is reference-counted across audio clients and undone when the
socket closes; a host shutdown also drops every socket, which restores the sink.

Because audio has its own connection it is unaffected by `{"t":"pause"}`: the screen
can stop streaming in the background while sound continues.

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
* Phone sleeps / app backgrounded → the foreground service keeps the process and the hub reconnects (1, 2, 4, 8 s, five attempts).
* Host dies → socket closes, the phone shows "Connection lost" and retries; after five failures the service stops and the app returns to the pairing screen with the reason.

## Pairing

Two paths, one secret:

1. **QR** — the host renders `pocketdesk://pair?v=1&host=…&port=…&token=…&name=…`
   as ANSI half-blocks (black modules on a white quiet zone, so it scans from a
   dark terminal). The app scans it with CameraX + ML Kit, fills every field and
   connects.
2. **Typed code** — the same token, 8 characters, compared case-insensitively
   while ignoring dashes and spaces.

The renderer is tested by decoding its matrix with an independent QR decoder
(`rqrr`) in CI, so a broken QR fails the build rather than the user's first run.

## Android session lifecycle

The session is **not** owned by the Activity:

```
MainActivity ──attach/detach──► SessionHub (singleton) ◄──listener── RemoteService
     │                              │
     │ renders frames               └── PocketDeskClient (WebSocket, decoder thread)
     ▼
  ScreenView
```

* `SessionHub` owns the socket, the last frame, hello and stats. It survives
  configuration changes and Activity destruction.
* `RemoteService` is a `specialUse` foreground service. It holds the process
  alive when the task is swiped out of recents, and it is the only place the
  notification exists.
* The notification shows the live state/fps and a **Stop** action. Stop opens a
  translucent `StopSessionActivity` with a confirmation dialog — a single
  accidental tap cannot kill a session.
* Reopening the app re-attaches to the hub and the last frame is drawn before the
  network even answers.
* Reconnects (1, 2, 4, 8 s, five attempts) run inside the hub, so they continue
  while the app is in the background.

## What is intentionally missing

Clipboard, file transfer, multi-monitor switching, TLS, macOS input. All are additive; none require changing the frame or input model.
