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
| Android client | **Working** — stream over Wi-Fi or USB cable, direct touch or touchpad gestures, keyboard, QR pairing, background session, quality presets, second screen, phone audio, in-app updates |
| Windows host | Preview — screen via `gdigrab`, input not implemented yet |
| macOS host | Preview — screen via `avfoundation`, input not implemented yet |
| Clipboard, file transfer | Roadmap |

Screens stream as JPEG at a paced 15 fps by default. On this project's test machine (1366×768, Hyprland, `grim`) that is ~20 fps at ~2 MB/s over Wi-Fi, with end-to-end latency around 100 ms. On a slow link, pick a lower preset in the app — the host reconfigures without restarting.

## Pair once, then it is one tap

Start the host and it prints a QR code plus a short 8-character code:

```
  PocketDesk 0.2.0
  ─────────────────────────────────────────────
  stream   grim (wayland) · 15 fps · quality 75
  display  hyprctl:eDP-1 (1366x768 at 0,0)
  server   http://192.168.1.24:8765
  code     K7M4-QX2B
  input    enabled (uinput virtual pointer + keyboard)

  Scan this with PocketDesk → "Scan QR"
  ▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄
  ... a real QR code, in your terminal ...
```

In the app:

1. **Scan QR code** — the camera reads the code and connects immediately.
2. Or type the 8-character code by hand (dashes and lowercase are fine: `k7m4 qx2b`).
3. **Remember this device** (on by default) keeps the machine and code, so next time is one tap on **Reconnect**. Untick it for a one-time login — nothing is stored, and **Forget** wipes a saved machine.

No 64-character tokens, no typing IP addresses twice.

> **Upgrading from 0.1?** The host rotates the old 64-character token to a short
> code on first start (it prints a warning). Re-scan the QR once; the app's
> *Forget* / *Reconnect* controls then use the new code.

## Install

