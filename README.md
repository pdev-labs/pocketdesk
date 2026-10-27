# PocketDesk

[![build](https://github.com/pdev-labs/pocketdesk/actions/workflows/build.yml/badge.svg)](https://github.com/pdev-labs/pocketdesk/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/pdev-labs/pocketdesk?include_prereleases&sort=semver)](https://github.com/pdev-labs/pocketdesk/releases)
[![license](https://img.shields.io/badge/license-MIT-6C8CFF)](LICENSE)

**Your desktop, in your pocket.** A small Rust host streams this machine's screen to an Android phone over your own network, and turns the phone into the mouse and keyboard. No account, no cloud relay, no subscription.

```
  ┌────────────────────┐        WebSocket (JPEG frames)        ┌──────────────────┐
  │  Linux / Win / mac │ ───────────────────────────────────▶ │  Android phone   │
  │  pocketdesk host   │ ◀─────────────────────────────────── │  PocketDesk app  │
  │  grim / ffmpeg     │        JSON pointer + keys           │  touch → input   │
  └────────────────────┘                                      └──────────────────┘
```

| Piece | State |
| --- | --- |
| Linux host (Hyprland/Sway/grim, X11/ffmpeg) | **Working** — screen, pointer, keyboard |
| Android client | **Working** — stream, touch pointer, soft keyboard, modifiers |
| Windows host | Preview — screen via `gdigrab`, input not implemented yet |
| macOS host | Preview — screen via `avfoundation`, input not implemented yet |
| Clipboard, audio, file transfer | Roadmap |

Screens stream as JPEG at a paced 15 fps by default. On this project's test machine (1366×768, Hyprland, `grim`) that is ~20 fps at ~2 MB/s over Wi-Fi, with end-to-end latency around 100 ms — enough to edit code, move windows, or answer a message from the sofa.

## Install

Grab the two files for your setup from [Releases](https://github.com/pdev-labs/pocketdesk/releases):

* **Phone:** `pocketdesk.apk` (Android 8+). Sideload it; there is no Play Store listing.
* **Computer:** `pocketdesk-linux-x86_64.tar.gz`, unpack it anywhere.

```bash
tar xzf pocketdesk-linux-x86_64.tar.gz
cd pocketdesk-linux-x86_64
./pocketdesk
```

The host prints everything you need:

```
  PocketDesk 0.1.0
  ─────────────────────────────────────────────
  stream   grim (wayland) · 20 fps · quality 75
  display  hyprctl:eDP-1 (1366x768 at 0,0)
  server   http://192.168.1.24:8765
  token    8f0c…c41a
  input    enabled (uinput virtual pointer + keyboard)
```

Type the address and token into the app, tap **Connect**, and your desktop appears.

### Let the phone control the machine

Screen sharing works out of the box. Mouse and keyboard need one-time permission to create a virtual input device:

```bash
sudo ./setup-linux-input.sh    # creates /dev/uinput access for your user
# log out and back in, or: newgrp pocketdesk-input
```

Without it the host still streams and the app shows **view only** with the reason on screen.

## Touch controls

| Gesture | What it does |
| --- | --- |
| Tap | Left click at that point |
| Drag | Drag with the left button held |
| Long press | Right click |
| Two fingers, move up/down | Scroll wheel |
| Toolbar **Keys** | Show the phone keyboard — typing lands in the focused window |
| Toolbar **Ctrl/Alt/Shift/Super** | Latch a modifier, then press a key chip |

The stream is letterboxed to your monitor's aspect ratio; the app switches to landscape automatically.

## Why it is built this way

* **Frames are JPEG, not a video stream.** One decoder on the phone, no codec negotiation, no B-frames to buffer. It degrades gracefully: a slow link just means fewer frames, never seconds of latency.
* **`grim` on Wayland, `ffmpeg` elsewhere.** Hyprland and Sway expose screen capture through `wlr-screencopy`, which `grim` speaks. X11, Windows and macOS have first-class grabbers in `ffmpeg`.
* **One capture thread, many viewers.** A phone, a tablet and a browser tab still cost exactly one screenshot per frame.
* **uinput, not X11 automation.** A virtual absolute pointer plus a full keyboard works identically on Wayland and X11 and needs no compositor plugins.
* **Token in constant time, no anonymous reads.** Screen bytes only ever leave over an authenticated socket.

## Using it from outside your LAN

Do not port-forward this to the internet. Put the machine and the phone on a private network instead:

```bash
# on the computer
sudo tailscale up
./pocketdesk --bind 100.x.y.z      # or keep 0.0.0.0 and let the firewall decide

# on the phone
# install Tailscale, sign in to the same tailnet, connect to the 100.x address
```

WireGuard, ZeroTier or `ssh -L 8765:127.0.0.1:8765` work the same way. If you must expose it, read [docs/security.md](docs/security.md) first.

## Options

```
pocketdesk --help

  --bind <ADDR>        listen address (default 0.0.0.0; use 127.0.0.1 behind a tunnel)
  --port <PORT>        default 8765
  --token <TOKEN>      override the stored pairing token
  --capture <KIND>     auto | grim | ffmpeg | test
  --fps <N>            target capture rate (default 15)
  --quality <1-100>    JPEG quality (default 75)
  --scale <F>          downscale before encoding, e.g. 0.75 on slow Wi-Fi
  --output <MONITOR>   e.g. eDP-1; defaults to the focused monitor
  --no-cursor          hide the cursor from the stream
  --read-only          stream only, never inject input
  --print-token        print the saved token and exit
```

Try `--capture test` to get a moving test pattern without touching your real screen — handy to check the app or a build.

## Build it yourself

Everything is built in GitHub Actions, so no local Android SDK or Rust toolchain is required:

* `.github/workflows/build.yml` builds the APK (debug + release) and the host for Linux, Windows and macOS on every push.
* Pushing a `v*` tag publishes a release with all artifacts and `SHA256SUMS.txt`.

Locally, if you do have the tools:

```bash
cd host && cargo build --release      # Rust 1.80+
cd android && gradle assembleDebug    # JDK 17 + Android SDK 35, Gradle 8.9+
```

## Repository layout

```
host/              Rust daemon: capture, WebSocket server, input injection
  src/capture/     grim (Wayland), ffmpeg (X11/Win/macOS), test pattern
  src/input/       uinput virtual pointer + keyboard (Linux), stubs elsewhere
android/           Kotlin app, plain Views + OkHttp + MediaCodec-free JPEG path
docs/              architecture, wire protocol, Linux setup, security, roadmap
```

* [docs/architecture.md](docs/architecture.md)
* [docs/protocol.md](docs/protocol.md)
* [docs/linux-setup.md](docs/linux-setup.md)
* [docs/security.md](docs/security.md)
* [docs/roadmap.md](docs/roadmap.md)

## Roadmap

H.264 via MediaCodec for ~4× less bandwidth, TLS with certificate pinning, clipboard sync, native input on Windows and macOS, multi-monitor switching, audio. See [docs/roadmap.md](docs/roadmap.md).

## License

MIT — see [LICENSE](LICENSE).