Grab the files from [Releases](https://github.com/pdev-labs/pocketdesk/releases):

* **Phone:** `pocketdesk.apk` (Android 8+). Sideload it; there is no Play Store listing.
* **Computer:** `pocketdesk-linux-x86_64.tar.gz`, unpack it anywhere.

```bash
tar xzf pocketdesk-linux-x86_64.tar.gz
cd pocketdesk-linux-x86_64
./pocketdesk
```

### The app

The interface is built with Material You: **Home** puts the computers found on your
network first, **Devices** keeps the paired machine and the USB/Wi-Fi choice, and
**Settings** collects quality, screen size, input, scrolling, audio, toolbar keys and
updates. On Android 12+ the palette follows your wallpaper; older phones use the
built-in dark seed. During a session the controls are a floating pill that fades out
after a few seconds — tap **☰** to bring it back.

### Finding the machine

You do not have to type an IP. The host broadcasts a small beacon on **UDP 8766**
every three seconds and answers discovery probes; the app lists what it finds
under **FOUND ON THIS NETWORK**. Tap a machine to fill in its address, then enter
the 8-character code (or scan the QR once and it reconnects automatically later).

The beacon contains the machine name, port and version — never the pairing code.
Discovery is LAN-only; if a firewall blocks UDP 8766, the list stays empty and
manual entry/QR still works. `--no-usb` does not affect discovery.

### Let the phone control the machine

Screen sharing works out of the box. Mouse and keyboard need one-time permission to create a virtual input device:

```bash
sudo ./setup-linux-input.sh    # creates /dev/uinput access for your user
# log out and back in, or: newgrp pocketdesk-input
```

Without it the host still streams and the app shows **view only** with the reason on screen.

## USB cable mode

Wi-Fi is convenient, but the cable is faster and does not compete with anything
else on the network. PocketDesk supports both, chosen with a checkbox on the
connect screen:

1. Enable **USB debugging** on the phone and plug it into the computer.
2. Start the host. It automatically runs `adb reverse tcp:8765 tcp:8765` for
   every attached device and prints:
   `usb      127.0.0.1:8765 over adb reverse (1 device)`.
3. In the app tick **USB cable (fast)**. The host field switches to `127.0.0.1`
   and the app talks to the host through the cable.
4. The cable profile raises the stream to **30 fps · q90** and sizes a virtual
   second monitor at 2400 px on the long edge (vs 1600 on Wi-Fi).

The tunnel is re-applied every ten seconds, so unplugging and replugging is
handled without restarting the host. Untick the box to go back to the saved
Wi-Fi address; the two addresses are kept separately, so switching is one tap.

Notes:

* `adb` must be installed on the computer (`android-tools` on Arch,
  `adb` on Debian/Ubuntu). The phone still needs USB debugging, exactly as for
  `adb` itself. No root, no extra app.
* The forwarded port is still protected by the pairing code; USB does not
  disable authentication.
* Prefer not to let the host touch adb? Start it with `--no-usb`.
* USB tethering (the phone's network-sharing mode) also works: connect to the
  host address shown on that interface and leave the cable box unticked.

## Runs in the background

The session lives in a foreground service, not in the screen you are looking at:

* Swipe the app out of recents, or press Back — **the connection stays up**.
* A quiet notification shows the live fps and has a **Stop** action.
* Tapping **Stop** asks *"Stop remote session?"* first; **Keep running** dismisses it. Nothing disconnects on a single accidental tap.
* Reopen the app from the launcher and you are dropped straight back into the running session, with the last frame already on screen.
* The in-app **Exit** chip asks the same confirmation.

## Performance

The stream is only as expensive as what actually changed:

| Situation | Traffic |
| --- | --- |
| Static desktop (idle) | ~0.4 Mbit/s — byte-identical frames are not sent at all |
| App in the background | 0 — the phone asks the host to pause frames |
| Active desktop, 1600×720, q65, 15 fps | ~1.5 MB/s |
| Active desktop, Low data preset | ~0.6 MB/s |

* **Unchanged frames are dropped.** JPEG encoding is deterministic, so a still desktop
  produces byte-identical frames; the capture thread compares and sends nothing. A late
  joiner, or a phone returning from the background, gets the last frame immediately.
* **Background = silent.** `{"t":"pause"}` stops frames for that client while the socket,
  pings and stats stay up. Resume is a single message and the first frame follows at once.
* **No allocation storm.** The phone rotates three reusable bitmaps instead of allocating
  ~4.6 MB per decoded frame.
* **Auto-quality.** If the phone draws less than 60% of what the host sends for two
  consecutive 5-second windows, it steps down one preset (never twice, never saved) and
  tells you in a toast. Static screens are excluded, so an idle desktop never triggers it.
* **Pick a preset** in **⋯ → Quality & frame rate** — the host applies it without restarting.

Measured on the reference laptop (Hyprland, 1366×768 panel, 1600×720 virtual monitor):
`grim` needs 39–47 ms per frame with the cursor, so ~20 fps is the ceiling on this
backend; the default 15 fps leaves headroom for encoding and Wi-Fi.

## Updates

The app updates itself from this repository's releases:

**⋯ → Check for updates** (also checked quietly on launch, at most every 6 hours).

1. Fetch `releases/latest/download/latest.json` — version, versionCode, size, SHA-256.
2. Download `pocketdesk.apk` with a progress indicator.
3. Verify the **SHA-256** and the **signing certificate** against the installed app.
4. Hand the file to Android's installer (one confirmation tap).

The certificate check is the real protection: an APK signed with any other key is
refused before the installer opens, so a compromised download cannot replace the app.

**One-time step for installs from v0.3.0 or older:** those builds were signed with a
throwaway CI key, so install v0.4.0 by hand once (uninstall the old app first). From
v0.4.0 on, every release installs over the previous one, keeping your saved machines.

Release certificate (SHA-256):
`A6:19:9D:C0:B7:5A:FD:BE:A0:46:D8:3D:00:12:54:35:DF:60:AB:95:1D:83:54:C3:CF:4C:4E:ED:D1:7B:B2:DA`

## Touch controls

Two input modes, picked with **⋯ → Input mode**:

### Direct touch (default)

| Gesture / control | What it does |
| --- | --- |
| Tap | Left click at that point |
| Drag | Drag with the left button held |
| Long press | Right click |
| Two fingers, move | Scroll (vertical and horizontal) |

### Touchpad

The phone becomes a trackpad, so the cursor moves relatively instead of jumping to
your finger. This is the comfortable mode when the phone is a second monitor.

| Gesture | What it does |
| --- | --- |
| One finger, move | Move the pointer |
| Tap | Left click |
| Hold, then move | Click-and-drag |
| Double-tap, then move | Drag (double-click style) |
| Two fingers, move | Scroll — natural direction by default |
| Two-finger tap | Right click |
| Three-finger tap | Middle click |

**⋯ → Input mode → Touchpad options** has tap-to-click, natural scrolling and
pointer speed (slow / normal / fast). Every option is remembered.

| Control | What it does |
| --- | --- |
| Toolbar **Keys** | Show the phone keyboard — typing lands in the focused window |
| Toolbar **Ctrl/Alt/Shift/Super** | Latch a modifier, then press a key chip |
| Toolbar **Exit** | Stop the session (with confirmation) |
| Back / swipe away | Keep the session running in the background |

The stream can meet the phone in three ways (**⋯ → Screen size**): **Fit** letterboxes
the whole desktop, **Fill** crops the sides while keeping proportions, and **Stretch**
distorts it to fill the display exactly. The app switches to landscape automatically.

## Audio

**⋯ → Audio output** chooses where this machine's sound goes:

* **This machine** — nothing is streamed; the host behaves normally.
* **Phone** — the host captures its default monitor source with `parec` and streams
  PCM (48 kHz, stereo, ~1.4 Mbit/s) over a separate WebSocket. The phone plays it with
  `AudioTrack`, including while the app is in the background.
* **Mute this machine** (default on) silences the host's default sink while the phone
  is listening, so sound comes out of one place only. It is restored when the audio
  socket closes, the session stops, or the app disconnects.

Audio needs PulseAudio or PipeWire (`parec`, from `pulseaudio-utils`) on the host. If it
is missing, the menu explains why instead of failing silently.

## Quality, keys and full screen

The toolbar is yours. **⋯** in the toolbar opens the session menu:

* **Quality & frame rate** — four presets, applied on the host while you watch:
  *Low data* (10 fps, q45, 0.75×), *Balanced* (15 fps, q65), *Sharp* (20 fps, q82),
  *Smooth* (30 fps, q60). The choice is remembered and re-applied on every connect.
* **Choose keys** — tick the buttons you want in the toolbar: modifiers, Esc/Tab/Enter,
  arrows, Home/End/PgUp/PgDn, Ins/Del, F1–F12, PrintScreen. **Reset** brings back the
  default set. The *Keys* (keyboard) and *Exit* buttons are always there.
* **Input mode** — direct touch or touchpad gestures (see above).
* **Audio output** — this machine or the phone (see above).
* **Screen size** — fit (black bars), fill (crop the sides) or stretch (fill exactly,
  may distort) for a mismatched aspect ratio.
* **Hide toolbar (full screen)** — the desktop gets the whole display. A small **☰**
  tab in the top-right corner brings it back; the setting survives restarts.

There is no fixed limit on how many keys you tick; the row scrolls sideways.

The modifier chips combine with the phone's own keyboard, not just the toolbar
keys: tap **Super** (or Ctrl/Alt/Shift) so it lights up, then type `s` on the
Google keyboard and the host receives `Super+S`. Shift works the same way, and
the chips stay latched until you tap them again, so Shift can cover a whole word.

## Use the phone as a second screen

Mirroring copies the laptop panel. *Second screen* instead asks the host for a
**virtual monitor sized to your phone** and streams *that*, so you get extra desktop
space rather than a duplicate:

```text
  eDP-1 (1366×768)                    pocketdesk-phone (1600×720)
  ┌──────────────┐                    ┌───────────────────────┐
  │  your work   │                    │  windows you move here│
  └──────────────┘                    └───────────────────────┘
          └───────────── one desktop, left to right ──────────┘
```

In the app: **⋯ → Use phone as a second screen**. The host creates a Hyprland
headless output named `pocketdesk-phone-1`, parks it to the right of your desktop, and
starts capturing it. Move a window onto it with `Super+Shift+←/→`, or launch apps and
let the compositor place them there. Your touch now drives that monitor, because the
pointer is mapped into its rectangle.

**The mode belongs to each phone.** Every connected phone picks mirror or second
screen for itself:

* One phone can mirror the laptop while another is extended; they do not switch each
  other.
* Two extended phones get two independent monitors (`pocketdesk-phone-1`,
  `pocketdesk-phone-2`, …), each sized to its own phone and captured separately.
* Turning it off with **⋯ → Back to mirroring** (or closing that session) removes only
  that phone's virtual monitor. Other phones keep streaming.
* Prefer it from the terminal? `pocketdesk --extend 1600x900` starts with a virtual
  monitor as the base stream; it survives the phone disconnecting, and phones that
  choose mirror see that same base output.
* Second screen needs **Hyprland** (it is built on `hyprctl`'s headless outputs).
  On X11, Windows and macOS, mirroring still works.

## Why it is built this way

* **Frames are JPEG, not a video stream.** One decoder on the phone, no codec negotiation, no B-frames to buffer. A slow link means fewer frames, never seconds of latency.
* **`grim` on Wayland, `ffmpeg` elsewhere.** Hyprland and Sway expose screen capture through `wlr-screencopy`, which `grim` speaks. X11, Windows and macOS have first-class grabbers in `ffmpeg`.
* **One capture thread, many viewers.** A phone, a tablet and a browser tab still cost exactly one screenshot per frame.
* **uinput, not X11 automation.** A virtual absolute pointer plus a full keyboard works identically on Wayland and X11 and needs no compositor plugins.
* **A short code plus a QR.** 8 characters from an unambiguous alphabet (~40 bits) with per-address lockout, and a QR so most people never type it at all.
* **Token in constant time, no anonymous reads.** Screen bytes only ever leave over an authenticated socket.

## Using it from outside your LAN

Do not port-forward this to the internet. Put the machine and the phone on a private network instead:

```bash
# on the computer
sudo tailscale up
./pocketdesk --bind 100.x.y.z      # or keep 0.0.0.0 and let the firewall decide

# on the phone
# install Tailscale, sign in to the same tailnet, scan the QR from that address
```

WireGuard, ZeroTier or `ssh -L 8765:127.0.0.1:8765` work the same way. If you must expose it, read [docs/security.md](docs/security.md) first.

## Options

```
pocketdesk --help

  --bind <ADDR>        listen address (default 0.0.0.0; use 127.0.0.1 behind a tunnel)
  --port <PORT>        default 8765
  --token <TOKEN>      override the stored pairing code
  --capture <KIND>     auto | grim | ffmpeg | test
  --fps <N>            target capture rate (default 15)
  --quality <1-100>    JPEG quality (default 75)
  --scale <F>          downscale before encoding, e.g. 0.75 on slow Wi-Fi
  --output <MONITOR>   e.g. eDP-1; defaults to the focused monitor
  --no-cursor          hide the cursor from the stream
  --no-qr              do not print the pairing QR in the terminal
  --extend <WxH>       start with a virtual monitor of this size (second screen, Hyprland)
  --no-usb             do not configure adb reverse for USB-cable connections
  --read-only          stream only, never inject input
  --print-token        print the saved pairing code and exit
```

Try `--capture test` to get a moving test pattern without touching your real screen — handy to check the app or a build.

## Build it yourself

Everything is built in GitHub Actions, so no local Android SDK or Rust toolchain is required:

* `.github/workflows/build.yml` builds the APK (debug + release) and the host for Linux, Windows and macOS on every push.
* Pushing a `v*` tag publishes a release with all artifacts, `SHA256SUMS.txt` and
  `latest.json` (the update manifest the app reads).
* Release APKs are signed with a persistent key stored in repository secrets
  (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`). CI verifies the certificate with `apksigner` on every tag,
  so a missigned APK fails the build instead of shipping an uninstallable app.
* No secrets → the build falls back to the debug key and the tag job fails the
  certificate check. Fork PRs still build.

Locally, if you do have the tools:

```bash
cd host && cargo build --release      # Rust 1.80+
cd android && gradle assembleDebug    # JDK 17 + Android SDK 35, Gradle 8.9+
```

## Repository layout

```
host/              Rust daemon: capture, WebSocket server, input injection, pairing QR
  src/capture/     grim (Wayland), ffmpeg (X11/Win/macOS), test pattern
  src/input/       uinput virtual pointer + keyboard (Linux), stubs elsewhere
  src/pairing.rs   short code/QR payload and terminal renderer
  src/second_screen.rs  Hyprland headless monitor for second-screen mode
android/           Kotlin app: session hub + foreground service, CameraX/ML Kit scanner
docs/              architecture, wire protocol, Linux setup, security, roadmap
```

* [docs/architecture.md](docs/architecture.md)
* [docs/protocol.md](docs/protocol.md)
* [docs/linux-setup.md](docs/linux-setup.md)
* [docs/security.md](docs/security.md)
* [docs/roadmap.md](docs/roadmap.md)

## Roadmap

H.264 via MediaCodec for ~4× less bandwidth, TLS with certificate pinning, clipboard sync, native input on Windows and macOS, multi-monitor switching, and second-screen support beyond Hyprland. See [docs/roadmap.md](docs/roadmap.md).

## License

MIT — see [LICENSE](LICENSE).
